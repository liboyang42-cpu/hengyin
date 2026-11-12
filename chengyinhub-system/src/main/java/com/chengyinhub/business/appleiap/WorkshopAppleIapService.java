package com.chengyinhub.business.appleiap;

import com.apple.itunes.storekit.model.InAppOwnershipType;
import com.apple.itunes.storekit.model.JWSTransactionDecodedPayload;
import com.apple.itunes.storekit.model.Type;
import com.chengyinhub.business.appleiap.WorkshopAppleOrderPort.Expectation;
import com.chengyinhub.business.appleiap.WorkshopAppleOrderPort.VerifiedTransaction;
import com.chengyinhub.business.appleiap.mapper.AppleIapMapper;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class WorkshopAppleIapService {
    private final AppleIapProperties config;
    private final AppleSignedData signed;
    private final AppleIapMapper mapper;
    private final ObjectProvider<WorkshopAppleOrderPort> orders;
    private final TransactionTemplate tx;
    private final TransactionTemplate notificationTx;
    private static final ObjectMapper JSON = new ObjectMapper();

    public WorkshopAppleIapService(AppleIapProperties config, AppleSignedData signed, AppleIapMapper mapper,
                                  ObjectProvider<WorkshopAppleOrderPort> orders, PlatformTransactionManager manager) {
        this.config = config; this.signed = signed; this.mapper = mapper; this.orders = orders;
        this.tx = new TransactionTemplate(manager);
        this.notificationTx = new TransactionTemplate(manager);
        this.notificationTx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Map<String, Object> confirm(Long buyer, String orderReference, String signedTransaction) {
        return purchase(buyer, orderReference, signedTransaction, false);
    }

    public Map<String, Object> restore(Long buyer, String orderReference, String signedTransaction) {
        return purchase(buyer, orderReference, signedTransaction, true);
    }

    public Map<String, Object> reconcile(Long buyer, String orderReference, String transactionId) {
        actor(buyer); key(orderReference);
        // Authorize the real order before spending API capacity or reading the controlled signing key.
        signed.requireEnabled();
        tx.execute(status -> {
            var expected = port().requireOrder(buyer, orderReference);
            equal(expected.buyerId(), buyer, "APPLE_ORDER_OWNER_CONFLICT");
            equal(expected.environment(), config.getEnvironment(), "APPLE_ENVIRONMENT_CONFLICT");
            equal(expected.bundleId(), config.getBundleId(), "APPLE_BUNDLE_CONFLICT"); return null;
        });
        String result = signed.query(transactionId);
        var decoded = signed.transaction(result);
        if (!Objects.equals(transactionId, decoded.getTransactionId())) throw failure("APPLE_QUERY_TRANSACTION_CONFLICT", 409);
        return purchase(buyer, orderReference, result, false);
    }

    private Map<String, Object> purchase(Long buyer, String reference, String raw, boolean restore) {
        actor(buyer); key(reference); signed.requireEnabled();
        VerifiedTransaction fact = fact(signed.transaction(raw));
        return tx.execute(status -> {
            Map<String, Object> original = remember(fact);
            var port = port(); var expected = port.requireOrder(buyer, reference);
            match(expected, fact);
            if (!Objects.equals(expected.buyerId(), buyer) || !Objects.equals(expected.orderReference(), reference))
                throw failure("APPLE_ORDER_OWNER_CONFLICT", 403);
            // Restore may only read the already bound original transaction; it never creates a new license.
            if (restore && !Objects.equals(expected.originalTransactionId(), fact.originalTransactionId()))
                throw failure("APPLE_RESTORE_ORIGINAL_REQUIRED", 409);
            bind(original, expected, fact);
            if (revoked(original) || fact.revocationDate() != null) {
                var revokedFact = revokedFact(original, fact);
                mapper.revokeOriginal(fact.originalTransactionId(), revokedFact.revocationDate()); port.revoke(expected, revokedFact);
                return Map.of("state", "REVOKED", "orderNo", reference);
            }
            if (restore) return Map.of("state", "RESTORED", "orderNo", reference, "license", port.restore(expected, fact));
            port.activate(expected, fact);
            return Map.of("state", "VERIFIED", "orderNo", reference);
        });
    }

    public Map<String, Object> notification(String raw) {
        signed.requireEnabled();
        var notification = signed.notification(raw);
        String uuid = uuid(notification.getNotificationUUID()); String hash = hash(raw);
        // A verified outer envelope is durable before inner verification or business matching can fail.
        boolean applied = Boolean.TRUE.equals(notificationTx.execute(status -> {
            mapper.insertNotification(uuid, hash, raw);
            var inbox = mapper.lockNotification(uuid);
            equal(inbox.get("payload_hash"), hash, "APPLE_NOTIFICATION_UUID_CONFLICT");
            return "APPLIED".equals(inbox.get("state"));
        }));
        if (applied) return Map.of("state", "APPLIED", "notificationUUID", uuid);
        try {
            String type = notification.getRawNotificationType();
            boolean refund = "REFUND".equals(type) || "REVOKE".equals(type);
            if (!refund && !"ONE_TIME_CHARGE".equals(type)) throw failure("APPLE_NOTIFICATION_TYPE_PENDING", 503);
            if (notification.getData() == null) throw failure("APPLE_NOTIFICATION_TRANSACTION_MISSING", 503);
            var decoded = signed.transaction(notification.getData().getSignedTransactionInfo());
            if (refund && decoded.getRevocationDate() == null) throw failure("APPLE_REVOCATION_FACT_MISSING", 503);
            if (decoded.getRevocationDate() != null) {
                id(decoded.getOriginalTransactionId());
                equal(decoded.getBundleId(), config.getBundleId(), "APPLE_BUNDLE_CONFLICT");
                equal(decoded.getEnvironment() == null ? null : decoded.getEnvironment().getValue(), config.getEnvironment(), "APPLE_ENVIRONMENT_CONFLICT");
                if (decoded.getRevocationDate() <= 0) throw failure("APPLE_REVOCATION_FACT_MISSING", 503);
                // Even incomplete legacy SKU/account metadata cannot erase a verified original-transaction revocation.
                notificationTx.execute(status -> {
                    mapper.lockNotification(uuid);
                    mapper.insertRevocation(decoded.getOriginalTransactionId(), decoded.getRevocationDate()); return null;
                });
                notificationTx.execute(status -> {
                    port().revokeBoundOriginal(decoded.getBundleId(), decoded.getEnvironment().getValue(),
                            decoded.getOriginalTransactionId(), decoded.getRevocationDate()); return null;
                });
            }
            VerifiedTransaction fact = fact(decoded);
            boolean matched = Boolean.TRUE.equals(notificationTx.execute(status -> {
                var inbox = mapper.lockNotification(uuid);
                equal(inbox.get("payload_hash"), hash, "APPLE_NOTIFICATION_UUID_CONFLICT");
                if ("APPLIED".equals(inbox.get("state"))) return true;
                var original = remember(fact);
                var port = port(); var found = port.findOrder(fact);
                if (found.isEmpty()) {
                    mapper.notificationState(uuid, "PENDING", "APPLE_ORDER_NOT_MATCHED"); return false;
                }
                var expected = found.get(); match(expected, fact); bind(original, expected, fact);
                if (refund || revoked(original) || fact.revocationDate() != null) port.revoke(expected, revokedFact(original, fact));
                else port.activate(expected, fact);
                mapper.notificationState(uuid, "APPLIED", null); return true;
            }));
            if (!matched) throw failure("APPLE_ORDER_NOT_MATCHED", 503);
            return Map.of("state", "APPLIED", "notificationUUID", uuid);
        } catch (RuntimeException e) {
            notificationTx.execute(status -> {
                var inbox = mapper.lockNotification(uuid);
                if (!"APPLIED".equals(inbox.get("state"))) mapper.notificationState(uuid, "PENDING",
                        e instanceof ServiceException ? e.getMessage() : "APPLE_NOTIFICATION_PROCESSING_FAILED");
                return null;
            });
            // A non-2xx response requests Apple's retry; never acknowledge an unapplied real refund.
            if (e instanceof ServiceException service) throw failure(service.getMessage(), 503);
            throw failure("APPLE_NOTIFICATION_PROCESSING_FAILED", 503);
        }
    }

    private VerifiedTransaction fact(JWSTransactionDecodedPayload data) {
        if (data == null || data.getEnvironment() == null || data.getType() != Type.NON_CONSUMABLE
                || data.getInAppOwnershipType() != InAppOwnershipType.PURCHASED || !Integer.valueOf(1).equals(data.getQuantity()))
            throw failure("APPLE_PRODUCT_TYPE_UNSUPPORTED", 422);
        equal(data.getBundleId(), config.getBundleId(), "APPLE_BUNDLE_CONFLICT");
        equal(data.getEnvironment().getValue(), config.getEnvironment(), "APPLE_ENVIRONMENT_CONFLICT");
        id(data.getTransactionId()); id(data.getOriginalTransactionId());
        if (data.getAppAccountToken() == null || data.getPurchaseDate() == null || data.getPurchaseDate() <= 0
                || data.getSignedDate() == null || data.getSignedDate() < data.getPurchaseDate()
                || (data.getRevocationDate() != null && data.getRevocationDate() < data.getPurchaseDate()))
            throw failure("APPLE_TRANSACTION_FACTS_INCOMPLETE", 422);
        if (data.getProductId() == null || data.getProductId().isBlank() || data.getPrice() == null || data.getPrice() <= 0
                || data.getCurrency() == null || !data.getCurrency().matches("[A-Z]{3}"))
            throw failure("APPLE_TRANSACTION_FACTS_INCOMPLETE", 422);
        return new VerifiedTransaction(data.getTransactionId(), data.getOriginalTransactionId(), data.getBundleId(),
                data.getEnvironment().getValue(), data.getProductId(), data.getAppAccountToken(), data.getType().getValue(),
                data.getPrice(), data.getCurrency(), data.getPurchaseDate(), data.getSignedDate(), data.getRevocationDate());
    }

    private Map<String, Object> remember(VerifiedTransaction f) {
        String identity = hash(encode(List.of(f.bundleId(), f.environment(), f.productId(), f.appAccountToken().toString())));
        mapper.insertOriginal(f.originalTransactionId(), identity);
        var original = mapper.lockOriginal(f.originalTransactionId());
        equal(original.get("identity_hash"), identity, "APPLE_ORIGINAL_IDENTITY_CONFLICT");
        // Signed/revocation timestamps can change when Apple re-signs the same immutable purchase fact.
        String hash = hash(encode(List.of(f.transactionId(), f.originalTransactionId(), identity,
                f.type(), f.priceMilliunits(), f.currency(), f.purchaseDate())));
        mapper.insertTransaction(f.transactionId(), f.originalTransactionId(), hash);
        var transaction = mapper.lockTransaction(f.transactionId());
        equal(transaction.get("original_id"), f.originalTransactionId(), "APPLE_TRANSACTION_ORIGINAL_CONFLICT");
        equal(transaction.get("fact_hash"), hash, "APPLE_TRANSACTION_FACT_CONFLICT");
        return original;
    }

    private void bind(Map<String, Object> original, Expectation e, VerifiedTransaction f) {
        if (original.get("order_reference") == null) mapper.bindOriginal(f.originalTransactionId(), e.orderReference());
        else equal(original.get("order_reference"), e.orderReference(), "APPLE_ORIGINAL_ORDER_CONFLICT");
    }

    private static void match(Expectation e, VerifiedTransaction f) {
        if (e == null || e.buyerId() == null || e.buyerId() <= 0 || e.orderReference() == null
                || e.appAccountToken() == null || e.priceMilliunits() == null || e.currency() == null)
            throw failure("APPLE_ORDER_EXPECTATION_INCOMPLETE", 409);
        equal(e.bundleId(), f.bundleId(), "APPLE_BUNDLE_CONFLICT");
        equal(e.environment(), f.environment(), "APPLE_ENVIRONMENT_CONFLICT");
        equal(e.productId(), f.productId(), "APPLE_PRODUCT_CONFLICT");
        equal(e.appAccountToken(), f.appAccountToken(), "APPLE_ACCOUNT_TOKEN_CONFLICT");
        equal(e.priceMilliunits(), f.priceMilliunits(), "APPLE_ORDER_AMOUNT_CONFLICT");
        equal(e.currency(), f.currency(), "APPLE_ORDER_CURRENCY_CONFLICT");
        if (e.originalTransactionId() != null) equal(e.originalTransactionId(), f.originalTransactionId(), "APPLE_ORIGINAL_ORDER_CONFLICT");
    }

    private WorkshopAppleOrderPort port() {
        var port = orders.getIfAvailable();
        if (port == null) throw failure("APPLE_ORDER_MAPPING_UNAVAILABLE", 503);
        return port;
    }
    private static VerifiedTransaction revokedFact(Map<String, Object> original, VerifiedTransaction f) {
        if (f.revocationDate() != null) return f;
        if (!(original.get("revoked_at") instanceof Number date) || date.longValue() <= 0)
            throw failure("APPLE_REVOCATION_FACT_MISSING", 503);
        return new VerifiedTransaction(f.transactionId(), f.originalTransactionId(), f.bundleId(), f.environment(),
                f.productId(), f.appAccountToken(), f.type(), f.priceMilliunits(), f.currency(),
                f.purchaseDate(), f.signedDate(), date.longValue());
    }

    private static boolean revoked(Map<String, Object> original) {
        Object v = original.get("revoked"); return Boolean.TRUE.equals(v) || v instanceof Number n && n.intValue() != 0;
    }
    private static void actor(Long buyer) { if (buyer == null || buyer <= 0) throw failure("LOGIN_REQUIRED", 401); }
    private static void key(String v) { if (v == null || !v.matches("[A-Za-z0-9_-]{1,128}")) throw failure("APPLE_ORDER_INVALID", 400); }
    private static void id(String v) { if (v == null || !v.matches("[0-9]{1,128}")) throw failure("APPLE_TRANSACTION_ID_INVALID", 422); }
    private static String uuid(String v) {
        try { String canonical = UUID.fromString(v).toString(); if (!canonical.equalsIgnoreCase(v)) throw new IllegalArgumentException(); return canonical; }
        catch (Exception e) { throw failure("APPLE_NOTIFICATION_UUID_INVALID", 422); }
    }
    private static void equal(Object expected, Object actual, String code) {
        if (expected == null || actual == null || !Objects.equals(expected, actual)) throw failure(code, 409);
    }
    private static String encode(Object value) {
        try { return JSON.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String hash(String v) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static ServiceException failure(String code, int status) { return AppleSignedData.error(code, status); }
}
