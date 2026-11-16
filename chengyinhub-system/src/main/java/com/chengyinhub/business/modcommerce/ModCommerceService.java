package com.chengyinhub.business.modcommerce;

import com.apple.itunes.storekit.model.InAppOwnershipType;
import com.apple.itunes.storekit.model.JWSTransactionDecodedPayload;
import com.apple.itunes.storekit.model.ResponseBodyV2DecodedPayload;
import com.apple.itunes.storekit.model.Type;
import com.chengyinhub.business.creatorearnings.CreatorEarningsService;
import com.chengyinhub.business.modcommerce.mapper.ModCommerceMapper;
import com.chengyinhub.business.moneyswitch.MoneyArea;
import com.chengyinhub.business.moneyswitch.MoneySwitchService;
import com.chengyinhub.business.platformpass.PlatformPassAppleProperties;
import com.chengyinhub.business.platformpass.PlatformPassAppleVerifier;
import com.chengyinhub.business.platformpass.PlatformPassException;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * R6c workshop-iap: the server side of the app's {@code /api/mod-commerce/*} contract
 * ({@code Core/WorkshopCommerceService.swift}, shapes in {@code Core/WorkshopCommerceContracts.swift}).
 *
 * <ul>
 *   <li><b>Offers</b>: operators map an approved template release ({@code workshop_version}) to one App Store
 *       non-consumable product id, price and currency ({@code mod_commerce_offer}).</li>
 *   <li><b>Orders</b> are server-owned: the server freezes the offer, issues the {@code appAccountToken}, bundle id,
 *       environment and product id, and keeps the payment and license in {@code workshop_order} (provider APPLE_IAP).
 *       The app's StoreKit progress (CREATED, NATIVE_READY, SUBMITTING, CANCELLED) lives in {@code mod_commerce_order}.</li>
 *   <li><b>Transactions</b> are verified by the pass agent's {@link PlatformPassAppleVerifier} (signature chain to the
 *       operator's Apple roots, bundle id, environment); this class then checks product id, appAccountToken, type,
 *       ownership, price and currency against the order. One original transaction id grants one order, once.</li>
 *   <li><b>Grant</b> appends the creator's {@code SALE} ledger row in the same transaction; a refund or revocation
 *       (App Store Server Notifications V2, routed here by product id) appends {@code SALE_REVERSAL}.</li>
 *   <li><b>Money switch</b>: everything that sells answers only while {@link #open()}; revocations are applied even
 *       when it is closed, because taking a refunded license back must never wait for "money on".</li>
 *   <li><b>Delivery</b> (MERCHANT-2): a purchase Apple has charged is always granted and restorable while
 *       {@link #deliverable()}; closing the money switch stops new sales only. An order can only exist if it was
 *       created while selling was open.</li>
 * </ul>
 */
@Service
public class ModCommerceService {
    private static final Logger log = LoggerFactory.getLogger(ModCommerceService.class);
    static final int PAGE = 50;
    static final String CHANNEL = "APPLE_STOREKIT";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "[A-Za-z0-9_-]{1,128}";
    private static final String PRODUCT = "[A-Za-z0-9._-]{1,255}";

    private final ModCommerceMapper mapper;
    private final ModCommerceProperties config;
    private final MoneySwitchService moneySwitch;
    private final PlatformPassAppleVerifier verifier;
    private final PlatformPassAppleProperties appleProperties;
    private final ObjectProvider<CreatorEarningsService> earnings;
    private final TransactionTemplate tx;
    private final TransactionTemplate inboxTx;

    public ModCommerceService(ModCommerceMapper mapper, ModCommerceProperties config, MoneySwitchService moneySwitch,
                              PlatformPassAppleVerifier verifier, PlatformPassAppleProperties appleProperties,
                              ObjectProvider<CreatorEarningsService> earnings, PlatformTransactionManager transactions) {
        this.mapper = mapper;
        this.config = config;
        this.moneySwitch = moneySwitch;
        this.verifier = verifier;
        this.appleProperties = appleProperties;
        this.earnings = earnings;
        this.tx = new TransactionTemplate(transactions);
        this.inboxTx = new TransactionTemplate(transactions);
        this.inboxTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------ switch

    /**
     * True only while {@code chengyin.mod-commerce.enabled} is true AND the money switch's {@code pass} area is open.
     * The pass area is the App Store in-app purchase rail (owner decision 2026-11-15: "in-app purchase for the pass and
     * templates"). A read failure counts as closed.
     */
    public boolean open() {
        if (!config.isEnabled()) return false;
        try {
            return Boolean.TRUE.equals(moneySwitch.snapshot().get(MoneyArea.PASS.key()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Delivery routes (order status, StoreKit transaction, restore, licences, cancel) and {@code ONE_TIME_CHARGE}
     * notifications need only {@code chengyin.mod-commerce.enabled}; the money switch does not stop them (MERCHANT-2).
     */
    public boolean deliverable() {
        return config.isEnabled();
    }

    /**
     * {@code GET /api/mod-commerce/storekit} (MERCHANT-9): the App Store facts the app needs to build its StoreKit
     * setup for templates, all from server configuration: bundle id, environment ({@code SANDBOX} / {@code PRODUCTION})
     * and every product id of this market (on sale or withdrawn, so a bought template stays restorable).
     * {@code selling} is {@link #open()}; when false the app may deliver and restore, never start a purchase.
     */
    public Map<String, Object> storeKit() {
        String reason = verifier.unavailableReason();
        String environment = reason == null ? verifier.configuredEnvironment() : null;
        if (reason == null && environment == null) reason = "APPLE_ENVIRONMENT_NOT_CONFIGURED";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", reason == null);
        out.put("unavailableReason", reason);
        out.put("selling", reason == null && open());
        out.put("market", market());
        out.put("bundleId", reason == null ? appleProperties.getBundleId() : null);
        out.put("environment", reason == null ? environment.toUpperCase(Locale.ROOT) : null);
        out.put("productIds", reason == null ? mapper.productIds(market()) : List.of());
        return out;
    }

    private String market() {
        String m = config.getMarket() == null ? "" : config.getMarket().trim().toUpperCase(Locale.ROOT);
        if (!m.equals("CN") && !m.equals("US")) throw failure("MOD_COMMERCE_MARKET_INVALID", 503);
        return m;
    }

    static String currencyFor(String market) { return "US".equals(market) ? "USD" : "CNY"; }
    static String storefrontFor(String market) { return "US".equals(market) ? "USA" : "CHN"; }

    // ------------------------------------------------------------------ offers

    public List<Map<String, Object>> offers(String before) {
        long cursor = Long.MAX_VALUE;
        if (before != null) {
            key(before);
            Long id = mapper.offerRowId(before);
            if (id == null) throw failure("OFFER_CURSOR_INVALID", 400);
            cursor = id;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : mapper.onSale(market(), cursor, PAGE)) out.add(offerView(row));
        return out;
    }

    public Map<String, Object> offer(String offerId) {
        return offerView(saleable(offerId));
    }

    private Map<String, Object> saleable(String offerId) {
        key(offerId);
        Map<String, Object> row = mapper.offer(offerId);
        if (row == null || !onSale(row)) throw failure("OFFER_NOT_FOUND", 404);
        return row;
    }

    private boolean onSale(Map<String, Object> row) {
        return num(row, "enabled") == 1 && "APPROVED".equals(row.get("review_state")) && market().equals(row.get("market"));
    }

    /** Exactly {@code WorkshopCommerceOffer}. Built only from immutable columns, so a frozen copy equals today's. */
    Map<String, Object> offerView(Map<String, Object> row) {
        JsonNode content = tree(str(row, "content_json"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("offerId", str(row, "offer_id"));
        out.put("moduleId", String.valueOf(num(row, "module_id")));
        out.put("releaseId", str(row, "version_id"));
        out.put("creatorId", num(row, "seller_id"));
        out.put("title", content.path("title").asText(""));
        out.put("currency", str(row, "currency"));
        out.put("priceMinor", num(row, "price_minor"));
        out.put("platform", "IOS");
        out.put("market", str(row, "market"));
        out.put("productType", "DIGITAL_MOD");
        out.put("channel", CHANNEL);
        out.put("terms", terms(row));
        return out;
    }

    /** Exactly {@code WorkshopCommerceTerms}, from the release's frozen terms (hash-checked against workshop_version). */
    Map<String, Object> terms(Map<String, Object> version) {
        String termsJson = str(version, "terms_json");
        if (!sha256(termsJson).equals(version.get("terms_hash"))
                || !sha256(str(version, "content_json")).equals(version.get("content_hash"))) {
            throw failure("RELEASE_INTEGRITY_FAILED", 409);
        }
        JsonNode t = tree(termsJson);
        List<String> regions = new ArrayList<>();
        t.path("allowedRegions").forEach(r -> regions.add(r.asText()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", t.path("version").asText(""));
        out.put("hash", str(version, "terms_hash"));
        out.put("contentHash", str(version, "content_hash"));
        out.put("commercialUse", t.path("commercialUse").asBoolean(false));
        out.put("allowedRegions", regions);
        out.put("installationLimit", t.path("installLimit").asLong(1));
        out.put("useDuration", "PERPETUAL_PURCHASED_VERSION");
        out.put("updatePolicy", "EXACT_PURCHASED_VERSION");
        out.put("redistribution", "PROHIBITED");
        out.put("refundPolicyReference", config.getRefundPolicyReference());
        return out;
    }

    // ------------------------------------------------------------------ orders

    public record Intent(String requestId, String offerId, String termsHash) { }

    /**
     * {@code POST orders}. The same request returns its original order. A non-consumable is bought once: if the buyer
     * already has a paid or in-flight order for this release, that earlier order is returned (the app adopts it and
     * cancels its new request).
     */
    public Map<String, Object> create(long buyer, Intent intent) {
        actor(buyer);
        validIntent(intent);
        String unavailable = verifier.unavailableReason();
        if (unavailable != null) throw failure("APPLE_IAP_UNAVAILABLE", 503);
        return tx.execute(s -> {
            Map<String, Object> existing = mapper.commerceOrderByRequest(buyer, intent.requestId());
            if (existing != null) {
                if (!intent.offerId().equals(existing.get("offer_id"))) throw failure("REQUEST_CONFLICT", 409);
                return orderView(lockOwned(buyer, str(existing, "order_no")));
            }
            Map<String, Object> offer = saleable(intent.offerId());
            if (!intent.termsHash().equals(offer.get("terms_hash"))) throw failure("TERMS_CHANGED", 409);
            if (num(offer, "seller_id") == buyer) throw failure("CANNOT_PURCHASE_OWN_RELEASE", 409);
            String earlier = mapper.earlierOrder(buyer, str(offer, "version_id"));
            if (earlier != null) return orderView(lockOwned(buyer, earlier));
            return orderView(insert(buyer, intent, offer));
        });
    }

    private Order insert(long buyer, Intent intent, Map<String, Object> offer) {
        String orderNo = "WKO_" + UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("orderNo", orderNo);
        row.put("owner", buyer);
        row.put("requestId", intent.requestId());
        row.put("requestHash", sha256(encode(List.of(intent.requestId(), intent.offerId(), intent.termsHash()))));
        row.put("versionId", offer.get("version_id"));
        row.put("currency", offer.get("currency"));
        row.put("amountMilliunits", num(offer, "price_minor") * 10);
        row.put("accountToken", UUID.randomUUID().toString());
        row.put("bundleId", verifierBundle());
        row.put("environment", verifier.configuredEnvironment());
        row.put("productId", offer.get("product_id"));
        row.put("offerId", intent.offerId());
        row.put("offerJson", encode(offerView(offer)));
        mapper.insertWorkshopOrder(row);
        mapper.insertCommerceOrder(row);
        return lockOwned(buyer, orderNo);
    }

    public Map<String, Object> lookup(long buyer, String requestId) {
        actor(buyer);
        key(requestId);
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> existing = mapper.commerceOrderByRequest(buyer, requestId);
        out.put("found", existing != null);
        out.put("order", existing == null ? null : tx.execute(s -> orderView(lockOwned(buyer, str(existing, "order_no")))));
        return out;
    }

    public Map<String, Object> byRequest(long buyer, String requestId) {
        actor(buyer);
        key(requestId);
        Map<String, Object> existing = mapper.commerceOrderByRequest(buyer, requestId);
        if (existing == null) throw failure("ORDER_NOT_FOUND", 404);
        return tx.execute(s -> orderView(lockOwned(buyer, str(existing, "order_no"))));
    }

    public Map<String, Object> order(long buyer, String orderKey) {
        actor(buyer);
        return tx.execute(s -> orderView(lockOwned(buyer, orderKey)));
    }

    /** The offer frozen into the order at creation, never today's catalogue row. */
    public Map<String, Object> orderOffer(long buyer, String orderKey) {
        actor(buyer);
        Order order = tx.execute(s -> lockOwned(buyer, orderKey));
        return object(str(order.commerce, "offer_json"));
    }

    /** {@code POST orders/{key}/payment}: CREATED becomes NATIVE_READY with the server-issued StoreKit parameters. */
    public Map<String, Object> payment(long buyer, String orderKey) {
        actor(buyer);
        return tx.execute(s -> {
            Order order = lockOwned(buyer, orderKey);
            if (order.paid()) return orderView(order);
            if (order.cancelled() || order.cancellationRequested()) throw failure("ORDER_NOT_PAYABLE", 409);
            if ("CREATED".equals(order.clientState())) mapper.moveClientState(orderKey, "CREATED", "NATIVE_READY");
            return orderView(lockOwned(buyer, orderKey));
        });
    }

    /** {@code GET orders/{key}/native-context}: re-read for recovery; never for a CREATED or cancelled order. */
    public Map<String, String> nativeContext(long buyer, String orderKey) {
        actor(buyer);
        Order order = tx.execute(s -> lockOwned(buyer, orderKey));
        if (!order.paid() && (order.cancelled() || "CREATED".equals(order.clientState()))) {
            throw failure("ORDER_NOT_READY", 409);
        }
        return parameters(order);
    }

    /** {@code POST orders/{key}/native-dispatch}: the single-use permission to call StoreKit purchase. */
    public Map<String, Object> nativeDispatch(long buyer, String orderKey) {
        actor(buyer);
        return tx.execute(s -> {
            Order order = lockOwned(buyer, orderKey);
            boolean allowed = !order.paid() && !order.cancelled() && "PENDING".equals(order.workshop.get("state"))
                    && mapper.dispatch(orderKey) == 1;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("orderKey", orderKey);
            out.put("dispatchAllowed", allowed);
            return out;
        });
    }

    /** {@code POST orders/{key}/native-transaction}: verify, match this order, grant once. */
    public Map<String, Object> nativeTransaction(long buyer, String orderKey, String signedTransaction) {
        actor(buyer);
        key(orderKey);
        Fact fact = verify(signedTransaction);
        return tx.execute(s -> {
            Order order = lockOwned(buyer, orderKey);
            apply(order, fact, "APPLE_REVOCATION");
            return orderView(lockOwned(buyer, orderKey));
        });
    }

    /**
     * {@code POST native-transactions/restore}: signed Apple evidence locates the ORIGINAL server order (by the
     * server-issued token, else the bound original transaction). The order must belong to the caller.
     */
    public Map<String, Object> restore(long buyer, String signedTransaction) {
        actor(buyer);
        Fact fact = verify(signedTransaction);
        return tx.execute(s -> {
            String orderNo = locate(fact);
            if (orderNo == null) throw failure("ORDER_NOT_FOUND", 404);
            Order order = lock(orderNo);
            if (num(order.workshop, "buyer_id") != buyer) throw failure("APPLE_ORDER_OWNER_CONFLICT", 403);
            apply(order, fact, "APPLE_REVOCATION");
            return orderView(lock(orderNo));
        });
    }

    /** No Apple call: the app's StoreKit queue and the notifications are the two ways a transaction arrives. */
    public Map<String, Object> reconcile(long buyer, String orderKey) {
        return order(buyer, orderKey);
    }

    public Map<String, Object> cancel(long buyer, String orderKey) {
        actor(buyer);
        return tx.execute(s -> {
            cancelLocked(lockOwned(buyer, orderKey));
            return orderView(lockOwned(buyer, orderKey));
        });
    }

    /**
     * {@code POST orders/by-request/cancel}. Cancels the request's order; a request that never reached the server is
     * recorded as a cancelled order so the same request id can never buy later.
     */
    public Map<String, Object> cancelRequest(long buyer, Intent intent) {
        actor(buyer);
        validIntent(intent);
        return tx.execute(s -> {
            Map<String, Object> existing = mapper.commerceOrderByRequest(buyer, intent.requestId());
            Order order;
            if (existing != null) {
                if (!intent.offerId().equals(existing.get("offer_id"))) throw failure("REQUEST_CONFLICT", 409);
                order = lockOwned(buyer, str(existing, "order_no"));
            } else {
                if (verifier.unavailableReason() != null) throw failure("APPLE_IAP_UNAVAILABLE", 503);
                Map<String, Object> offer = mapper.offer(intent.offerId());
                if (offer == null) throw failure("OFFER_NOT_FOUND", 404);
                if (!intent.termsHash().equals(offer.get("terms_hash"))) throw failure("TERMS_CHANGED", 409);
                order = insert(buyer, intent, offer);
            }
            cancelLocked(order);
            return orderView(lock(order.orderNo()));
        });
    }

    /** Before dispatch: cancelled. After dispatch StoreKit may still complete, so only the request is recorded. */
    private void cancelLocked(Order order) {
        if (order.paid() || order.cancelled()) return;
        String state = order.clientState();
        if ("CREATED".equals(state) || "NATIVE_READY".equals(state)) {
            mapper.cancelCommerceOrder(order.orderNo());
            mapper.cancelWorkshopOrder(order.orderNo());
        } else {
            mapper.requestCancellation(order.orderNo());
        }
    }

    // ------------------------------------------------------------------ licenses

    public List<Map<String, Object>> licenses(long buyer, String before) {
        actor(buyer);
        long cursor = Long.MAX_VALUE;
        if (before != null) {
            key(before);
            Long id = mapper.orderRowId(before, buyer);
            if (id == null) throw failure("LICENSE_CURSOR_INVALID", 400);
            cursor = id;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : mapper.licenses(buyer, cursor, PAGE)) {
            Map<String, Object> commerce = mapper.commerceOrder(str(row, "order_no"));
            out.add(licenseView(new Order(row, commerce)));
        }
        return out;
    }

    public Map<String, Object> license(long buyer, String licenseId) {
        actor(buyer);
        Order order = tx.execute(s -> lockOwned(buyer, licenseId));
        if (!order.paid()) throw failure("LICENSE_NOT_FOUND", 404);
        return licenseView(order);
    }

    /** Exactly {@code WorkshopBuyerReversalStatus} ({@code mod-buyer-reversal-status-v1}). */
    public Map<String, Object> reversalStatus(long buyer, String orderKey) {
        actor(buyer);
        Order order = tx.execute(s -> lockOwned(buyer, orderKey));
        if (!order.paid()) throw failure("LICENSE_NOT_FOUND", 404);
        Map<String, Object> offer = object(str(order.commerce, "offer_json"));
        @SuppressWarnings("unchecked") Map<String, Object> terms = (Map<String, Object>) offer.get("terms");
        long total = order.amountMinor();
        boolean refunded = "REFUNDED".equals(order.workshop.get("state"));
        List<Map<String, Object>> reversals = new ArrayList<>();
        if (refunded) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("reversalKey", "APPLE_REFUND_" + order.orderNo());
            item.put("requestId", Objects.toString(order.workshop.get("apple_original_transaction_id"), order.orderNo()));
            item.put("kind", "REFUND");
            item.put("state", "SUCCEEDED");
            item.put("buyerMinor", total);
            reversals.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", "mod-buyer-reversal-status-v1");
        out.put("orderKey", order.orderNo());
        out.put("offerId", offer.get("offerId"));
        out.put("moduleId", offer.get("moduleId"));
        out.put("releaseId", offer.get("releaseId"));
        out.put("contentHash", terms.get("contentHash"));
        out.put("termsHash", terms.get("hash"));
        out.put("currency", order.workshop.get("currency"));
        out.put("orderState", state(order));
        out.put("licenseId", order.orderNo());
        out.put("licenseState", order.workshop.get("license_status"));
        out.put("totalMinor", total);
        out.put("refundedBuyerMinor", refunded ? total : 0L);
        out.put("chargedBackBuyerMinor", 0L);
        out.put("revision", (long) reversals.size());
        out.put("cancellationRequested", order.cancellationRequested());
        out.put("reversals", reversals);
        return out;
    }

    // ------------------------------------------------------------------ App Store Server Notifications V2

    /**
     * Called by the app's one notification endpoint after the pass handler declined. Empty when the notification is not
     * for a workshop product (or cannot be verified here), so the next handler can try. Throws to request Apple's retry.
     */
    public Optional<Map<String, Object>> notificationIfTemplate(String signedPayload) {
        if (verifier.unavailableReason() != null) return Optional.empty();
        ResponseBodyV2DecodedPayload body;
        JWSTransactionDecodedPayload decoded;
        try {
            body = verifier.notification(signedPayload);
            if (body.getData() == null || body.getData().getSignedTransactionInfo() == null) return Optional.empty();
            decoded = verifier.transaction(body.getData().getSignedTransactionInfo());
        } catch (PlatformPassException e) {
            return Optional.empty();
        }
        if (decoded.getProductId() == null || mapper.offerByProduct(decoded.getProductId()) == null) return Optional.empty();
        return Optional.of(handleNotification(body, decoded, sha256(signedPayload)));
    }

    Map<String, Object> handleNotification(ResponseBodyV2DecodedPayload body, JWSTransactionDecodedPayload decoded, String hash) {
        String uuid = uuid(body.getNotificationUUID());
        String type = body.getRawNotificationType();
        boolean done = Boolean.TRUE.equals(inboxTx.execute(s -> {
            mapper.insertNotification(uuid, type, body.getRawSubtype(), decoded.getTransactionId(), hash);
            Map<String, Object> inbox = mapper.lockNotification(uuid);
            if (inbox == null || !hash.equals(inbox.get("payload_hash"))) throw failure("APPLE_NOTIFICATION_UUID_CONFLICT", 409);
            // A charge stored IGNORED by the old switch rule is processed again when Apple redelivers it.
            return "APPLIED".equals(inbox.get("state"))
                    || ("IGNORED".equals(inbox.get("state")) && !"ONE_TIME_CHARGE".equals(type));
        }));
        if (done) return Map.of("state", "APPLIED", "notificationUUID", uuid);
        try {
            String state;
            if ("REFUND".equals(type) || "REVOKE".equals(type)) {
                Fact fact = fact(decoded);
                if (fact.revocationDate() == null) throw failure("APPLE_REVOCATION_FACT_MISSING", 503);
                applyNotified(fact, "REFUND".equals(type) ? "APPLE_REFUND" : "APPLE_REVOKE");
                state = "APPLIED";
            } else if ("ONE_TIME_CHARGE".equals(type) && deliverable() && locate(fact(decoded)) != null) {
                // Delivery ignores the money switch: a charge that matches a server order is granted (idempotent).
                applyNotified(fact(decoded), "APPLE_REVOCATION");
                state = "APPLIED";
            } else {
                // No matching order, or a type with nothing to do.
                state = "IGNORED";
            }
            String finalState = state;
            inboxTx.execute(s -> mapper.notificationState(uuid, finalState, null));
            return Map.of("state", state, "notificationUUID", uuid);
        } catch (RuntimeException e) {
            String code = e instanceof ServiceException se ? se.getMessage() : "APPLE_NOTIFICATION_PROCESSING_FAILED";
            log.warn("mod-commerce notification {} not applied: {}", uuid, code);
            inboxTx.execute(s -> mapper.notificationState(uuid, "PENDING", code));
            // Non-2xx asks Apple to deliver again; an unapplied refund is never acknowledged.
            throw failure(code, 503);
        }
    }

    private void applyNotified(Fact fact, String reason) {
        tx.execute(s -> {
            String orderNo = locate(fact);
            if (orderNo == null) throw failure("APPLE_ORDER_NOT_MATCHED", 503);
            apply(lock(orderNo), fact, reason);
            return null;
        });
    }

    // ------------------------------------------------------------------ verification and grant

    /** Facts from an Apple-verified transaction (signature chain, bundle id and environment checked by the verifier). */
    record Fact(String transactionId, String originalTransactionId, String bundleId, String environment, String productId,
                UUID appAccountToken, long priceMilliunits, String currency, long purchaseDate, Long revocationDate) { }

    private Fact verify(String signedTransaction) {
        if (verifier.unavailableReason() != null) throw failure("APPLE_IAP_UNAVAILABLE", 503);
        try {
            return fact(verifier.transaction(signedTransaction));
        } catch (PlatformPassException e) {
            throw failure(e.getReasonCode(), e.getHttpStatus() >= 400 ? e.getHttpStatus() : 422);
        }
    }

    Fact fact(JWSTransactionDecodedPayload d) {
        if (d == null || d.getType() != Type.NON_CONSUMABLE || d.getInAppOwnershipType() != InAppOwnershipType.PURCHASED
                || (d.getQuantity() != null && d.getQuantity() != 1)) {
            throw failure("APPLE_PRODUCT_TYPE_UNSUPPORTED", 422);
        }
        // The verifier already enforced these; checked again so a configuration slip cannot widen them.
        if (!Objects.equals(d.getBundleId(), verifierBundle())) throw failure("APPLE_BUNDLE_CONFLICT", 409);
        if (d.getEnvironment() == null || !Objects.equals(d.getEnvironment().getValue(), verifier.configuredEnvironment())) {
            throw failure("APPLE_ENVIRONMENT_CONFLICT", 409);
        }
        if (d.getTransactionId() == null || !d.getTransactionId().matches("[0-9]{1,64}")
                || d.getOriginalTransactionId() == null || !d.getOriginalTransactionId().matches("[0-9]{1,64}")) {
            throw failure("APPLE_TRANSACTION_ID_INVALID", 422);
        }
        if (d.getAppAccountToken() == null) throw failure("APPLE_ACCOUNT_TOKEN_MISSING", 422);
        if (d.getProductId() == null || d.getPurchaseDate() == null || d.getPurchaseDate() <= 0
                || d.getPrice() == null || d.getPrice() <= 0 || d.getCurrency() == null) {
            throw failure("APPLE_TRANSACTION_FACTS_INCOMPLETE", 422);
        }
        return new Fact(d.getTransactionId(), d.getOriginalTransactionId(), d.getBundleId(), d.getEnvironment().getValue(),
                d.getProductId(), d.getAppAccountToken(), d.getPrice(), d.getCurrency(), d.getPurchaseDate(),
                d.getRevocationDate());
    }

    private String locate(Fact fact) {
        String byToken = mapper.orderByToken(fact.appAccountToken().toString());
        return byToken != null ? byToken : mapper.orderByOriginal(fact.originalTransactionId());
    }

    /** Match the order, then grant once or revoke. Caller holds the order lock. */
    private void apply(Order order, Fact fact, String revokeReason) {
        Map<String, Object> w = order.workshop;
        if (!Objects.equals(w.get("apple_bundle_id"), fact.bundleId())) throw failure("APPLE_BUNDLE_CONFLICT", 409);
        if (!Objects.equals(w.get("apple_environment"), fact.environment())) throw failure("APPLE_ENVIRONMENT_CONFLICT", 409);
        if (!Objects.equals(w.get("apple_product_id"), fact.productId())) throw failure("APPLE_PRODUCT_CONFLICT", 409);
        if (!Objects.equals(w.get("app_account_token"), fact.appAccountToken().toString())) {
            // A restore may arrive by original id only; the token must still be this order's.
            throw failure("APPLE_ACCOUNT_TOKEN_CONFLICT", 409);
        }
        Object boundOriginal = w.get("apple_original_transaction_id");
        if (boundOriginal != null && !boundOriginal.equals(fact.originalTransactionId())) {
            throw failure("APPLE_ORIGINAL_ORDER_CONFLICT", 409);
        }
        String elsewhere = mapper.orderByOriginal(fact.originalTransactionId());
        if (elsewhere != null && !elsewhere.equals(order.orderNo())) throw failure("APPLE_ORIGINAL_ORDER_CONFLICT", 409);

        if (fact.revocationDate() != null) {
            revoke(order, fact, revokeReason);
            return;
        }
        if (order.paid()) return; // idempotent: same original transaction already granted (or since refunded)
        if (num(w, "amount_milliunits") != fact.priceMilliunits()) throw failure("APPLE_ORDER_AMOUNT_CONFLICT", 409);
        if (!Objects.equals(w.get("currency"), fact.currency())) throw failure("APPLE_ORDER_CURRENCY_CONFLICT", 409);
        Map<String, Object> row = mutation(order, fact);
        if (mapper.grant(row) != 1) throw failure("APPLE_GRANT_CONFLICT", 409);
        recordSale(order, new Date(fact.purchaseDate()));
    }

    private void revoke(Order order, Fact fact, String reason) {
        if ("REFUNDED".equals(order.workshop.get("state"))) return;
        boolean wasPaid = order.paid();
        Map<String, Object> row = mutation(order, fact);
        row.put("revokedAt", new Date(fact.revocationDate()));
        row.put("reason", reason);
        if (mapper.revoke(row) != 1) throw failure("APPLE_REVOCATION_CONFLICT", 409);
        // A refund that arrives before the grant still records the sale first, so the reversal has its pair.
        if (!wasPaid) recordSale(order, new Date(fact.purchaseDate()));
        recordReversal(order, new Date(fact.revocationDate()));
    }

    private Map<String, Object> mutation(Order order, Fact fact) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("orderNo", order.orderNo());
        row.put("transactionId", fact.transactionId());
        row.put("originalId", fact.originalTransactionId());
        row.put("paidAt", new Date(fact.purchaseDate()));
        row.put("factHash", sha256(encode(List.of(fact.transactionId(), fact.originalTransactionId(), fact.bundleId(),
                fact.environment(), fact.productId(), fact.appAccountToken().toString(), fact.priceMilliunits(),
                fact.currency(), fact.purchaseDate()))));
        return row;
    }

    /** Creator SALE row, same transaction as the grant. The ledger's unique event key makes a replay a no-op. */
    private void recordSale(Order order, Date paidAt) {
        CreatorEarningsService ledger = earnings.getIfAvailable();
        if (ledger == null) return; // the hourly accrual job still picks the paid workshop_order up
        Map<String, Object> offer = object(str(order.commerce, "offer_json"));
        Map<String, Object> sale = new LinkedHashMap<>();
        sale.put("orderNo", order.orderNo());
        sale.put("actorId", num(order.workshop, "buyer_id"));
        sale.put("creatorId", ((Number) offer.get("creatorId")).longValue());
        sale.put("itemRef", order.workshop.get("version_id"));
        sale.put("itemTitle", offer.get("title"));
        sale.put("occurTime", paidAt);
        sale.put("grossMinor", order.amountMinor());
        sale.put("currency", order.workshop.get("currency"));
        ledger.sale(sale);
    }

    private void recordReversal(Order order, Date revokedAt) {
        CreatorEarningsService ledger = earnings.getIfAvailable();
        if (ledger == null) return;
        Map<String, Object> reversal = new LinkedHashMap<>();
        reversal.put("orderNo", order.orderNo());
        reversal.put("actorId", num(order.workshop, "buyer_id"));
        reversal.put("occurTime", revokedAt);
        ledger.reversal(reversal);
    }

    // ------------------------------------------------------------------ views

    /** Exactly {@code WorkshopCommerceOrder}. */
    Map<String, Object> orderView(Order order) {
        String state = state(order);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderKey", order.orderNo());
        out.put("requestId", order.commerce.get("request_id"));
        out.put("offerId", order.commerce.get("offer_id"));
        out.put("state", state);
        out.put("licenseId", order.paid() ? order.orderNo() : null);
        out.put("amountMinor", order.amountMinor());
        out.put("currency", order.workshop.get("currency"));
        out.put("cancellationRequested", order.cancellationRequested());
        out.put("paymentParameters", "NATIVE_READY".equals(state) || "SUBMITTING".equals(state) ? parameters(order) : Map.of());
        return out;
    }

    static String state(Order order) {
        if (order.paid()) return "PAID";
        if (order.cancelled()) return "CANCELLED";
        return order.clientState();
    }

    /** Exactly what {@code WorkshopStoreKitParameters} reads. All server-issued. */
    Map<String, String> parameters(Order order) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("channel", CHANNEL);
        out.put("environment", str(order.workshop, "apple_environment").toUpperCase(Locale.ROOT));
        out.put("orderKey", order.orderNo());
        out.put("productId", str(order.workshop, "apple_product_id"));
        out.put("bundleId", str(order.workshop, "apple_bundle_id"));
        out.put("appAccountToken", str(order.workshop, "app_account_token"));
        Map<String, Object> offer = object(str(order.commerce, "offer_json"));
        out.put("storefront", storefrontFor(Objects.toString(offer.get("market"), "CN")));
        return out;
    }

    /** Exactly {@code WorkshopCommerceLicense}. */
    Map<String, Object> licenseView(Order order) {
        Map<String, Object> offer = object(str(order.commerce, "offer_json"));
        String status = str(order.workshop, "license_status");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("licenseId", order.orderNo());
        out.put("orderKey", order.orderNo());
        out.put("moduleId", offer.get("moduleId"));
        out.put("purchasedVersionId", order.workshop.get("version_id"));
        out.put("acquisition", "PAID");
        out.put("status", status);
        out.put("acquiredAt", instant(order.workshop.get("paid_at")));
        out.put("terms", offer.get("terms"));
        out.put("usedInstallations", mapper.installations(order.orderNo()));
        out.put("suspensionReason", "ACTIVE".equals(status) ? null : order.workshop.get("revoke_reason"));
        out.put("published", false);
        out.put("executable", false);
        return out;
    }

    // ------------------------------------------------------------------ admin

    public List<Map<String, Object>> adminOffers() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : mapper.adminOffers()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("offerId", row.get("offer_id"));
            item.put("versionId", row.get("version_id"));
            item.put("title", tree(str(row, "content_json")).path("title").asText(""));
            item.put("creatorId", row.get("seller_id"));
            item.put("reviewState", row.get("review_state"));
            item.put("market", row.get("market"));
            item.put("currency", row.get("currency"));
            item.put("priceMinor", row.get("price_minor"));
            item.put("productId", row.get("product_id"));
            item.put("enabled", num(row, "enabled") == 1);
            item.put("updateBy", row.get("update_by"));
            item.put("updateTime", row.get("update_time"));
            out.add(item);
        }
        return out;
    }

    public record OfferCommand(String versionId, String market, String productId, Long priceMinor) { }

    /** New offers start off sale. Offers are immutable apart from on/off: a new price is a new App Store product. */
    public Map<String, Object> createOffer(OfferCommand command, String operator) {
        if (command == null || command.versionId() == null || command.productId() == null || command.priceMinor() == null) {
            throw failure("OFFER_FIELDS_REQUIRED", 400);
        }
        String market = command.market() == null ? "" : command.market().trim().toUpperCase(Locale.ROOT);
        if (!market.equals("CN") && !market.equals("US")) throw failure("OFFER_MARKET_INVALID", 400);
        String productId = command.productId().trim();
        if (!productId.matches(PRODUCT)) throw failure("OFFER_PRODUCT_ID_INVALID", 400);
        if (command.priceMinor() <= 0 || command.priceMinor() > 2_147_483_647L) throw failure("OFFER_PRICE_INVALID", 400);
        return tx.execute(s -> {
            Map<String, Object> version = mapper.version(command.versionId().trim());
            if (version == null) throw failure("RELEASE_NOT_FOUND", 404);
            if (!"APPROVED".equals(version.get("review_state"))) throw failure("RELEASE_NOT_APPROVED", 409);
            terms(version); // integrity check before anything is sold
            if (mapper.offerByProduct(productId) != null) throw failure("OFFER_PRODUCT_ID_TAKEN", 409);
            Map<String, Object> row = new LinkedHashMap<>();
            String offerId = "MCO_" + UUID.randomUUID().toString().replace("-", "");
            row.put("offerId", offerId);
            row.put("versionId", version.get("version_id"));
            row.put("market", market);
            row.put("currency", currencyFor(market));
            row.put("priceMinor", command.priceMinor());
            row.put("productId", productId);
            row.put("operator", operator == null ? "" : operator);
            mapper.insertOffer(row);
            return Map.<String, Object>of("offerId", offerId, "enabled", false);
        });
    }

    public Map<String, Object> setOfferEnabled(String offerId, boolean enabled, String operator) {
        key(offerId);
        if (mapper.setOfferEnabled(offerId, enabled ? 1 : 0, operator == null ? "" : operator) != 1) {
            throw failure("OFFER_NOT_FOUND", 404);
        }
        return Map.of("offerId", offerId, "enabled", enabled);
    }

    // ------------------------------------------------------------------ plumbing

    /** A locked workshop_order row joined to its app order state. */
    record Order(Map<String, Object> workshop, Map<String, Object> commerce) {
        String orderNo() { return (String) workshop.get("order_no"); }
        boolean paid() { return workshop.get("paid_at") != null; }
        boolean cancelled() { return "CANCELLED".equals(workshop.get("state")) || "CANCELLED".equals(commerce.get("client_state")); }
        boolean cancellationRequested() { return num(commerce, "cancellation_requested") != 0; }
        String clientState() { return (String) commerce.get("client_state"); }
        long amountMinor() { return num(workshop, "amount_milliunits") / 10; }
    }

    private Order lockOwned(long buyer, String orderKey) {
        key(orderKey);
        Order order = lock(orderKey);
        if (order == null || num(order.workshop, "buyer_id") != buyer) throw failure("ORDER_NOT_FOUND", 404);
        return order;
    }

    private Order lock(String orderNo) {
        Map<String, Object> workshop = mapper.lockWorkshopOrder(orderNo);
        if (workshop == null) throw failure("ORDER_NOT_FOUND", 404);
        Map<String, Object> commerce = mapper.commerceOrder(orderNo);
        if (commerce == null) throw failure("ORDER_NOT_FOUND", 404);
        return new Order(workshop, commerce);
    }

    /** The App Store identity is the pass agent's configuration; read here, never changed. */
    private String verifierBundle() {
        return appleProperties.getBundleId();
    }

    private static void validIntent(Intent intent) {
        if (intent == null) throw failure("COMMAND_REQUIRED", 400);
        key(intent.requestId());
        key(intent.offerId());
        if (intent.termsHash() == null || !intent.termsHash().matches("[a-f0-9]{64}")) throw failure("INVALID_HASH", 400);
    }

    private static void actor(long buyer) { if (buyer <= 0) throw failure("LOGIN_REQUIRED", 401); }

    private static void key(String v) { if (v == null || !v.matches(KEY)) throw failure("INVALID_KEY", 400); }

    private static String uuid(String v) {
        try {
            String canonical = UUID.fromString(v).toString();
            if (!canonical.equalsIgnoreCase(v)) throw new IllegalArgumentException();
            return canonical;
        } catch (Exception e) {
            throw failure("APPLE_NOTIFICATION_UUID_INVALID", 422);
        }
    }

    static long num(Map<String, Object> row, String field) {
        Object v = row.get(field);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof Boolean b) return b ? 1 : 0;
        if (v == null) return 0;
        return Long.parseLong(v.toString());
    }

    private static String str(Map<String, Object> row, String field) {
        Object v = row.get(field);
        if (v == null) throw failure("RECORD_INCOMPLETE", 409);
        return v.toString();
    }

    private static String instant(Object date) {
        if (date instanceof Date d) return d.toInstant().toString();
        if (date instanceof java.time.LocalDateTime t) return t.atZone(java.time.ZoneId.systemDefault()).toInstant().toString();
        return Objects.toString(date, Instant.EPOCH.toString());
    }

    static String sha256(String v) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode tree(String value) {
        try {
            return JSON.readTree(value);
        } catch (Exception e) {
            throw failure("RECORD_INCOMPLETE", 409);
        }
    }

    private static Map<String, Object> object(String value) {
        try {
            return JSON.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception e) {
            throw failure("RECORD_INCOMPLETE", 409);
        }
    }

    static ServiceException failure(String code, int status) { return new ServiceException(code, status); }
}
