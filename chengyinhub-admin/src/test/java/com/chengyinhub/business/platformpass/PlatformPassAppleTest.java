package com.chengyinhub.business.platformpass;

import com.apple.itunes.storekit.model.JWSTransactionDecodedPayload;
import com.chengyinhub.business.appleiap.WorkshopAppleIapService;
import com.chengyinhub.business.moneyswitch.MoneySwitchService;
import com.chengyinhub.business.platformpass.mapper.PlatformPassAppleMapper;
import com.chengyinhub.business.platformpass.mapper.PlatformPassMapper;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.api.ApiAppleNotificationController;
import com.chengyinhub.web.controller.api.ApiPlatformPassAppleController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R6a(FLOW-10 / MON-05):通行证 App Store 购买。验签走 Apple 官方 SDK 与一条测试用的合成证书链
 * ({@link ApplePassTestChain}),绝不连 Apple;库表用内存桩,只测判定与调用。
 */
class PlatformPassAppleTest {
    static final long MEMBER = 42L;
    static final String BUNDLE = "org.chengyin.test";
    static final String PRODUCT = "org.chengyin.test.pass.month";
    static final String TOKEN = "7d1c3b4e-1f0a-4b7e-9a51-0d2b6c8e9f10";
    static final String ORDER = "PPO2026111500000000000000000001";

    ApplePassTestChain chain;
    PlatformPassAppleProperties config;
    PlatformPassProperties passProps;
    PlatformPassAppleVerifier verifier;
    PlatformPassService pass;
    PlatformPassMapper passMapper;
    PlatformPassAppleMapper mapper;
    MoneySwitchService moneySwitch;
    PlatformPassAppleService service;
    Map<String, Object> appleRow;
    Map<String, Object> orderRow;
    Map<String, Map<String, Object>> inbox;
    boolean moneyOpen = true;

    @BeforeEach
    void setUp() {
        chain = ApplePassTestChain.create();
        config = config();
        passProps = new PlatformPassProperties();
        passProps.setEnabled(true);
        verifier = new PlatformPassAppleVerifier(config, List.of(chain.rootDer()));
        pass = mock(PlatformPassService.class);
        passMapper = mock(PlatformPassMapper.class);
        mapper = mock(PlatformPassAppleMapper.class);
        moneySwitch = mock(MoneySwitchService.class);
        when(moneySwitch.snapshot()).thenAnswer(i -> Map.of("pass", moneyOpen));
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new PlatformPassAppleService(pass, passMapper, mapper, passProps, config, verifier, moneySwitch, tm);
        stubStore();
    }

    static PlatformPassAppleProperties config() {
        PlatformPassAppleProperties c = new PlatformPassAppleProperties();
        c.setEnabled(true);
        c.setBundleId(BUNDLE);
        c.setEnvironment("Sandbox");
        c.setOnlineChecks(false);
        c.setProductIds(new LinkedHashMap<>(Map.of("MONTH", PRODUCT)));
        return c;
    }

    /** In-memory stand-ins for the two Apple tables and the order row, honouring each SQL precondition. */
    private void stubStore() {
        appleRow = new HashMap<>(Map.of("order_no", ORDER, "member_id", MEMBER, "plan_code", "MONTH", "product_id", PRODUCT,
                "app_account_token", TOKEN, "bundle_id", BUNDLE, "environment", "Sandbox", "state", "PENDING"));
        orderRow = new HashMap<>(Map.of("id", 9L, "order_no", ORDER, "member_id", MEMBER, "status", 0, "channel", "APPLE",
                "price_fen", 1800L, "valid_days", 30L));
        inbox = new HashMap<>();
        when(mapper.selectByTokenForUpdate(anyString())).thenAnswer(i -> TOKEN.equals(i.getArgument(0)) ? appleRow : null);
        when(mapper.selectByToken(anyString())).thenAnswer(i -> TOKEN.equals(i.getArgument(0)) ? appleRow : null);
        when(mapper.selectByTransactionForUpdate(anyString()))
                .thenAnswer(i -> i.getArgument(0).equals(appleRow.get("transaction_id")) ? appleRow : null);
        when(mapper.selectByOrderNo(anyString())).thenAnswer(i -> appleRow);
        when(mapper.bindTransaction(anyString(), anyString(), anyString(), any(), any(), any())).thenAnswer(i -> {
            if (appleRow.get("transaction_id") != null) return 0;
            appleRow.put("transaction_id", i.getArgument(1));
            return 1;
        });
        when(mapper.markGranted(anyString())).thenAnswer(i -> "PENDING".equals(appleRow.put("state", "GRANTED")) ? 1 : 0);
        when(mapper.markRevoked(anyString(), any(), any())).thenAnswer(i -> {
            appleRow.put("state", "REVOKED");
            return 1;
        });
        when(mapper.markOrderPaid(anyLong(), anyString(), any())).thenAnswer(i -> {
            int status = ((Number) orderRow.get("status")).intValue();
            if (status != 0 && status != 2) return 0;
            orderRow.put("status", 1);
            return 1;
        });
        when(passMapper.selectOrderByMemberRequest(anyLong(), anyString())).thenReturn(null);
        when(passMapper.selectOrderByNoForUpdate(ORDER)).thenAnswer(i -> orderRow);
        when(passMapper.selectOrderByNo(anyString())).thenAnswer(i -> orderRow);
        when(pass.orderView(anyMap())).thenAnswer(i -> new LinkedHashMap<>(Map.of("orderNo", ORDER)));
        org.mockito.Mockito.doAnswer(i -> {
            orderRow.put("status", 3);
            return null;
        }).when(pass).finalizeAppleRefund(anyMap(), any());
        when(mapper.insertNotification(anyString(), any(), any(), any(), anyString())).thenAnswer(i -> {
            if (inbox.containsKey(i.<String>getArgument(0))) return 0;
            inbox.put(i.getArgument(0), new HashMap<>(Map.of("payload_hash", i.getArgument(4), "state", "RECEIVED")));
            return 1;
        });
        when(mapper.lockNotification(anyString())).thenAnswer(i -> inbox.get(i.<String>getArgument(0)));
        when(mapper.notificationState(anyString(), anyString(), any())).thenAnswer(i -> {
            Map<String, Object> row = inbox.get(i.<String>getArgument(0));
            if (row == null || "APPLIED".equals(row.get("state"))) return 0;
            row.put("state", i.getArgument(1));
            return 1;
        });
        when(passMapper.selectPlanByCode("MONTH")).thenReturn(new HashMap<>(Map.of("id", 1L, "plan_code", "MONTH",
                "title", "月卡", "price_fen", 1800L, "valid_days", 30L, "status", 1)));
        when(passMapper.selectOnSalePlans()).thenReturn(List.of(new HashMap<>(Map.of("id", 1L, "plan_code", "MONTH",
                "title", "月卡", "price_fen", 1800L, "valid_days", 30L, "status", 1))));
    }

    // ------------------------------------------------------------------ JWS fixtures

    static String transactionJson(String transactionId, String productId, String token, String bundle, String env,
                                  String type, Long revocationDate) {
        long now = System.currentTimeMillis();
        StringBuilder s = new StringBuilder("{\"transactionId\":\"").append(transactionId)
                .append("\",\"originalTransactionId\":\"").append(transactionId)
                .append("\",\"bundleId\":\"").append(bundle).append("\",\"productId\":\"").append(productId)
                .append("\",\"purchaseDate\":").append(now - 1000).append(",\"originalPurchaseDate\":").append(now - 1000)
                .append(",\"quantity\":1,\"type\":\"").append(type).append("\",\"inAppOwnershipType\":\"PURCHASED\"")
                .append(",\"signedDate\":").append(now).append(",\"environment\":\"").append(env)
                .append("\",\"price\":18000,\"currency\":\"CNY\",\"storefront\":\"CHN\"");
        if (token != null) s.append(",\"appAccountToken\":\"").append(token).append('"');
        if (revocationDate != null) s.append(",\"revocationDate\":").append(revocationDate).append(",\"revocationReason\":0");
        return s.append('}').toString();
    }

    String transaction(String transactionId) {
        return chain.sign(transactionJson(transactionId, PRODUCT, TOKEN, BUNDLE, "Sandbox", "Non-Renewing Subscription", null));
    }

    String notification(String type, String uuid, String signedTransaction) {
        return chain.sign("{\"notificationType\":\"" + type + "\",\"notificationUUID\":\"" + uuid + "\",\"version\":\"2.0\""
                + ",\"signedDate\":" + System.currentTimeMillis() + ",\"data\":{\"bundleId\":\"" + BUNDLE
                + "\",\"environment\":\"Sandbox\",\"signedTransactionInfo\":\"" + signedTransaction + "\"}}");
    }

    // ------------------------------------------------------------------ verifier: chain, bundle, environment

    @Test
    void verifierAcceptsOnlyTheConfiguredChainBundleAndEnvironment() {
        JWSTransactionDecodedPayload ok = verifier.transaction(transaction("2000000001"));
        assertEquals("2000000001", ok.getTransactionId());
        assertEquals(UUID.fromString(TOKEN), ok.getAppAccountToken());

        // A chain that does not lead to the configured root.
        String foreign = ApplePassTestChain.create().sign(transactionJson("2000000002", PRODUCT, TOKEN, BUNDLE, "Sandbox",
                "Non-Renewing Subscription", null));
        assertEquals("APPLE_TRANSACTION_VERIFICATION_FAILED",
                assertThrows(PlatformPassException.class, () -> verifier.transaction(foreign)).getReasonCode());
        // Leaf without Apple's signing-certificate OID.
        ApplePassTestChain noOid = ApplePassTestChain.create(false);
        var noOidVerifier = new PlatformPassAppleVerifier(config, List.of(noOid.rootDer()));
        assertThrows(PlatformPassException.class, () -> noOidVerifier.transaction(noOid.sign(transactionJson("2000000003",
                PRODUCT, TOKEN, BUNDLE, "Sandbox", "Non-Renewing Subscription", null))));
        // Another app's bundle and the other environment.
        assertThrows(PlatformPassException.class, () -> verifier.transaction(chain.sign(transactionJson("2000000004", PRODUCT,
                TOKEN, "com.other.app", "Sandbox", "Non-Renewing Subscription", null))));
        assertThrows(PlatformPassException.class, () -> verifier.transaction(chain.sign(transactionJson("2000000005", PRODUCT,
                TOKEN, BUNDLE, "Production", "Non-Renewing Subscription", null))));
        // Tampered payload (signature no longer matches) and unsigned / malformed input.
        String good = transaction("2000000006");
        String[] parts = good.split("\\.");
        String forged = parts[0] + "." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(transactionJson(
                "2000000007", PRODUCT, TOKEN, BUNDLE, "Sandbox", "Non-Renewing Subscription", null).getBytes()) + "." + parts[2];
        assertThrows(PlatformPassException.class, () -> verifier.transaction(forged));
        assertThrows(PlatformPassException.class, () -> verifier.transaction("eyJhbGciOiJub25lIn0.e30."));
        assertEquals("APPLE_SIGNED_DATA_INVALID",
                assertThrows(PlatformPassException.class, () -> verifier.transaction(" ")).getReasonCode());
    }

    @Test
    void verifierFailsClosedWithoutConfiguration() {
        var empty = new PlatformPassAppleVerifier(new PlatformPassAppleProperties());
        assertEquals("APPLE_PASS_DISABLED", empty.unavailableReason());
        var c = config();
        var noRoots = new PlatformPassAppleVerifier(c);
        assertEquals("APPLE_TRUST_ROOTS_NOT_CONFIGURED", noRoots.unavailableReason());
        c.setEnvironment("LocalTesting");
        assertEquals("APPLE_ENVIRONMENT_INVALID", new PlatformPassAppleVerifier(c, List.of(chain.rootDer())).unavailableReason());
        c.setEnvironment("Production");
        assertEquals("APPLE_APP_ID_NOT_CONFIGURED", new PlatformPassAppleVerifier(c, List.of(chain.rootDer())).unavailableReason());
        c.setEnvironment("Sandbox");
        c.setBundleId(" ");
        assertEquals("APPLE_BUNDLE_NOT_CONFIGURED", new PlatformPassAppleVerifier(c, List.of(chain.rootDer())).unavailableReason());
        // Server API lookup needs every credential; none is set by default.
        assertEquals("APPLE_SERVER_API_UNAVAILABLE",
                assertThrows(PlatformPassException.class, () -> verifier.lookup("2000000001")).getReasonCode());
    }

    @Test
    void verifierReadsRootsFromConfiguredFilesOnly(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path root = dir.resolve("root.cer");
        Files.write(root, chain.rootDer());
        var c = config();
        c.setRootCertificatePaths(List.of(root.toString()));
        var fromFile = new PlatformPassAppleVerifier(c);
        assertNull(fromFile.unavailableReason());
        assertEquals("2000000001", fromFile.transaction(transaction("2000000001")).getTransactionId());
    }

    @Test
    void shippedDefaultsKeepTheApplePassOff() {
        var defaults = new PlatformPassAppleProperties();
        assertFalse(defaults.isEnabled());
        assertNull(defaults.getBundleId());
        assertTrue(defaults.getRootCertificatePaths().isEmpty());
        assertTrue(defaults.getProductIds().isEmpty());
        assertFalse(defaults.isServerApiEnabled());
        assertTrue(defaults.isOnlineChecks());
    }

    // ------------------------------------------------------------------ money switch

    @Test
    void closedSwitchStillRefusesOfferAndNewOrders() {
        moneyOpen = false;
        assertEquals("MONEY_SWITCH_CLOSED", service.unavailableReason());
        Map<String, Object> offer = service.offer();
        assertEquals(false, offer.get("available"));
        assertEquals("MONEY_SWITCH_CLOSED", offer.get("unavailableReason"));
        assertEquals(List.of(), offer.get("products"));
        PlatformPassException order = assertThrows(PlatformPassException.class,
                () -> service.createOrder(MEMBER, "MONTH", "req-12345678"));
        assertEquals("MONEY_SWITCH_CLOSED", order.getReasonCode());
        assertEquals(503, order.getHttpStatus());
        verify(passMapper, never()).insertOrder(anyMap());
        verify(mapper, never()).insertAppleOrder(anyMap());
        verify(pass, never()).activate(anyMap(), any(), any());
    }

    @Test
    void closedSwitchStillDeliversAVerifiedTransactionForAnExistingOrder() {
        // MERCHANT-2: the order was created while selling was open; the area closed before the app confirmed.
        moneyOpen = false;
        assertNull(service.undeliverableReason());
        service.confirm(MEMBER, transaction("2000000001"));
        verify(pass, times(1)).activate(anyMap(), any(), any());
        assertEquals("GRANTED", appleRow.get("state"));
        assertEquals(1, orderRow.get("status"));
        // Still only for the member who owns the order.
        assertEquals("ORDER_NOT_FOUND", assertThrows(PlatformPassException.class,
                () -> service.confirm(7L, transaction("2000000001"))).getReasonCode());
        // Lookup is not refused by the switch either; here it fails only because the server API is not configured.
        PlatformPassException lookup = assertThrows(PlatformPassException.class,
                () -> service.lookup(MEMBER, "2000000001"));
        assertNotEquals("MONEY_SWITCH_CLOSED", lookup.getReasonCode());
        verify(passMapper, never()).insertOrder(anyMap());
    }

    @Test
    void deliveryStillNeedsTheAppleChannelConfiguration() {
        config.setEnabled(false);
        assertNotNull(service.undeliverableReason());
        assertThrows(PlatformPassException.class, () -> service.confirm(MEMBER, transaction("2000000001")));
        verify(pass, never()).activate(anyMap(), any(), any());
    }

    @Test
    void switchReadFailureCountsAsClosed() {
        when(moneySwitch.snapshot()).thenThrow(new IllegalStateException("db down"));
        assertEquals("MONEY_SWITCH_CLOSED", service.unavailableReason());
    }

    @Test
    void passDisabledIsRefusedBeforeTheSwitchIsRead() {
        passProps.setEnabled(false);
        assertEquals("PLATFORM_PASS_DISABLED", service.unavailableReason());
    }

    @Test
    void controllerAnswersMoneySwitchClosedAsARejection() {
        moneyOpen = false;
        var controller = new ApiPlatformPassAppleController(service) {
            @Override public Long getAppUserId() { return MEMBER; }
        };
        PlatformPassException e = assertThrows(PlatformPassException.class,
                () -> controller.create(Map.of("planCode", "MONTH", "requestId", "req-12345678")));
        AjaxResult body = controller.handleRejection(e);
        assertEquals(503, body.get("code"));
        assertEquals("MONEY_SWITCH_CLOSED", body.get("reasonCode"));
    }

    // ------------------------------------------------------------------ order and grant

    @Test
    void offerAndOrderUseServerConfiguredProductAndIssueAnAccountToken() {
        Map<String, Object> offer = service.offer();
        assertEquals(true, offer.get("available"));
        assertEquals("Sandbox", offer.get("environment"));
        @SuppressWarnings("unchecked") var products = (List<Map<String, Object>>) offer.get("products");
        assertEquals(PRODUCT, products.get(0).get("productId"));

        when(passMapper.insertOrder(anyMap())).thenReturn(1);
        when(mapper.insertAppleOrder(anyMap())).thenReturn(1);
        service.createOrder(MEMBER, "month", "req-12345678");
        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(mapper).insertAppleOrder(captor.capture());
        assertEquals(PRODUCT, captor.getValue().get("product_id"));
        assertEquals(BUNDLE, captor.getValue().get("bundle_id"));
        UUID.fromString((String) captor.getValue().get("app_account_token"));
        var orderCaptor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(passMapper).insertOrder(orderCaptor.capture());
        assertEquals("APPLE", orderCaptor.getValue().get("channel"));
        assertEquals(1800L, orderCaptor.getValue().get("price_fen"));

        assertEquals("PLAN_NOT_ON_SALE", assertThrows(PlatformPassException.class,
                () -> service.createOrder(MEMBER, "YEAR", "req-87654321")).getReasonCode());
        assertEquals("REQUEST_ID_REQUIRED", assertThrows(PlatformPassException.class,
                () -> service.createOrder(MEMBER, "MONTH", "x")).getReasonCode());
    }

    @Test
    void verifiedTransactionGrantsOnceAndReplaysIdempotently() {
        String jws = transaction("2000000001");
        service.confirm(MEMBER, jws);
        service.confirm(MEMBER, jws);
        verify(pass, times(1)).activate(anyMap(), any(), any());
        assertEquals("GRANTED", appleRow.get("state"));
        assertEquals("2000000001", appleRow.get("transaction_id"));
        assertEquals(1, orderRow.get("status"));
        // The same order cannot be bound to a second purchase.
        assertEquals("APPLE_ORDER_ALREADY_BOUND", assertThrows(PlatformPassException.class,
                () -> service.confirm(MEMBER, transaction("2000000099"))).getReasonCode());
        verify(pass, times(1)).activate(anyMap(), any(), any());
    }

    @Test
    void anotherMembersTokenOrAnUnknownProductNeverGrants() {
        assertEquals("ORDER_NOT_FOUND", assertThrows(PlatformPassException.class,
                () -> service.confirm(7L, transaction("2000000001"))).getReasonCode());
        String unknown = chain.sign(transactionJson("2000000002", "org.chengyin.test.template", TOKEN, BUNDLE, "Sandbox",
                "Non-Renewing Subscription", null));
        assertEquals("APPLE_PRODUCT_UNKNOWN",
                assertThrows(PlatformPassException.class, () -> service.confirm(MEMBER, unknown)).getReasonCode());
        String consumable = chain.sign(transactionJson("2000000003", PRODUCT, TOKEN, BUNDLE, "Sandbox", "Consumable", null));
        assertEquals("APPLE_PRODUCT_TYPE_UNSUPPORTED",
                assertThrows(PlatformPassException.class, () -> service.confirm(MEMBER, consumable)).getReasonCode());
        String noToken = chain.sign(transactionJson("2000000004", PRODUCT, null, BUNDLE, "Sandbox",
                "Non-Renewing Subscription", null));
        assertEquals("APPLE_TRANSACTION_FACTS_INCOMPLETE",
                assertThrows(PlatformPassException.class, () -> service.confirm(MEMBER, noToken)).getReasonCode());
        verify(pass, never()).activate(anyMap(), any(), any());
    }

    @Test
    void anAlreadyRevokedTransactionIsNeverGranted() {
        String revoked = chain.sign(transactionJson("2000000001", PRODUCT, TOKEN, BUNDLE, "Sandbox",
                "Non-Renewing Subscription", System.currentTimeMillis()));
        service.confirm(MEMBER, revoked);
        verify(pass, never()).activate(anyMap(), any(), any());
        assertEquals("REVOKED", appleRow.get("state"));
    }

    // ------------------------------------------------------------------ App Store Server Notifications V2

    @Test
    void refundNotificationTakesThePassBackEvenWithTheSwitchClosedAndOnlyOnce() {
        service.confirm(MEMBER, transaction("2000000001"));
        moneyOpen = false;
        String uuid = UUID.randomUUID().toString();
        String payload = notification("REFUND", uuid, transaction("2000000001"));
        Optional<Map<String, Object>> first = service.notificationIfPass(payload);
        assertEquals("APPLIED", first.orElseThrow().get("state"));
        assertEquals("REVOKED", appleRow.get("state"));
        assertEquals(3, orderRow.get("status"));
        // Apple redelivers the same notification: acknowledged, nothing taken back twice.
        assertEquals("APPLIED", service.notificationIfPass(payload).orElseThrow().get("state"));
        verify(pass, times(1)).finalizeAppleRefund(anyMap(), any());
        // A later confirm of the refunded transaction never grants again.
        moneyOpen = true;
        service.confirm(MEMBER, transaction("2000000001"));
        verify(pass, times(1)).activate(anyMap(), any(), any());
    }

    @Test
    void revokeBeforeGrantPinsTheOrderSoItIsNeverGranted() {
        String uuid = UUID.randomUUID().toString();
        assertEquals("APPLIED", service.notificationIfPass(notification("REVOKE", uuid, transaction("2000000001")))
                .orElseThrow().get("state"));
        assertEquals("REVOKED", appleRow.get("state"));
        verify(passMapper).markCancelled(org.mockito.ArgumentMatchers.eq(9L), org.mockito.ArgumentMatchers.eq(MEMBER), any());
        service.confirm(MEMBER, transaction("2000000001"));
        verify(pass, never()).activate(anyMap(), any(), any());
    }

    @Test
    void oneTimeChargeAfterSwitchOffGrantsTheExistingOrder() {
        moneyOpen = false;
        String closed = notification("ONE_TIME_CHARGE", UUID.randomUUID().toString(), transaction("2000000001"));
        assertEquals("APPLIED", service.notificationIfPass(closed).orElseThrow().get("state"));
        verify(pass, times(1)).activate(anyMap(), any(), any());
        // Redelivery and a later confirm from the app add nothing.
        assertEquals("APPLIED", service.notificationIfPass(closed).orElseThrow().get("state"));
        service.confirm(MEMBER, transaction("2000000001"));
        verify(pass, times(1)).activate(anyMap(), any(), any());
    }

    @Test
    void oneTimeChargeWithoutOrderIsIgnored() {
        String stranger = chain.sign(transactionJson("2000000005", PRODUCT, UUID.randomUUID().toString(), BUNDLE, "Sandbox",
                "Non-Renewing Subscription", null));
        String uuid = UUID.randomUUID().toString();
        assertEquals("IGNORED", service.notificationIfPass(notification("ONE_TIME_CHARGE", uuid, stranger))
                .orElseThrow().get("state"));
        verify(pass, never()).activate(anyMap(), any(), any());
    }

    @Test
    void aChargeIgnoredByTheOldSwitchRuleIsProcessedWhenAppleRedelivers() {
        String uuid = UUID.randomUUID().toString();
        String payload = notification("ONE_TIME_CHARGE", uuid, transaction("2000000001"));
        // Simulate a row stored IGNORED by the previous version while the area was closed.
        inbox.put(uuid, new HashMap<>(Map.of("payload_hash", PlatformPassService.sha256(payload), "state", "IGNORED")));
        assertEquals("APPLIED", service.notificationIfPass(payload).orElseThrow().get("state"));
        verify(pass, times(1)).activate(anyMap(), any(), any());
    }

    @Test
    void unmatchedRefundIsLeftForAppleToRedeliver() {
        String other = chain.sign(transactionJson("2000000001", PRODUCT, UUID.randomUUID().toString(), BUNDLE, "Sandbox",
                "Non-Renewing Subscription", null));
        String uuid = UUID.randomUUID().toString();
        PlatformPassException e = assertThrows(PlatformPassException.class,
                () -> service.notificationIfPass(notification("REFUND", uuid, other)));
        assertEquals(503, e.getHttpStatus());
        assertEquals("PENDING", inbox.get(uuid).get("state"));
    }

    @Test
    void nonPassOrUnverifiableNotificationsAreHandedOn() {
        String template = chain.sign(transactionJson("2000000001", "org.chengyin.test.template", TOKEN, BUNDLE, "Sandbox",
                "Non-Consumable", null));
        assertTrue(service.notificationIfPass(notification("REFUND", UUID.randomUUID().toString(), template)).isEmpty());
        String foreign = ApplePassTestChain.create().sign("{\"notificationType\":\"REFUND\"}");
        assertTrue(service.notificationIfPass(foreign).isEmpty());
        config.setEnabled(false);
        assertTrue(service.notificationIfPass(notification("REFUND", UUID.randomUUID().toString(), transaction("1")))
                .isEmpty());
    }

    @Test
    void notificationEndpointRoutesNonPassProductsToTheWorkshopHandler() {
        PlatformPassAppleService passService = mock(PlatformPassAppleService.class);
        when(passService.notificationIfPass("other")).thenReturn(Optional.empty());
        when(passService.notificationIfPass("pass")).thenReturn(Optional.of(Map.of("state", "APPLIED")));
        WorkshopAppleIapService workshop = mock(WorkshopAppleIapService.class);
        when(workshop.notification("other")).thenReturn(Map.of("state", "WORKSHOP"));
        @SuppressWarnings("unchecked") ObjectProvider<WorkshopAppleIapService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(workshop);
        var controller = new ApiAppleNotificationController(passService, provider);

        assertEquals(200, controller.notification(new ApiAppleNotificationController.Notification("pass")).getStatusCode().value());
        verify(workshop, never()).notification("pass");
        assertEquals(200, controller.notification(new ApiAppleNotificationController.Notification("other")).getStatusCode().value());
        verify(workshop).notification("other");
        assertEquals(400, controller.notification(null).getStatusCode().value());

        when(passService.notificationIfPass("retry")).thenThrow(new PlatformPassException("APPLE_ORDER_NOT_MATCHED", 503, "x"));
        assertEquals(503, controller.notification(new ApiAppleNotificationController.Notification("retry")).getStatusCode().value());
        when(provider.getIfAvailable()).thenReturn(null);
        assertEquals(503, controller.notification(new ApiAppleNotificationController.Notification("other")).getStatusCode().value());
    }
}
