package com.chengyinhub.business.platformpass;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.platformpass.mapper.PlatformPassMapper;
import com.chengyinhub.business.service.IWeChatMiniSessionService;
import com.chengyinhub.business.service.RefundRequestResult;
import com.chengyinhub.business.service.WechatPaymentOperations;
import com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 通行证服务：开关、幂等、归属、已验证事实、回查开通、退款围栏。只测判定与调用，不连库（库见 PlatformPassMySqlIT）。 */
class PlatformPassServiceTest {
    private static final long MEMBER = 42L;
    private PlatformPassMapper mapper;
    private PlatformPassProperties props;
    private PlatformPassService service;
    private WechatPaymentOperations wechat;
    private WechatVerifiedPaymentFactService facts;
    private XpayGateway xpay;
    private IWeChatMiniSessionService sessions;

    @BeforeEach
    void setUp() {
        mapper = mock(PlatformPassMapper.class);
        props = new PlatformPassProperties();
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new PlatformPassService(mapper, props, tm);
        wechat = mock(WechatPaymentOperations.class);
        facts = mock(WechatVerifiedPaymentFactService.class);
        xpay = mock(XpayGateway.class);
        sessions = mock(IWeChatMiniSessionService.class);
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        UmsMember m = new UmsMember();
        m.setId(MEMBER);
        m.setWxOpenId("openid-42");
        when(members.selectUmsMemberById(MEMBER)).thenReturn(m);
        ReflectionTestUtils.setField(service, "wechatPay", wechat);
        ReflectionTestUtils.setField(service, "verifiedFacts", facts);
        ReflectionTestUtils.setField(service, "xpay", xpay);
        ReflectionTestUtils.setField(service, "miniSessions", sessions);
        ReflectionTestUtils.setField(service, "memberMapper", members);
    }

    private void enableAll() {
        props.setEnabled(true);
        props.setJsapiEnabled(true);
        props.getXpay().setEnabled(true);
        props.getXpay().setOfferId("OFFER1");
        props.getXpay().setAppKey("app-key");
        props.getXpay().setEnv(1);
        when(wechat.isReady()).thenReturn(true);
    }

    private static Map<String, Object> plan(String code, int status, String productId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", 1L); p.put("plan_code", code); p.put("title", "年卡"); p.put("price_fen", 29900);
        p.put("valid_days", 365); p.put("status", status); p.put("xpay_product_id", productId);
        return p;
    }

    private static Map<String, Object> order(String channel, int status) {
        Map<String, Object> o = new HashMap<>();
        o.put("id", 10L); o.put("order_no", "PPO20261110000000000000000000001"); o.put("member_id", MEMBER);
        o.put("plan_code", "YEAR"); o.put("price_fen", 29900); o.put("valid_days", 365); o.put("channel", channel);
        o.put("status", status); o.put("request_fingerprint", "x");
        return o;
    }

    // ---- 开关

    @Test
    void disabledSwitchMeansNoCoverageAndNoSale() {
        assertNull(service.coverage(MEMBER, 5L, new Date()));
        assertEquals(false, service.overview(MEMBER).get("enabled"));
        PlatformPassException e = assertThrows(PlatformPassException.class,
                () -> service.createOrder(MEMBER, "YEAR", "JSAPI", "req-00000001"));
        assertEquals("PLATFORM_PASS_DISABLED", e.getReasonCode());
        verify(mapper, never()).selectActiveMembership(anyLong(), any());
    }

    @Test
    void channelSwitchesAreIndependent() {
        props.setEnabled(true);
        Map<?, ?> channels = service.channels();
        assertEquals(false, ((Map<?, ?>) channels.get("jsapi")).get("available"));
        assertEquals("JSAPI_DISABLED", ((Map<?, ?>) channels.get("jsapi")).get("reason"));
        assertEquals("XPAY_DISABLED", ((Map<?, ?>) channels.get("xpay")).get("reason"));
    }

    @Test
    void defaultCoverageIncludesEveryPublishedSourceButRequiresActiveMembership() {
        enableAll();
        Date now = new Date(1_800_000_000_000L);
        when(mapper.selectActiveMembership(MEMBER, now)).thenReturn(activeMembership(now));
        // Official, player and club topics are all eligible; source is not a coverage gate.
        for (long topicId : List.of(5L, 6L, 7L)) {
            when(mapper.isPublishedTopic(topicId)).thenReturn(1);
            assertTrue(props.coversTopic(topicId));
            assertEquals(7L, service.coverage(MEMBER, topicId, now).membershipId());
        }
        when(mapper.isPublishedTopic(8L)).thenReturn(0);
        assertNull(service.coverage(MEMBER, 8L, now), "Unpublished topics are not covered");
        assertNull(service.coverage(99L, 5L, now), "An active membership is required");
    }

    @Test
    void defaultBatchCoverageUsesPublishedTopicsAndTheSuppliedMembershipInstant() {
        enableAll();
        Date now = new Date(1_800_000_000_000L);
        when(mapper.selectActiveMembership(MEMBER, now)).thenReturn(activeMembership(now));
        when(mapper.selectPublishedTopicIds(List.of(5L, 6L, 7L, 8L))).thenReturn(List.of(5L, 6L, 7L));

        assertEquals(Set.of(5L, 6L, 7L), service.coveredTopics(MEMBER, List.of(5L, 6L, 7L, 8L, 6L), now));
        verify(mapper).selectActiveMembership(MEMBER, now);
        verify(mapper).selectPublishedTopicIds(List.of(5L, 6L, 7L, 8L));
        verify(mapper, never()).isPublishedTopic(anyLong());
    }

    @Test
    void configuredAllowlistIsSharedBySingleBatchCatalogAndOverviewCoverage() {
        enableAll();
        props.setCoverageTopicIds(Set.of(6L, 8L));
        Date now = new Date(1_800_000_000_000L);
        when(mapper.selectActiveMembership(eq(MEMBER), any())).thenReturn(activeMembership(now));
        when(mapper.isPublishedTopic(6L)).thenReturn(1);
        when(mapper.isPublishedTopic(8L)).thenReturn(0);
        when(mapper.selectPublishedTopicIds(List.of(6L, 8L))).thenReturn(List.of(6L));
        when(mapper.selectLibrary(null, 20)).thenReturn(List.of(libraryTopic(5L), libraryTopic(6L), libraryTopic(8L)));
        when(mapper.countLibrary(Set.of(6L, 8L))).thenReturn(1);

        assertFalse(props.coversTopic(5L));
        assertTrue(props.coversTopic(6L));
        assertNull(service.coverage(MEMBER, 5L, now));
        assertNotNull(service.coverage(MEMBER, 6L, now));
        assertNull(service.coverage(MEMBER, 8L, now), "An allowlisted topic must still be published");
        assertEquals(Set.of(6L), service.coveredTopics(MEMBER, List.of(5L, 6L, 8L), now));
        Map<String, Object> catalog = service.library(MEMBER, null, 20);
        assertEquals(List.of(false, true, false), libraryRows(catalog).stream().map(r -> r.get("passCovered")).toList());
        assertEquals(false, catalog.get("covered"), "Partial coverage must not label the whole catalog covered");
        assertEquals(1, service.overview(MEMBER).get("libraryCount"));
        verify(mapper).countLibrary(Set.of(6L, 8L));
        verify(mapper, never()).isPublishedTopic(5L);
    }

    @Test
    void batchCoverageRejectsAnonymousDisabledNonholderAndInvalidInputs() {
        Date now = new Date(1_800_000_000_000L);
        assertEquals(Set.of(), service.coveredTopics(MEMBER, List.of(5L), now));
        enableAll();
        assertEquals(Set.of(), service.coveredTopics(null, List.of(5L), now));
        assertEquals(Set.of(), service.coveredTopics(0L, List.of(5L), now));
        assertEquals(Set.of(), service.coveredTopics(MEMBER, null, now));
        assertEquals(Set.of(), service.coveredTopics(MEMBER, List.of(), now));
        assertEquals(Set.of(), service.coveredTopics(MEMBER, java.util.Arrays.asList(null, 0L, -1L), now));
        assertEquals(Set.of(), service.coveredTopics(MEMBER, List.of(5L), now));
        verify(mapper, never()).selectPublishedTopicIds(any());
        verify(mapper).selectActiveMembership(MEMBER, now);
    }

    private static Map<String, Object> activeMembership(Date now) {
        return Map.of("id", 7L, "valid_to", new Date(now.getTime() + 86_400_000L));
    }

    @Test
    void nullOnlyBoundCoverageNormalizesToAllButMixedNullNeverBroadensAnAllowlist() {
        PlatformPassProperties bound = new PlatformPassProperties();
        bound.setCoverageTopicIds(java.util.Collections.singleton(null));
        assertEquals(Set.of(), bound.getCoverageTopicIds());
        assertTrue(bound.coversTopic(5L));
        bound.setCoverageTopicIds(Set.of(6L));
        assertThrows(NullPointerException.class, () -> bound.setCoverageTopicIds(
                new java.util.HashSet<>(java.util.Arrays.asList(6L, null))));
        assertEquals(Set.of(6L), bound.getCoverageTopicIds());
        assertTrue(bound.coversTopic(6L));
        assertFalse(bound.coversTopic(5L));
    }

    @Test
    void publishedPolicyDoesNotBadgeUnavailableSelfPlayButAllowsUnexpiredFreeSelfPlay() {
        enableAll();
        Date now = new Date(1_800_000_000_000L);
        when(mapper.selectActiveMembership(eq(MEMBER), any())).thenReturn(activeMembership(now));
        Map<String, Object> noSelfPlay = new HashMap<>(libraryTopic(1L));
        noSelfPlay.put("self_play", Boolean.FALSE);
        Map<String, Object> closed = new HashMap<>(libraryTopic(2L));
        closed.put("lifecycle", 6);
        Map<String, Object> notStarted = new HashMap<>(libraryTopic(3L));
        notStarted.put("selling_started_at", new Date(4_000_000_000_000L));
        Map<String, Object> ended = new HashMap<>(libraryTopic(4L));
        ended.put("sale_end_at", new Date(1_600_000_000_000L));
        Map<String, Object> free = new HashMap<>(libraryTopic(5L));
        free.put("self_play_price", BigDecimal.ZERO);
        free.put("lifecycle", 0);
        free.remove("selling_started_at");
        Map<String, Object> expiredFree = new HashMap<>(free);
        expiredFree.put("id", 6L);
        expiredFree.put("sale_end_at", new Date(1_600_000_000_000L));
        when(mapper.selectLibrary(null, 20)).thenReturn(List.of(noSelfPlay, closed, notStarted, ended, free, expiredFree));
        List<Long> ids = List.of(1L, 2L, 3L, 4L, 5L, 6L);
        when(mapper.selectPublishedTopicIds(ids)).thenReturn(ids);

        assertEquals(Set.copyOf(ids), service.coveredTopics(MEMBER, ids, now), "Published policy remains source/lifecycle independent");
        Map<String, Object> catalog = service.library(MEMBER, null, 20);
        assertEquals(6, libraryRows(catalog).size(), "Unavailable claims do not remove public rows");
        assertEquals(List.of(false, false, false, false, true, false),
                libraryRows(catalog).stream().map(row -> row.get("passCovered")).toList());
        assertEquals(false, catalog.get("covered"));
    }

    @Test
    void absentAndBlankCoverageConfigurationBindToAllTopics() {
        PlatformPassProperties absent = bindProperties(Map.of("platform-pass.enabled", "true"));
        assertEquals(Set.of(), absent.getCoverageTopicIds());
        assertTrue(absent.coversTopic(5L));
        for (String blank : List.of("", "   ")) {
            PlatformPassProperties bound = bindProperties(Map.of("platform-pass.coverage-topic-ids", blank));
            assertEquals(Set.of(), bound.getCoverageTopicIds());
            assertTrue(bound.coversTopic(5L));
            assertTrue(bound.coversTopic(999L));
            assertFalse(bound.coversTopic(null));
            assertFalse(bound.coversTopic(0L));
        }
    }

    @Test
    void nonemptyCoverageConfigurationBindsExactIdsAndMalformedValuesFailClosed() {
        PlatformPassProperties bound = bindProperties(Map.of("platform-pass.coverage-topic-ids", "6,8"));
        assertEquals(Set.of(6L, 8L), bound.getCoverageTopicIds());
        assertFalse(bound.coversTopic(5L));
        assertTrue(bound.coversTopic(6L));
        assertTrue(bound.coversTopic(8L));
        for (String malformed : List.of("not-a-topic", "6,not-a-topic")) {
            assertThrows(BindException.class,
                    () -> bindProperties(Map.of("platform-pass.coverage-topic-ids", malformed)),
                    "Malformed nonempty configuration must not silently become default-all");
        }
    }

    private static PlatformPassProperties bindProperties(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("platform-pass", Bindable.of(PlatformPassProperties.class)).get();
    }

    private static Map<String, Object> libraryTopic(long id) {
        return Map.of("id", id, "name", "主题" + id, "product_type", 1, "self_play", Boolean.TRUE, "lifecycle", 3,
                "selling_started_at", new Date(1_600_000_000_000L), "sale_end_at", new Date(4_102_444_800_000L),
                "self_play_price", new BigDecimal("100.00"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> libraryRows(Map<String, Object> catalog) {
        return (List<Map<String, Object>>) catalog.get("rows");
    }

    // ---- D-112 通行证卡字段

    private Map<String, Object> activeSegment(String planCode, Date from, Date to) {
        Map<String, Object> active = new HashMap<>();
        active.put("id", 7L); active.put("plan_code", planCode);
        active.put("valid_from", from); active.put("valid_to", to);
        return active;
    }

    @Test
    void holdingCarriesPlanLabelAndProgressDays() {
        enableAll();
        Date now = new Date();
        long day = 86_400_000L;
        when(mapper.selectActiveMembership(MEMBER, now))
                .thenReturn(activeSegment("YEAR", new Date(now.getTime() - 65 * day), new Date(now.getTime() + 300 * day)));
        Map<String, Object> holding = service.holding(MEMBER, now);
        assertEquals("YEAR", holding.get("planCode"));
        assertEquals("年卡", holding.get("planLabel"));
        assertEquals(365, holding.get("daysTotal"));
        assertEquals(300, holding.get("daysRemaining"));
        assertEquals(false, holding.get("teammateBenefit"), "通行证只覆盖本人自玩");
        assertNull(holding.get("badge"));
    }

    @Test
    void holdingFlagsExpiringBadgeAndMonthLabelAndClampsRemaining() {
        enableAll();
        Date now = new Date();
        long day = 86_400_000L;
        when(mapper.selectActiveMembership(MEMBER, now))
                .thenReturn(activeSegment("MONTH", new Date(now.getTime() - 26 * day), new Date(now.getTime() + 4 * day - 1000)));
        Map<String, Object> holding = service.holding(MEMBER, now);
        assertEquals("月卡", holding.get("planLabel"));
        assertEquals(30, holding.get("daysTotal"));
        assertEquals(4, holding.get("daysRemaining"), "向上取整天");
        assertEquals("EXPIRING", holding.get("badge"));
    }

    @Test
    void holdingWithoutActiveSegmentHasNullCardFacts() {
        enableAll();
        Map<String, Object> holding = service.holding(99L, new Date());
        assertEquals(false, holding.get("active"));
        assertNull(holding.get("planCode"));
        assertNull(holding.get("planLabel"));
        assertNull(holding.get("daysTotal"));
        assertNull(holding.get("daysRemaining"));
        assertNull(holding.get("badge"));
        assertEquals(false, holding.get("teammateBenefit"));
    }

    // ---- 下单

    @Test
    void createOrderFreezesPlanAndReplaysSameRequest() {
        enableAll();
        when(mapper.selectPlanByCode("YEAR")).thenReturn(plan("YEAR", 1, "prod_year"));
        when(mapper.insertOrder(any())).thenReturn(1);
        when(mapper.selectOrderByNo(anyString())).thenAnswer(i -> order("JSAPI", 0));
        Map<String, Object> view = service.createOrder(MEMBER, "year", "jsapi", "req-00000001");
        assertEquals("PENDING", view.get("status"));
        assertEquals(29900L, view.get("priceFen"));

        Map<String, Object> stored = order("JSAPI", 0);
        stored.put("request_fingerprint", PlatformPassService.sha256("YEAR|JSAPI|29900|365"));
        when(mapper.selectOrderByMemberRequest(MEMBER, "req-00000001")).thenReturn(stored);
        service.createOrder(MEMBER, "YEAR", "JSAPI", "req-00000001");
        verify(mapper, org.mockito.Mockito.times(1)).insertOrder(any());

        PlatformPassException conflict = assertThrows(PlatformPassException.class,
                () -> service.createOrder(MEMBER, "YEAR", "XPAY", "req-00000001"));
        assertEquals("REQUEST_CONFLICT", conflict.getReasonCode());
    }

    @Test
    void offSalePlanAndUnconfiguredXpayProductAreRefused() {
        enableAll();
        when(mapper.selectPlanByCode("MONTH")).thenReturn(plan("MONTH", 0, "p"));
        assertEquals("PLAN_NOT_ON_SALE", assertThrows(PlatformPassException.class,
                () -> service.createOrder(MEMBER, "MONTH", "JSAPI", "req-00000002")).getReasonCode());
        when(mapper.selectPlanByCode("YEAR")).thenReturn(plan("YEAR", 1, null));
        assertEquals("XPAY_PRODUCT_NOT_CONFIGURED", assertThrows(PlatformPassException.class,
                () -> service.createOrder(MEMBER, "YEAR", "XPAY", "req-00000003")).getReasonCode());
    }

    @Test
    void foreignOrderIsNotFound() {
        enableAll();
        Map<String, Object> other = order("JSAPI", 0);
        other.put("member_id", 999L);
        when(mapper.selectOrderByNo("PPO1")).thenReturn(other);
        assertEquals("ORDER_NOT_FOUND", assertThrows(PlatformPassException.class,
                () -> service.preparePayment(MEMBER, "PPO1", null)).getReasonCode());
        assertEquals("ORDER_NOT_FOUND", assertThrows(PlatformPassException.class,
                () -> service.cancel(MEMBER, "PPO1")).getReasonCode());
    }

    @Test
    void jsapiPrepayUsesGenericOrderWithFrozenAmount() {
        enableAll();
        when(mapper.selectOrderByNo("PPO1")).thenReturn(order("JSAPI", 0));
        when(wechat.createGenericOrder(eq("PPO20261110000000000000000000001"), any(), anyString(), eq("openid-42")))
                .thenReturn(Map.of("package", "prepay_id=x"));
        Map<String, Object> out = service.preparePayment(MEMBER, "PPO1", null);
        assertTrue(out.containsKey("payParams"));
        verify(wechat).createGenericOrder("PPO20261110000000000000000000001", new BigDecimal("299.00"),
                "城瘾通行证", "openid-42");
    }

    @Test
    void xpayPrepayRequiresMatchingSessionAndSignsSignData() {
        enableAll();
        when(mapper.selectOrderByNo("PPO1")).thenReturn(order("XPAY", 0));
        when(mapper.selectPlanByCode("YEAR")).thenReturn(plan("YEAR", 1, "prod_year"));
        when(sessions.exchange("code-1")).thenReturn(new IWeChatMiniSessionService.WeChatMiniSession("openid-42", "sk"));
        Map<String, Object> out = service.preparePayment(MEMBER, "PPO1", "code-1");
        @SuppressWarnings("unchecked") Map<String, Object> vp = (Map<String, Object>) out.get("virtualPayment");
        String signData = (String) vp.get("signData");
        JSONObject parsed = JSON.parseObject(signData);
        assertEquals("OFFER1", parsed.getString("offerId"));
        assertEquals(29900, parsed.getIntValue("goodsPrice"));
        assertEquals("prod_year", parsed.getString("productId"));
        assertEquals(1, parsed.getIntValue("env"));
        assertFalse(parsed.containsKey("platform"), "道具直购 signData 不带 platform");
        assertEquals(XpaySigner.paySig("app-key", "requestVirtualPayment", signData), vp.get("paySig"));
        assertEquals(XpaySigner.userSignature("sk", signData), vp.get("signature"));

        when(sessions.exchange("code-2")).thenReturn(new IWeChatMiniSessionService.WeChatMiniSession("someone-else", "sk"));
        assertEquals("SESSION_MISMATCH", assertThrows(PlatformPassException.class,
                () -> service.preparePayment(MEMBER, "PPO1", "code-2")).getReasonCode());
    }

    // ---- JSAPI 回调

    private static Map<String, String> paidFacts() {
        Map<String, String> f = new HashMap<>();
        f.put("trade_state", "SUCCESS");
        f.put("out_trade_no", "PPO20261110000000000000000000001");
        f.put("verified_fact_id", "501");
        f.put("verified_fact_hash", "a".repeat(64));
        return f;
    }

    private static WechatVerifiedPaymentFact fact(long total, String tradeType) {
        return new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT, "mch", "app",
                "PPO20261110000000000000000000001", "PPO20261110000000000000000000001", null, null, "TX1",
                tradeType, "CNY", total, total, Instant.now(), null, "SUCCESS");
    }

    @Test
    void jsapiPaidActivatesOnceWithStackedWindow() {
        enableAll();
        when(mapper.selectOrderByNoForUpdate(anyString())).thenReturn(order("JSAPI", 0));
        when(facts.requireConsumablePayment(any())).thenReturn(
                new WechatVerifiedPaymentFactService.Stored(new WechatVerifiedPaymentFact.Reference(501, "a".repeat(64)), fact(29900, "JSAPI")));
        when(mapper.markJsapiPaid(eq(10L), eq(501L), anyString(), eq("TX1"), any())).thenReturn(1);
        Date existingEnd = new Date(System.currentTimeMillis() + 10L * 86_400_000L);
        when(mapper.selectLatestValidToForUpdate(eq(MEMBER), any())).thenReturn(existingEnd);
        when(mapper.insertMembership(any())).thenReturn(1);

        assertTrue(service.handleJsapiPaid(paidFacts()));
        org.mockito.ArgumentCaptor<Map<String, Object>> row = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(mapper).insertMembership(row.capture());
        assertEquals(existingEnd, row.getValue().get("valid_from"), "续买从现有段到期顺延");
        assertEquals(existingEnd.getTime() + 365L * 86_400_000L, ((Date) row.getValue().get("valid_to")).getTime());
        verify(facts).completePaymentRecovery(any(), eq(10L));
    }

    @Test
    void jsapiAmountMismatchIsRejectedWithoutActivation() {
        enableAll();
        when(mapper.selectOrderByNoForUpdate(anyString())).thenReturn(order("JSAPI", 0));
        when(facts.requireConsumablePayment(any())).thenReturn(
                new WechatVerifiedPaymentFactService.Stored(new WechatVerifiedPaymentFact.Reference(501, "a".repeat(64)), fact(100, "JSAPI")));
        assertThrows(PlatformPassException.class, () -> service.handleJsapiPaid(paidFacts()));
        verify(mapper, never()).markJsapiPaid(anyLong(), anyLong(), anyString(), anyString(), any());
        verify(mapper, never()).insertMembership(any());
    }

    @Test
    void nonSuccessOrForeignPrefixIsIgnored() {
        Map<String, String> f = paidFacts();
        f.put("trade_state", "NOTPAY");
        assertFalse(service.handleJsapiPaid(f));
        f = paidFacts();
        f.put("out_trade_no", "MKO1");
        assertFalse(service.handleJsapiPaid(f));
    }

    // ---- 虚拟支付回查

    private static JSONObject remote(int status, long paid, String orderId) {
        JSONObject order = new JSONObject();
        order.put("status", status); order.put("paid_fee", paid); order.put("order_fee", paid);
        order.put("order_id", orderId); order.put("wx_order_id", "WXO1"); order.put("refund_fee", paid);
        JSONObject r = new JSONObject();
        r.put("errcode", 0); r.put("order", order);
        return r;
    }

    @Test
    void xpayConfirmActivatesOnlyOnPaidRemoteWithMatchingAmount() {
        enableAll();
        Map<String, Object> pending = order("XPAY", 0);
        when(mapper.selectOrderByNo(anyString())).thenReturn(pending);
        when(mapper.selectOrderByNoForUpdate(anyString())).thenReturn(pending);
        when(xpay.queryOrder("openid-42", "PPO20261110000000000000000000001", 1)).thenReturn(remote(1, 29900, "PPO20261110000000000000000000001"));
        service.confirmXpay(MEMBER, "PPO1");
        verify(mapper, never()).markXpayPaid(anyLong(), anyString(), anyInt(), any());

        when(xpay.queryOrder("openid-42", "PPO20261110000000000000000000001", 1)).thenReturn(remote(2, 100, "PPO20261110000000000000000000001"));
        assertThrows(PlatformPassException.class, () -> service.confirmXpay(MEMBER, "PPO1"));
        verify(mapper, never()).markXpayPaid(anyLong(), anyString(), anyInt(), any());

        when(xpay.queryOrder("openid-42", "PPO20261110000000000000000000001", 1)).thenReturn(remote(2, 29900, "PPO20261110000000000000000000001"));
        when(mapper.markXpayPaid(10L, "WXO1", 1, null)).thenReturn(1);
        when(mapper.markXpayPaid(eq(10L), eq("WXO1"), eq(1), any())).thenReturn(1);
        when(mapper.insertMembership(any())).thenReturn(1);
        service.confirmXpay(MEMBER, "PPO1");
        verify(mapper).insertMembership(any());
        verify(xpay).notifyProvideGoods("PPO20261110000000000000000000001", 1);
    }

    @Test
    void xpayPushIsOnlyASignalAndUnknownEventsAreRefused() {
        enableAll();
        JSONObject push = new JSONObject();
        push.put("Event", "xpay_goods_deliver_notify");
        push.put("OutTradeNo", "PPO20261110000000000000000000001");
        Map<String, Object> pending = order("XPAY", 0);
        when(mapper.selectOrderByNo(anyString())).thenReturn(pending);
        when(xpay.queryOrder(anyString(), anyString(), anyInt())).thenReturn(remote(1, 29900, "PPO20261110000000000000000000001"));
        assertFalse(service.handleXpayPush(push), "回查未付不开通");
        push.put("Event", "something_else");
        assertFalse(service.handleXpayPush(push));
    }

    // ---- 退款

    @Test
    void refundRefusedOnceAnyThemeWasClaimed() {
        enableAll();
        when(mapper.selectOrderByNoForUpdate("PPO1")).thenReturn(order("JSAPI", 1));
        Map<String, Object> membership = new HashMap<>();
        membership.put("id", 7L);
        when(mapper.selectMembershipByOrder(10L)).thenReturn(membership);
        when(mapper.countClaimsByMembership(7L)).thenReturn(1);
        assertEquals("PASS_ALREADY_USED", assertThrows(PlatformPassException.class,
                () -> service.requestRefund("PPO1", "admin", null)).getReasonCode());
        verify(wechat, never()).submitRefund(any(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void definitiveRefundFailureRestoresPaidStatus() {
        enableAll();
        Map<String, Object> paid = order("JSAPI", 1);
        paid.put("payment_fact_id", 501L); paid.put("payment_fact_hash", "a".repeat(64));
        when(mapper.selectOrderByNoForUpdate("PPO1")).thenReturn(paid);
        when(mapper.selectOrderByNo("PPO1")).thenReturn(paid);
        when(mapper.markRefundRequested(eq(10L), anyString(), anyString(), eq("admin"), any())).thenReturn(1);
        when(wechat.submitRefund(any(), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(RefundRequestResult.DEFINITIVE_FAILED);
        assertEquals("REFUND_REJECTED", assertThrows(PlatformPassException.class,
                () -> service.requestRefund("PPO1", "admin", "测试")).getReasonCode());
        verify(mapper).markRefundRolledBack(eq(10L), anyString());
    }

    @Test
    void refundNumbersFitWechatLimits() {
        String no = PlatformPassService.newNo(PlatformPassService.ORDER_PREFIX);
        assertEquals(32, no.length());
        assertTrue(no.matches("PPO[0-9]{29}"));
        assertTrue(PlatformPassService.newNo(PlatformPassService.REFUND_PREFIX).startsWith("RPP"));
    }

    @Test
    void recordClaimOutsideTransactionIsRefused() {
        assertThrows(IllegalStateException.class, () -> service.recordClaim(
                new PlatformPassService.Coverage(1L, new Date()), MEMBER, 5L, 9L));
        service.recordClaim(null, MEMBER, 5L, 9L); // 未覆盖：什么都不做
        verify(mapper, never()).insertClaim(any());
    }

    @Test
    void disabledCatalogStillReturnsRowsWithoutZeroPriceCoverage() {
        when(mapper.selectLibrary(null, 20)).thenReturn(List.of(libraryTopic(5L), libraryTopic(6L)));
        Map<String, Object> catalog = service.library(MEMBER, null, 20);
        assertEquals(false, catalog.get("enabled"));
        assertEquals(List.of(5L, 6L), libraryRows(catalog).stream().map(r -> r.get("topicId")).toList());
        assertEquals(List.of(false, false), libraryRows(catalog).stream().map(r -> r.get("passCovered")).toList());
        assertEquals(false, catalog.get("covered"));
        verify(mapper, never()).selectActiveMembership(anyLong(), any());
        verify(mapper, never()).selectPublishedTopicIds(any());
    }

    @Test
    void anonymousAndNonholderCatalogsStillReturnRowsWithoutCoverage() {
        enableAll();
        when(mapper.selectLibrary(null, 20)).thenReturn(List.of(libraryTopic(5L), libraryTopic(6L)));
        for (Long memberId : java.util.Arrays.asList(null, MEMBER)) {
            Map<String, Object> catalog = service.library(memberId, null, 20);
            assertEquals(2, libraryRows(catalog).size());
            assertEquals(List.of(false, false), libraryRows(catalog).stream().map(r -> r.get("passCovered")).toList());
            assertEquals(false, catalog.get("covered"));
        }
        verify(mapper, never()).selectPublishedTopicIds(any());
    }

    @Test
    void catalogIsCoveredOnlyWhenNonemptyAndEveryRowIsCovered() {
        enableAll();
        Date now = new Date(1_800_000_000_000L);
        when(mapper.selectActiveMembership(eq(MEMBER), any())).thenReturn(activeMembership(now));
        when(mapper.selectLibrary(null, 20)).thenReturn(List.of(libraryTopic(5L), libraryTopic(6L)));
        when(mapper.selectPublishedTopicIds(List.of(5L, 6L))).thenReturn(List.of(5L, 6L));
        Map<String, Object> catalog = service.library(MEMBER, null, 20);
        assertEquals(List.of(true, true), libraryRows(catalog).stream().map(r -> r.get("passCovered")).toList());
        assertEquals(true, catalog.get("covered"));
        when(mapper.selectLibrary(null, 20)).thenReturn(List.of());
        assertEquals(false, service.library(MEMBER, null, 20).get("covered"));
    }

    @Test
    void mapRowTimesFromDriverAreNormalisedToDate() {
        java.time.LocalDateTime t = java.time.LocalDateTime.of(2027, 11, 10, 12, 0);
        Date d = PlatformPassService.toDate(t);
        assertEquals(t.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), d.getTime());
        assertNull(PlatformPassService.toDate(null));
        Date now = new Date();
        assertEquals(now.getTime(), PlatformPassService.toDate(now).getTime());
        assertThrows(IllegalStateException.class, () -> PlatformPassService.toDate("2027-11-10"));
        enableAll();
        Map<String, Object> active = new HashMap<>();
        active.put("id", 7L); active.put("valid_to", java.time.LocalDateTime.now().plusDays(3));
        when(mapper.selectActiveMembership(eq(MEMBER), any())).thenReturn(active);
        when(mapper.isPublishedTopic(5L)).thenReturn(1);
        assertTrue(service.coverage(MEMBER, 5L, new Date()).validTo().after(new Date()), "驱动给 LocalDateTime 时覆盖段仍可用");
    }
}
