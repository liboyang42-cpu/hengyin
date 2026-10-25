package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.CoopSettlement;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CoopComplaintMapper;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IPricingService;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.business.service.IUmsMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺陷⑨ 结算必须按【每个商家自己的实际核销数】分账,而不是全主题人头。
 *
 * 病根:verification_status 是报名级布尔——任一中标商家扫一次码就把整个报名置 1
 * (ApiRegistrationController:1109),而 settleCoopTopic 让每个 coop_order 都用
 * 全主题的 verifiedSales/verifiedHeads ⇒ 没接待过玩家的商家照样满额拿钱。
 *
 * 两种模式都有:城市定向靠「玩家走完所有节点」这个假设,弃玩即失效(概率触发);
 * 自由探索下玩家本就只去所选那家(必然触发)。
 *
 * 数据本来就有,只是没人读:CmsRegistrationScan 里 registration_type=1 的那批
 * 记的就是真正扫码的商家(:1119-1130)。
 * WP04：显式计划替身下的五分项组合测试；不证明模拟的 CLUB/团路径已开放现金结算。
 */
class CoopSettlementPerMerchantTest {

    private static final Long TOPIC = 700L;
    private static final Long PUBLISHER = 900001L;
    private static final Long MERCHANT_A = 900002L;
    private static final Long MERCHANT_B = 900003L;
    private static final Long MERCHANT_C = 900004L;

    private CmsRegistrationMapper cmsRegistrationMapper;
    private CoopOrderMapper coopOrderMapper;
    private CoopSettlementMapper coopSettlementMapper;
    private IClubService clubService;
    private CmsTopicServiceImpl svc;

    @BeforeEach
    void setUp() {
        cmsRegistrationMapper = mock(CmsRegistrationMapper.class);
        coopOrderMapper = mock(CoopOrderMapper.class);
        coopSettlementMapper = mock(CoopSettlementMapper.class);
        when(coopSettlementMapper.insertCoopSettlement(any())).thenReturn(1);
        clubService = mock(IClubService.class);
        IPricingService pricingService = new PricingServiceImpl(); // 真实保底率/平台率(纯计算)
        CoopComplaintMapper coopComplaintMapper = mock(CoopComplaintMapper.class);
        CoopInviteMapper coopInviteMapper = mock(CoopInviteMapper.class);
        when(coopComplaintMapper.countOpenByTopicId(any())).thenReturn(0);
        when(coopSettlementMapper.countByOrderId(any())).thenReturn(0);
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(Collections.<CoopInvite>emptyList());

        svc = new CmsTopicServiceImpl();

        org.springframework.test.util.ReflectionTestUtils.setField(svc, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(svc, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(svc, "cmsRegistrationMapper", cmsRegistrationMapper);
        ReflectionTestUtils.setField(svc, "coopOrderMapper", coopOrderMapper);
        ReflectionTestUtils.setField(svc, "coopSettlementMapper", coopSettlementMapper);
        PreparedCoopPlanFixture.install(svc, cmsRegistrationMapper, coopOrderMapper, null, coopSettlementMapper);
        ReflectionTestUtils.setField(svc, "pricingService", pricingService);
        ReflectionTestUtils.setField(svc, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(svc, "umsMemberBalanceDetailService", mock(IUmsMemberBalanceDetailService.class));
        ReflectionTestUtils.setField(svc, "clubService", clubService);
        ReflectionTestUtils.setField(svc, "coopComplaintMapper", coopComplaintMapper);
        ReflectionTestUtils.setField(svc, "coopInviteMapper", coopInviteMapper);
        ReflectionTestUtils.setField(svc, "coopInviteService", mock(ICoopInviteService.class));
    }

    private CmsTopic topic() {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setMemberId(PUBLISHER);
        return t;
    }

    private CoopOrder merchantOrder(Long id, Long merchantId, int shareMode, String rate, String fee) {
        CoopOrder o = new CoopOrder();
        o.setId(id);
        o.setToType("merchant");
        o.setToId(merchantId); // toType=merchant 时 toId 就是商家 member_id,与 scan.member_id 同一人
        o.setShareMode(shareMode);
        o.setShareRate(rate == null ? null : new BigDecimal(rate));
        o.setFixedFee(fee == null ? null : new BigDecimal(fee));
        return o;
    }

    /** 全主题口径:100 人核销、销售额 5000。 */
    private void topicWideVerified() {
        when(cmsRegistrationMapper.selectVerifiedHeadsByTopicId(TOPIC)).thenReturn(100);
        when(cmsRegistrationMapper.selectVerifiedSalesByTopicId(TOPIC)).thenReturn(new BigDecimal("5000"));
        when(cmsRegistrationMapper.selectVerifiedRegistrationIdsByTopicId(TOPIC))
                .thenReturn(Collections.singletonList(1L));
    }

    /** 商家级口径:默认谁都没接待过,再按需给某家开数。 */
    private void merchantVerified(Long merchantId, int heads, String sales) {
        when(cmsRegistrationMapper.selectVerifiedHeadsByTopicAndMerchant(TOPIC, merchantId)).thenReturn(heads);
        when(cmsRegistrationMapper.selectVerifiedSalesByTopicAndMerchant(TOPIC, merchantId))
                .thenReturn(new BigDecimal(sales));
    }

    private Map<String, List<CoopSettlement>> capturedLines() {
        ArgumentCaptor<CoopSettlement> cap = ArgumentCaptor.forClass(CoopSettlement.class);
        org.mockito.Mockito.verify(coopSettlementMapper, atLeast(1)).insertCoopSettlement(cap.capture());
        Map<String, List<CoopSettlement>> m = new HashMap<>();
        for (CoopSettlement s : cap.getAllValues()) {
            m.computeIfAbsent(s.getLineType(), k -> new ArrayList<>()).add(s);
        }
        return m;
    }

    private CoopSettlement lineOf(Map<String, List<CoopSettlement>> lines, String type, Long payee) {
        for (CoopSettlement s : lines.getOrDefault(type, Collections.emptyList())) {
            if (payee.equals(s.getMerchantId())) return s;
        }
        throw new AssertionError("没有 " + type + " 行给 " + payee + ",实际:" + lines);
    }

    private void assertAmount(String expected, CoopSettlement s, String msg) {
        assertEquals(0, new BigDecimal(expected).compareTo(s.getAmount()), msg + "(实际 " + s.getAmount() + ")");
    }

    @Test
    void declaredCompositionScopeCannotTurnAnUnvisitedMerchantIntoPositivePayable() {
        topicWideVerified(); merchantVerified(MERCHANT_A, 100, "5000"); merchantVerified(MERCHANT_B, 0, "0");
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC)).thenReturn(Arrays.asList(
                merchantOrder(1L, MERCHANT_A, 2, null, "20"), merchantOrder(2L, MERCHANT_B, 2, null, "20")));
        PreparedCoopPlanFixture.topic(TOPIC);
        var claims = (com.chengyinhub.business.service.ICoopSettlementClaimService)
                ReflectionTestUtils.getField(svc, "coopSettlementClaimService");
        var plans = coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC).stream()
                .map(order -> claims.prepare(order, new java.util.Date())).toList();
        com.chengyinhub.business.service.support.CoopSettlementContract.requireIndependentOrderScopes(plans);
        assertEquals(0, new BigDecimal("2000").compareTo(plans.get(0).getOriginAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(plans.get(1).getOriginAmount()));
        assertEquals(0, plans.get(1).getVerifiedHeads());
        assertEquals(0, BigDecimal.ZERO.compareTo(plans.get(1).getEligibleSales()));
        for (var plan : plans) for (var line : plan.getOriginLines()) {
            assertEquals(Long.valueOf(1), line.getRegistrationId());
            com.chengyinhub.business.service.support.CoopSettlementContract.requireHeaderEquality(plan.getSnapshot(), line);
        }
        verify(coopSettlementMapper, never()).insertCoopSettlement(any());
    }

    /**
     * ★ 端到端断言(自由探索常态):3 家中标固定型 ¥20/人头,100 人全在 A 处核销。
     * 旧口径:三家各按全主题 100 人头拿 2000 → 商家合计 6000 > 销售额-平台-保底
     * → 发起人毛利 -2250 → assertSettlementConservation 拒结 → 款项悬空(文档 §2⑨ 金额示例)。
     */
    @Test
    void eachMerchantPaidOnlyForOwnVerifiedHeads_fixedFee() {
        topicWideVerified();
        merchantVerified(MERCHANT_A, 100, "5000"); // 只有 A 接待了玩家
        merchantVerified(MERCHANT_B, 0, "0");
        merchantVerified(MERCHANT_C, 0, "0");
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC)).thenReturn(Arrays.asList(
                merchantOrder(1L, MERCHANT_A, 2, null, "20"),
                merchantOrder(2L, MERCHANT_B, 2, null, "20"),
                merchantOrder(3L, MERCHANT_C, 2, null, "20")));

        PreparedCoopPlanFixture.topic(TOPIC);

        PreparedCoopPlanFixture.settle(svc, topic(), new BigDecimal("5000"));

        Map<String, List<CoopSettlement>> lines = capturedLines();
        assertAmount("2000.00", lineOf(lines, "MERCHANT_VERIFY", MERCHANT_A), "A 接待 100 人 → 20×100");
        assertAmount("0.00", lineOf(lines, "MERCHANT_VERIFY", MERCHANT_B), "B 没接待过任何人 → 0");
        assertAmount("0.00", lineOf(lines, "MERCHANT_VERIFY", MERCHANT_C), "C 没接待过任何人 → 0");

        // 保底 +2n% 只认「有真实核销」的商家:1 家 → 5%+2%=7% → 5000×7%=350
        assertAmount("350.00", lineOf(lines, "INITIATOR_GUARANTEE", PUBLISHER), "保底率按有核销商家数=1");
        assertAmount("500.00", lines.get("PLATFORM").get(0), "平台 5000×10%"); // 平台行无受款人
        // 毛利残差 5000-500-350-2000 = 2150(旧口径下这里是负数,直接抛异常拒结)
        assertAmount("2150.00", lineOf(lines, "INITIATOR_MARGIN", PUBLISHER), "毛利=残差,不再被顶穿");

        BigDecimal sum = lines.get("PLATFORM").get(0).getAmount()
                .add(lineOf(lines, "INITIATOR_GUARANTEE", PUBLISHER).getAmount())
                .add(lineOf(lines, "MERCHANT_VERIFY", MERCHANT_A).getAmount())
                .add(lineOf(lines, "MERCHANT_VERIFY", MERCHANT_B).getAmount())
                .add(lineOf(lines, "MERCHANT_VERIFY", MERCHANT_C).getAmount())
                .add(lineOf(lines, "INITIATOR_MARGIN", PUBLISHER).getAmount());
        assertEquals(0, new BigDecimal("5000").compareTo(sum), "五分项守恒==总销售额");
    }

    /** 分成型同理:每家按自己接待到的销售额 × 自己的费率,而不是全主题销售额。 */
    @Test
    void eachMerchantPaidOnlyForOwnVerifiedSales_shareRate() {
        topicWideVerified();
        merchantVerified(MERCHANT_A, 80, "4000");
        merchantVerified(MERCHANT_B, 20, "1000");
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC)).thenReturn(Arrays.asList(
                merchantOrder(1L, MERCHANT_A, 1, "30", null),
                merchantOrder(2L, MERCHANT_B, 1, "30", null)));

        PreparedCoopPlanFixture.topic(TOPIC);

        PreparedCoopPlanFixture.settle(svc, topic(), new BigDecimal("5000"));

        Map<String, List<CoopSettlement>> lines = capturedLines();
        assertAmount("1200.00", lineOf(lines, "MERCHANT_VERIFY", MERCHANT_A), "A:自己的 4000×30%,不是全主题 5000×30%");
        assertAmount("300.00", lineOf(lines, "MERCHANT_VERIFY", MERCHANT_B), "B:自己的 1000×30%");
    }

    /**
     * 城市定向弃玩(文档 §2⑨ 表格第 2 行):玩家走到第 1 家就弃玩。
     * 旧口径 A-E 各拿 1 人头;正确的是只有 A 拿。
     */
    @Test
    void cityDirectedDropout_onlyVisitedMerchantPaid() {
        when(cmsRegistrationMapper.selectVerifiedHeadsByTopicId(TOPIC)).thenReturn(1);
        when(cmsRegistrationMapper.selectVerifiedSalesByTopicId(TOPIC)).thenReturn(new BigDecimal("50"));
        when(cmsRegistrationMapper.selectVerifiedRegistrationIdsByTopicId(TOPIC))
                .thenReturn(Collections.singletonList(1L));
        merchantVerified(MERCHANT_A, 1, "50");
        merchantVerified(MERCHANT_B, 0, "0");
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC)).thenReturn(Arrays.asList(
                merchantOrder(1L, MERCHANT_A, 2, null, "5"),
                merchantOrder(2L, MERCHANT_B, 2, null, "5")));

        PreparedCoopPlanFixture.topic(TOPIC);

        PreparedCoopPlanFixture.settle(svc, topic(), new BigDecimal("50"));

        Map<String, List<CoopSettlement>> lines = capturedLines();
        assertAmount("5.00", lineOf(lines, "MERCHANT_VERIFY", MERCHANT_A), "玩家只到过 A");
        assertAmount("0.00", lineOf(lines, "MERCHANT_VERIFY", MERCHANT_B), "B 没接待,弃玩不该照付");
    }

    /**
     * 俱乐部带队(LEAD_FEE)不是「接待玩家的商家」,它带的是整趟、从不扫码核销,
     * 所以必须保持全主题口径 —— 若也换成「自己的核销数」会恒得 0,那是反向资损。
     */
    @Test
    void clubLeadFeeStillUsesTopicWideVerified() {
        topicWideVerified();
        Club club = new Club();
        club.setId(55L);
        club.setMemberId(900009L);
        when(clubService.selectClubById(55L)).thenReturn(club);

        CoopOrder clubOrder = new CoopOrder();
        clubOrder.setId(1L);
        clubOrder.setToType("club");
        clubOrder.setToId(55L);
        clubOrder.setShareMode(2);
        clubOrder.setFixedFee(new BigDecimal("10"));
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC))
                .thenReturn(Collections.singletonList(clubOrder));

        PreparedCoopPlanFixture.topic(TOPIC);

        PreparedCoopPlanFixture.settle(svc, topic(), new BigDecimal("5000"));

        Map<String, List<CoopSettlement>> lines = capturedLines();
        assertAmount("1000.00", lineOf(lines, "LEAD_FEE", 900009L), "俱乐部带队按全主题 100 人头 × ¥10");
        // 俱乐部不该被按「扫码商家」口径查
        org.mockito.Mockito.verify(cmsRegistrationMapper, org.mockito.Mockito.never())
                .selectVerifiedHeadsByTopicAndMerchant(eq(TOPIC), eq(55L));
    }

    /** 结算行快照要跟金额同口径,否则对账时「heads=100 但只拿 0」对不上。 */
    @Test
    void merchantLineSnapshotsOwnVerifiedNumbers() {
        topicWideVerified();
        merchantVerified(MERCHANT_A, 100, "5000");
        merchantVerified(MERCHANT_B, 0, "0");
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC)).thenReturn(Arrays.asList(
                merchantOrder(1L, MERCHANT_A, 2, null, "20"),
                merchantOrder(2L, MERCHANT_B, 2, null, "20")));

        PreparedCoopPlanFixture.topic(TOPIC);

        PreparedCoopPlanFixture.settle(svc, topic(), new BigDecimal("5000"));

        Map<String, List<CoopSettlement>> lines = capturedLines();
        assertEquals(Integer.valueOf(100), lineOf(lines, "MERCHANT_VERIFY", MERCHANT_A).getVerifiedHeads(),
                "A 的结算行快照记自己的 100");
        assertEquals(Integer.valueOf(0), lineOf(lines, "MERCHANT_VERIFY", MERCHANT_B).getVerifiedHeads(),
                "B 的结算行快照记自己的 0,而不是全主题 100");
        assertEquals(0, new BigDecimal("0").compareTo(lineOf(lines, "MERCHANT_VERIFY", MERCHANT_B).getVerifiedSales()),
                "B 的销售额快照同样是自己的");
    }
}
