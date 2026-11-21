package com.chengyinhub.business.modcommerce;

import com.chengyinhub.business.creatorearnings.CreatorEarningsService;
import com.chengyinhub.business.mapper.CreatorEarningMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.modcommerce.ModCommerceService.Intent;
import com.chengyinhub.business.modcommerce.ModCommerceService.OfferCommand;
import com.chengyinhub.business.modcommerce.mapper.ModCommerceMapper;
import com.chengyinhub.business.moneyswitch.MoneySwitchService;
import com.chengyinhub.business.platformpass.PlatformPassAppleProperties;
import com.chengyinhub.business.platformpass.PlatformPassAppleVerifier;
import com.chengyinhub.business.platformpass.SyntheticAppleChain;
import com.chengyinhub.business.service.IUmsMemberPointsDetailService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R6c workshop-iap end to end below the controller: the real {@link ModCommerceMapper} SQL on H2 (MySQL mode), the pass
 * agent's real {@link PlatformPassAppleVerifier} (Apple's SDK) trusting only a synthetic test root, and the real
 * {@link CreatorEarningsService} writing the ledger. No Apple server is contacted.
 */
class ModCommerceServiceTest {
    static final String BUNDLE = "org.chengyin.app";
    static final String PRODUCT = "org.chengyin.app.template.oldtown";
    static final long CREATOR = 200L, BUYER = 300L, OTHER = 400L;
    static final String VERSION = "WKV_" + "a".repeat(32);
    static final String CONTENT = "{\"title\":\"Old Town Hunt\"}";
    static final String TERMS = "{\"version\":\"v1\",\"text\":\"Personal use\",\"commercialUse\":false,"
            + "\"allowedRegions\":[\"310000\"],\"installLimit\":3}";

    private SqlSession session;
    private JdbcTemplate jdbc;
    private SyntheticAppleChain chain;
    private PlatformPassAppleProperties apple;
    private ModCommerceProperties props;
    private MoneySwitchService moneySwitch;
    private ModCommerceService service;
    private String offerId;

    static String sha(String v) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
    }

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:mod_commerce_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=FALSE");
        ds.setUsername("sa");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table workshop_version (id bigint auto_increment unique, version_id varchar(40) primary key, seller_id bigint,"
                + " request_id varchar(80), module_id bigint, source_canonical_id bigint, content_hash char(64), content_json varchar(4000),"
                + " terms_hash char(64), terms_json varchar(4000), price_fen bigint, review_state varchar(24))");
        jdbc.execute("create table workshop_order (id bigint auto_increment primary key, order_no varchar(40) unique, buyer_id bigint,"
                + " request_id varchar(80), request_hash char(64), version_id varchar(40), amount_fen bigint, provider varchar(20),"
                + " state varchar(20), license_status varchar(20), version_review_hash char(64), currency char(3),"
                + " amount_milliunits bigint, app_account_token char(36) unique, apple_bundle_id varchar(255),"
                + " apple_environment varchar(20), apple_product_id varchar(255), apple_transaction_id varchar(128) unique,"
                + " apple_original_transaction_id varchar(128) unique, apple_fact_hash char(64), apple_revocation_hash char(64),"
                + " paid_at timestamp(3), refund_no varchar(44), refunded_at timestamp(3), revoked_at timestamp(3), revoke_reason varchar(40),"
                + " created_at timestamp(3) default current_timestamp(3), unique(buyer_id, request_id))");
        jdbc.execute("create table workshop_install (id bigint auto_increment primary key, buyer_id bigint, order_no varchar(40),"
                + " draft_id bigint, installed_at timestamp(3))");
        jdbc.execute("create table mod_commerce_offer (id bigint auto_increment primary key, offer_id varchar(40) unique,"
                + " version_id varchar(40) not null, market char(2) not null, currency char(3) not null, price_minor bigint not null,"
                + " product_id varchar(255) not null unique, enabled tinyint not null default 0, create_by varchar(64),"
                + " create_time timestamp(3), update_by varchar(64), update_time timestamp(3))");
        jdbc.execute("create table mod_commerce_order (order_no varchar(40) primary key, member_id bigint not null,"
                + " request_id varchar(80) not null, offer_id varchar(40) not null, offer_json varchar(8000) not null,"
                + " client_state varchar(20) not null default 'CREATED', cancellation_requested tinyint not null default 0,"
                + " dispatched_at timestamp(3), create_time timestamp(3), update_time timestamp(3), unique(member_id, request_id),"
                + " foreign key (order_no) references workshop_order(order_no))");
        jdbc.execute("create table mod_commerce_apple_notification (notification_uuid char(36) primary key, notification_type varchar(40),"
                + " subtype varchar(40), transaction_id varchar(128), payload_hash char(64) not null, state varchar(16) not null,"
                + " reason_code varchar(64), create_time timestamp(3), update_time timestamp(3))");
        jdbc.execute("create table creator_earning_ledger (id bigint auto_increment primary key, event_key varchar(191) not null unique,"
                + " event_type varchar(16) not null, creator_member_id bigint not null, actor_member_id bigint, item_kind varchar(16) not null,"
                + " item_ref varchar(64) not null, item_title varchar(255), source_ref varchar(64) not null, points int not null default 0,"
                + " capped tinyint not null default 0, gross_minor bigint not null default 0, creator_share_bps int not null default 0,"
                + " creator_minor bigint not null default 0, currency varchar(8), money_state varchar(20) not null default 'NONE',"
                + " income_order_key char(32), occur_time timestamp(3) not null, occur_date date not null, create_time timestamp(3))");
        jdbc.update("insert into workshop_version(version_id, seller_id, request_id, module_id, source_canonical_id, content_hash,"
                + " content_json, terms_hash, terms_json, price_fen, review_state) values(?,?,?,?,?,?,?,?,?,?,?)",
                VERSION, CREATOR, "seed", 77L, 10L, sha(CONTENT), CONTENT, sha(TERMS), TERMS, 600L, "APPROVED");

        Configuration configuration = new Configuration(new Environment("mc", new JdbcTransactionFactory(), ds));
        configuration.addMapper(ModCommerceMapper.class);
        configuration.addMapper(CreatorEarningMapper.class);
        String resource = "mapper/business/CreatorEarningMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);

        UmsMemberMapper members = mock(UmsMemberMapper.class);
        when(members.selectPointsForUpdate(anyLong())).thenReturn(0L);
        IUmsMemberService memberService = mock(IUmsMemberService.class);
        when(memberService.addPoints(anyLong(), anyInt())).thenReturn(true);
        IUmsMemberPointsDetailService points = mock(IUmsMemberPointsDetailService.class);
        when(points.insertUmsMemberPointsDetail(any())).thenReturn(1);
        CreatorEarningsService earnings = new CreatorEarningsService(session.getMapper(CreatorEarningMapper.class), members,
                memberService, points, mock(PlatformTransactionManager.class), 5, 4, 1, 2, 1, 7000, false);
        @SuppressWarnings("unchecked") ObjectProvider<CreatorEarningsService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(earnings);

        chain = new SyntheticAppleChain();
        apple = new PlatformPassAppleProperties();
        apple.setEnabled(true);
        apple.setBundleId(BUNDLE);
        apple.setEnvironment("Sandbox");
        apple.setOnlineChecks(false);
        PlatformPassAppleVerifier verifier = SyntheticAppleChain.verifier(apple, chain.rootDer());

        props = new ModCommerceProperties();
        props.setEnabled(true);
        moneySwitch = mock(MoneySwitchService.class);
        when(moneySwitch.snapshot()).thenReturn(Map.of("pass", true, "tickets", false));
        service = new ModCommerceService(session.getMapper(ModCommerceMapper.class), props, moneySwitch, verifier, apple,
                provider, mock(PlatformTransactionManager.class));

        offerId = (String) service.createOffer(new OfferCommand(VERSION, "CN", PRODUCT, 600L), "operator").get("offerId");
        service.setOfferEnabled(offerId, true, "operator");
    }

    @AfterEach
    void tearDown() { if (session != null) session.close(); }

    // ------------------------------------------------------------------ helpers

    private Intent intent(String requestId) {
        try {
            return new Intent(requestId, offerId, sha(TERMS));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> params(Map<String, Object> order) {
        return (Map<String, String>) order.get("paymentParameters");
    }

    /** Order through payment and dispatch: what the app has before it calls StoreKit. */
    private Map<String, Object> readyOrder(long buyer, String requestId) throws Exception {
        Map<String, Object> created = service.create(buyer, intent(requestId));
        String key = (String) created.get("orderKey");
        Map<String, Object> ready = service.payment(buyer, key);
        assertEquals(true, service.nativeDispatch(buyer, key).get("dispatchAllowed"));
        return ready;
    }

    private Map<String, Object> transaction(String txId, String original, String bundle, String environment, String product,
                                            String token, long price, Long revocationDate) {
        long now = System.currentTimeMillis();
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("transactionId", txId);
        t.put("originalTransactionId", original);
        t.put("bundleId", bundle);
        t.put("productId", product);
        t.put("purchaseDate", now - 60_000);
        t.put("originalPurchaseDate", now - 60_000);
        t.put("quantity", 1);
        t.put("type", "Non-Consumable");
        t.put("inAppOwnershipType", "PURCHASED");
        t.put("signedDate", now);
        t.put("environment", environment);
        t.put("appAccountToken", token);
        t.put("storefront", "CHN");
        t.put("price", price);
        t.put("currency", "CNY");
        if (revocationDate != null) { t.put("revocationDate", revocationDate); t.put("revocationReason", 0); }
        return t;
    }

    private String signedFor(Map<String, Object> order, String txId) {
        Map<String, String> p = params(order);
        return chain.sign(transaction(txId, txId, BUNDLE, "Sandbox", p.get("productId"), p.get("appAccountToken"), 6000, null));
    }

    private String notification(String type, Map<String, Object> transaction) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bundleId", BUNDLE);
        data.put("environment", "Sandbox");
        data.put("signedTransactionInfo", chain.sign(transaction));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("notificationType", type);
        body.put("notificationUUID", UUID.randomUUID().toString());
        body.put("version", "2.0");
        body.put("signedDate", System.currentTimeMillis());
        body.put("data", data);
        return chain.sign(body);
    }

    private static String json(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String code(Runnable call) {
        return assertThrows(ServiceException.class, call::run).getMessage();
    }

    private int ledger(String type) {
        return jdbc.queryForObject("select count(*) from creator_earning_ledger where event_type = ?", Integer.class, type);
    }

    // ------------------------------------------------------------------ switch

    @Test
    void openOnlyWhenEnabledAndPassAreaOpen() {
        assertTrue(service.open());
        when(moneySwitch.snapshot()).thenReturn(Map.of("pass", false, "tickets", true));
        assertFalse(service.open(), "the tickets area does not open App Store template sales");
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(moneySwitch).snapshot();
        assertFalse(service.open(), "a read failure is closed");
        org.mockito.Mockito.doReturn(Map.of("pass", true)).when(moneySwitch).snapshot();
        props.setEnabled(false);
        assertFalse(service.open(), "the server flag defaults off");
    }

    // ------------------------------------------------------------------ offers

    @Test
    void offersListOnlyEnabledApprovedOffersInTheExactAppShape() throws Exception {
        List<Map<String, Object>> offers = service.offers(null);
        assertEquals(1, offers.size());
        Map<String, Object> offer = offers.get(0);
        assertEquals(List.of("offerId", "moduleId", "releaseId", "creatorId", "title", "currency", "priceMinor", "platform",
                "market", "productType", "channel", "terms"), List.copyOf(offer.keySet()));
        assertEquals("77", offer.get("moduleId"));
        assertEquals(VERSION, offer.get("releaseId"));
        assertEquals(CREATOR, offer.get("creatorId"));
        assertEquals("Old Town Hunt", offer.get("title"));
        assertEquals("CNY", offer.get("currency"));
        assertEquals(600L, offer.get("priceMinor"));
        assertEquals("IOS", offer.get("platform"));
        assertEquals("CN", offer.get("market"));
        assertEquals("DIGITAL_MOD", offer.get("productType"));
        assertEquals("APPLE_STOREKIT", offer.get("channel"));
        @SuppressWarnings("unchecked") Map<String, Object> terms = (Map<String, Object>) offer.get("terms");
        assertEquals(sha(TERMS), terms.get("hash"));
        assertEquals(sha(CONTENT), terms.get("contentHash"));
        assertEquals(List.of("310000"), terms.get("allowedRegions"));
        assertEquals(3L, terms.get("installationLimit"));
        assertEquals(offer, service.offer(offerId));

        service.setOfferEnabled(offerId, false, "operator");
        assertTrue(service.offers(null).isEmpty());
        assertEquals("OFFER_NOT_FOUND", code(() -> service.offer(offerId)));
        service.setOfferEnabled(offerId, true, "operator");
        jdbc.update("update workshop_version set review_state = 'WITHDRAWN'");
        assertTrue(service.offers(null).isEmpty(), "a withdrawn release is not sold");
    }

    @Test
    void adminRefusesUnapprovedReleasesBadPricesAndDuplicateProducts() {
        assertEquals("OFFER_PRODUCT_ID_TAKEN", code(() -> service.createOffer(new OfferCommand(VERSION, "CN", PRODUCT, 600L), "op")));
        assertEquals("OFFER_PRICE_INVALID", code(() -> service.createOffer(new OfferCommand(VERSION, "CN", "x.y", 0L), "op")));
        assertEquals("OFFER_MARKET_INVALID", code(() -> service.createOffer(new OfferCommand(VERSION, "JP", "x.y", 100L), "op")));
        jdbc.update("update workshop_version set review_state = 'PENDING'");
        assertEquals("RELEASE_NOT_APPROVED", code(() -> service.createOffer(new OfferCommand(VERSION, "CN", "x.y", 100L), "op")));
        Map<String, Object> listed = service.adminOffers().get(0);
        assertEquals(PRODUCT, listed.get("productId"));
        assertEquals(true, listed.get("enabled"));
    }

    // ------------------------------------------------------------------ orders

    @Test
    void orderCreationIsServerOwnedIdempotentAndOncePerRelease() throws Exception {
        Map<String, Object> created = service.create(BUYER, intent("req-1"));
        assertEquals(List.of("orderKey", "requestId", "offerId", "state", "licenseId", "amountMinor", "currency",
                "cancellationRequested", "paymentParameters"), List.copyOf(created.keySet()));
        assertEquals("CREATED", created.get("state"));
        assertEquals(600L, created.get("amountMinor"));
        assertEquals("CNY", created.get("currency"));
        assertEquals(Map.of(), created.get("paymentParameters"));
        assertNull(created.get("licenseId"));
        assertEquals(created, service.create(BUYER, intent("req-1")), "same request, same order");
        assertEquals(created.get("orderKey"), service.create(BUYER, intent("req-2")).get("orderKey"),
                "a non-consumable: a second request returns the earlier order for adoption");
        assertEquals(1, jdbc.queryForObject("select count(*) from workshop_order", Integer.class));
        assertEquals("CANNOT_PURCHASE_OWN_RELEASE", code(() -> service.create(CREATOR, intent("req-3"))));
        assertEquals("TERMS_CHANGED", code(() -> service.create(OTHER, new Intent("req-4", offerId, "0".repeat(64)))));
        assertEquals("ORDER_NOT_FOUND", code(() -> service.order(OTHER, (String) created.get("orderKey"))));

        Map<String, Object> lookup = service.lookup(BUYER, "req-1");
        assertEquals(true, lookup.get("found"));
        assertEquals(created, lookup.get("order"));
        assertEquals(false, service.lookup(BUYER, "req-unknown").get("found"));
        assertEquals(json(service.offer(offerId)), json(service.orderOffer(BUYER, (String) created.get("orderKey"))),
                "the frozen offer is byte-for-byte today's offer while the catalogue row is unchanged");
        Map<String, Object> row = jdbc.queryForMap("select * from workshop_order");
        assertEquals("APPLE_IAP", row.get("provider"));
        assertEquals(6000L, ((Number) row.get("amount_milliunits")).longValue());
        assertEquals(BUNDLE, row.get("apple_bundle_id"));
        assertEquals("Sandbox", row.get("apple_environment"));
    }

    @Test
    void paymentIssuesStoreKitParametersAndDispatchIsSingleUse() throws Exception {
        String key = (String) service.create(BUYER, intent("req-1")).get("orderKey");
        Map<String, Object> ready = service.payment(BUYER, key);
        assertEquals("NATIVE_READY", ready.get("state"));
        Map<String, String> p = params(ready);
        assertEquals("APPLE_STOREKIT", p.get("channel"));
        assertEquals("SANDBOX", p.get("environment"));
        assertEquals(key, p.get("orderKey"));
        assertEquals(PRODUCT, p.get("productId"));
        assertEquals(BUNDLE, p.get("bundleId"));
        assertEquals("CHN", p.get("storefront"));
        assertEquals(UUID.fromString(p.get("appAccountToken")).toString(), p.get("appAccountToken"));
        assertEquals(p, service.nativeContext(BUYER, key));
        assertEquals(true, service.nativeDispatch(BUYER, key).get("dispatchAllowed"));
        assertEquals(false, service.nativeDispatch(BUYER, key).get("dispatchAllowed"), "never a second StoreKit purchase");
        assertEquals("SUBMITTING", service.order(BUYER, key).get("state"));
    }

    @Test
    void verifiedTransactionGrantsOnceAndRecordsOneCreatorSale() throws Exception {
        Map<String, Object> ready = readyOrder(BUYER, "req-1");
        String key = (String) ready.get("orderKey");
        String signed = signedFor(ready, "2000000001");
        Map<String, Object> paid = service.nativeTransaction(BUYER, key, signed);
        assertEquals("PAID", paid.get("state"));
        assertEquals(key, paid.get("licenseId"));
        assertEquals(paid, service.nativeTransaction(BUYER, key, signed), "replay is idempotent");

        Map<String, Object> license = service.license(BUYER, key);
        assertEquals("ACTIVE", license.get("status"));
        assertEquals("PAID", license.get("acquisition"));
        assertEquals(VERSION, license.get("purchasedVersionId"));
        assertEquals(json(service.offer(offerId).get("terms")), json(license.get("terms")));
        assertEquals(false, license.get("published"));
        assertEquals(0L, license.get("usedInstallations"));
        assertEquals(List.of(license), service.licenses(BUYER, null));
        assertTrue(service.licenses(BUYER, key).isEmpty());

        assertEquals(1, ledger("SALE"));
        Map<String, Object> sale = jdbc.queryForMap("select * from creator_earning_ledger");
        assertEquals(CREATOR, ((Number) sale.get("creator_member_id")).longValue());
        assertEquals(BUYER, ((Number) sale.get("actor_member_id")).longValue());
        assertEquals(600L, ((Number) sale.get("gross_minor")).longValue());
        assertEquals(420L, ((Number) sale.get("creator_minor")).longValue());
        assertEquals("SALE:" + key, sale.get("event_key"));
        assertEquals("HELD_MONEY_OFF", sale.get("money_state"));

        // Another order cannot reuse the same original transaction.
        Map<String, Object> other = readyOrder(OTHER, "req-x");
        Map<String, String> p = params(other);
        String reused = chain.sign(transaction("2000000001", "2000000001", BUNDLE, "Sandbox", PRODUCT, p.get("appAccountToken"), 6000, null));
        assertEquals("APPLE_ORIGINAL_ORDER_CONFLICT", code(() -> service.nativeTransaction(OTHER, (String) other.get("orderKey"), reused)));
        assertEquals(1, ledger("SALE"));
    }

    @Test
    void verificationFailuresNeverGrant() throws Exception {
        Map<String, Object> ready = readyOrder(BUYER, "req-1");
        String key = (String) ready.get("orderKey");
        String token = params(ready).get("appAccountToken");

        String wrongBundle = chain.sign(transaction("1", "1", "com.other.app", "Sandbox", PRODUCT, token, 6000, null));
        assertEquals("APPLE_TRANSACTION_VERIFICATION_FAILED", code(() -> service.nativeTransaction(BUYER, key, wrongBundle)));
        String wrongEnvironment = chain.sign(transaction("2", "2", BUNDLE, "Production", PRODUCT, token, 6000, null));
        assertEquals("APPLE_TRANSACTION_VERIFICATION_FAILED", code(() -> service.nativeTransaction(BUYER, key, wrongEnvironment)));
        String wrongProduct = chain.sign(transaction("3", "3", BUNDLE, "Sandbox", "org.chengyin.app.other", token, 6000, null));
        assertEquals("APPLE_PRODUCT_CONFLICT", code(() -> service.nativeTransaction(BUYER, key, wrongProduct)));
        String wrongToken = chain.sign(transaction("4", "4", BUNDLE, "Sandbox", PRODUCT, UUID.randomUUID().toString(), 6000, null));
        assertEquals("APPLE_ACCOUNT_TOKEN_CONFLICT", code(() -> service.nativeTransaction(BUYER, key, wrongToken)));
        String wrongPrice = chain.sign(transaction("5", "5", BUNDLE, "Sandbox", PRODUCT, token, 1000, null));
        assertEquals("APPLE_ORDER_AMOUNT_CONFLICT", code(() -> service.nativeTransaction(BUYER, key, wrongPrice)));
        String untrusted = new SyntheticAppleChain().sign(transaction("6", "6", BUNDLE, "Sandbox", PRODUCT, token, 6000, null));
        assertEquals("APPLE_TRANSACTION_VERIFICATION_FAILED", code(() -> service.nativeTransaction(BUYER, key, untrusted)));
        assertEquals("APPLE_SIGNED_DATA_INVALID", code(() -> service.nativeTransaction(BUYER, key, "")));

        assertEquals("SUBMITTING", service.order(BUYER, key).get("state"));
        assertEquals(0, ledger("SALE"));
        assertNull(jdbc.queryForMap("select * from workshop_order").get("paid_at"));
    }

    @Test
    void restoreFindsTheOriginalOrderOnlyForItsBuyer() throws Exception {
        Map<String, Object> ready = readyOrder(BUYER, "req-1");
        String key = (String) ready.get("orderKey");
        String signed = signedFor(ready, "3000000001");
        assertEquals("APPLE_ORDER_OWNER_CONFLICT", code(() -> service.restore(OTHER, signed)));
        Map<String, Object> restored = service.restore(BUYER, signed);
        assertEquals("PAID", restored.get("state"), "a crash after purchase is recovered from the signed transaction");
        assertEquals(key, restored.get("orderKey"));
        assertEquals(restored, service.restore(BUYER, signed));
        assertEquals(1, ledger("SALE"));
        String unknown = chain.sign(transaction("9", "9", BUNDLE, "Sandbox", PRODUCT, UUID.randomUUID().toString(), 6000, null));
        assertEquals("ORDER_NOT_FOUND", code(() -> service.restore(BUYER, unknown)));
    }

    @Test
    void refundNotificationRevokesOnceAndReversesTheSaleEvenWithTheSwitchClosed() throws Exception {
        Map<String, Object> ready = readyOrder(BUYER, "req-1");
        String key = (String) ready.get("orderKey");
        service.nativeTransaction(BUYER, key, signedFor(ready, "4000000001"));
        when(moneySwitch.snapshot()).thenReturn(Map.of("pass", false));

        Map<String, String> p = params(ready);
        String refund = notification("REFUND", transaction("4000000001", "4000000001", BUNDLE, "Sandbox", PRODUCT,
                p.get("appAccountToken"), 6000, System.currentTimeMillis()));
        assertEquals("APPLIED", service.notificationIfTemplate(refund).orElseThrow().get("state"));
        assertEquals("APPLIED", service.notificationIfTemplate(refund).orElseThrow().get("state"), "replayed delivery");

        assertEquals("REVOKED", service.license(BUYER, key).get("status"));
        assertEquals("PAID", service.order(BUYER, key).get("state"), "the order stays paid; its license is revoked");
        assertEquals(1, ledger("SALE"));
        assertEquals(1, ledger("SALE_REVERSAL"));
        assertEquals(-420L, jdbc.queryForObject("select creator_minor from creator_earning_ledger where event_type = 'SALE_REVERSAL'", Long.class));

        when(moneySwitch.snapshot()).thenReturn(Map.of("pass", true));
        Map<String, Object> status = service.reversalStatus(BUYER, key);
        assertEquals("mod-buyer-reversal-status-v1", status.get("schema"));
        assertEquals(600L, status.get("refundedBuyerMinor"));
        assertEquals("REVOKED", status.get("licenseState"));
        assertEquals(1L, status.get("revision"));
        assertEquals(1, ((List<?>) status.get("reversals")).size());
    }

    @Test
    void refundBeforeGrantBlocksTheGrantAndKeepsTheLedgerBalanced() throws Exception {
        Map<String, Object> ready = readyOrder(BUYER, "req-1");
        String key = (String) ready.get("orderKey");
        Map<String, String> p = params(ready);
        String revoke = notification("REVOKE", transaction("5000000001", "5000000001", BUNDLE, "Sandbox", PRODUCT,
                p.get("appAccountToken"), 6000, System.currentTimeMillis()));
        assertTrue(service.notificationIfTemplate(revoke).isPresent());
        service.nativeTransaction(BUYER, key, signedFor(ready, "5000000001"));
        assertEquals("REVOKED", service.license(BUYER, key).get("status"));
        assertEquals(1, ledger("SALE"));
        assertEquals(1, ledger("SALE_REVERSAL"));
    }

    @Test
    void notificationsForOtherProductsFallThroughAndChargesAreDeliveredWithTheSwitchClosed() throws Exception {
        String pass = notification("REFUND", transaction("1", "1", BUNDLE, "Sandbox", "org.chengyin.app.pass.month",
                UUID.randomUUID().toString(), 6000, System.currentTimeMillis()));
        assertTrue(service.notificationIfTemplate(pass).isEmpty(), "not a workshop product: next handler");
        assertTrue(service.notificationIfTemplate("not-a-jws").isEmpty());

        Map<String, Object> ready = readyOrder(BUYER, "req-1");
        Map<String, String> p = params(ready);
        // MERCHANT-2: the area closed after the order was created; Apple's charge is still delivered.
        when(moneySwitch.snapshot()).thenReturn(Map.of("pass", false));
        String charge = notification("ONE_TIME_CHARGE", transaction("6000000001", "6000000001", BUNDLE, "Sandbox", PRODUCT,
                p.get("appAccountToken"), 6000, null));
        assertEquals("APPLIED", service.notificationIfTemplate(charge).orElseThrow().get("state"));
        assertEquals("PAID", service.order(BUYER, (String) ready.get("orderKey")).get("state"));
        assertEquals(1, ledger("SALE"));
        String chargeAgain = notification("ONE_TIME_CHARGE", transaction("6000000001", "6000000001", BUNDLE, "Sandbox", PRODUCT,
                p.get("appAccountToken"), 6000, null));
        assertEquals("APPLIED", service.notificationIfTemplate(chargeAgain).orElseThrow().get("state"));
        assertEquals(1, ledger("SALE"), "granted once");

        String stranger = notification("ONE_TIME_CHARGE", transaction("6000000009", "6000000009", BUNDLE, "Sandbox", PRODUCT,
                UUID.randomUUID().toString(), 6000, null));
        assertEquals("IGNORED", service.notificationIfTemplate(stranger).orElseThrow().get("state"),
                "a charge with no server order is not ours");

        String unmatched = notification("REFUND", transaction("7", "7", BUNDLE, "Sandbox", PRODUCT,
                UUID.randomUUID().toString(), 6000, System.currentTimeMillis()));
        assertEquals("APPLE_ORDER_NOT_MATCHED", code(() -> service.notificationIfTemplate(unmatched)),
                "an unmatched refund is never acknowledged; Apple retries");
    }

    @Test
    void storeKitSetupComesFromServerConfigurationAndKeepsWithdrawnProductsRestorable() {
        Map<String, Object> open = service.storeKit();
        assertEquals(true, open.get("available"));
        assertEquals(true, open.get("selling"));
        assertEquals(BUNDLE, open.get("bundleId"));
        assertEquals("SANDBOX", open.get("environment"));
        assertEquals("CN", open.get("market"));
        assertEquals(List.of(PRODUCT), open.get("productIds"));

        service.setOfferEnabled(offerId, false, "operator");
        when(moneySwitch.snapshot()).thenReturn(Map.of("pass", false));
        Map<String, Object> closed = service.storeKit();
        assertEquals(true, closed.get("available"), "delivery and restore still work");
        assertEquals(false, closed.get("selling"), "no new purchase");
        assertEquals(List.of(PRODUCT), closed.get("productIds"), "a withdrawn template stays restorable");
    }

    @Test
    void cancellationBeforeDispatchCancelsAndAnUnseenRequestIsTombstoned() throws Exception {
        String key = (String) service.create(BUYER, intent("req-1")).get("orderKey");
        assertEquals("CANCELLED", service.cancel(BUYER, key).get("state"));
        Map<String, Object> tomb = service.cancelRequest(BUYER, intent("req-never-sent"));
        assertEquals("CANCELLED", tomb.get("state"));
        assertEquals("req-never-sent", tomb.get("requestId"));
        assertEquals("CANCELLED", service.create(BUYER, intent("req-never-sent")).get("state"), "the request id cannot buy later");

        Map<String, Object> ready = readyOrder(OTHER, "req-2");
        Map<String, Object> afterDispatch = service.cancel(OTHER, (String) ready.get("orderKey"));
        assertEquals("SUBMITTING", afterDispatch.get("state"), "StoreKit may still complete a dispatched purchase");
        assertEquals(true, afterDispatch.get("cancellationRequested"));
    }
}
