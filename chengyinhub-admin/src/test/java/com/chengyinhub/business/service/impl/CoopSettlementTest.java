package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.CoopSettlement;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopComplaintMapper;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IPricingService;
import com.chengyinhub.business.service.ISettlementRuleService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.business.service.impl.PricingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * coop 分项分账单测(平台/发起人保底/承接带队/商家核销/发起人毛利 五分项 + 守恒 + T+7 打款)。
 * 发起人保底/毛利改为 T+7 结算行(不再即时 payPublisher)。
 */
class CoopSettlementTest {

    private CmsRegistrationMapper cmsRegistrationMapper;
    private CmsTopicMapper cmsTopicMapper;
    private CoopOrderMapper coopOrderMapper;
    private CoopSettlementMapper coopSettlementMapper;
    private IPricingService pricingService;
    private IUmsMemberService umsMemberService;
    private UmsMemberMapper umsMemberMapper;
    private IUmsMemberBalanceDetailService balanceDetailService;
    private IClubService clubService;
    private CoopComplaintMapper coopComplaintMapper;
    private CoopInviteMapper coopInviteMapper;
    private ICoopInviteService coopInviteService;
    private IWechatPayService wechatPayService;
    private AlarmService alarmService;
    private IPointsAwardService pointsAwardService;
    private ISettlementRuleService settlementRuleService;
    private CmsTopicServiceImpl svc;

    private com.chengyinhub.business.service.support.SettlementDebtLedger debtLedger;

    @BeforeEach
    void setUp() {
        cmsRegistrationMapper = mock(CmsRegistrationMapper.class);
        cmsTopicMapper = mock(CmsTopicMapper.class);
        coopOrderMapper = mock(CoopOrderMapper.class);
        coopSettlementMapper = mock(CoopSettlementMapper.class);
        pricingService = new PricingServiceImpl(); // 用真实保底率/平台率(纯计算)
        umsMemberService = mock(IUmsMemberService.class);
        umsMemberMapper = mock(UmsMemberMapper.class);
        balanceDetailService = mock(IUmsMemberBalanceDetailService.class);
        clubService = mock(IClubService.class);
        coopComplaintMapper = mock(CoopComplaintMapper.class);
        coopInviteMapper = mock(CoopInviteMapper.class);
        coopInviteService = mock(ICoopInviteService.class);
        wechatPayService = mock(IWechatPayService.class);
        alarmService = mock(AlarmService.class);
        pointsAwardService = mock(IPointsAwardService.class);
        settlementRuleService = mock(ISettlementRuleService.class);
        when(coopComplaintMapper.countOpenByTopicId(any())).thenReturn(0); // 默认无客诉
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(Collections.<CoopInvite>emptyList());
        svc = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(svc, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(svc, "cmsRegistrationMapper", cmsRegistrationMapper);
        ReflectionTestUtils.setField(svc, "cmsTopicMapper", cmsTopicMapper);
        ReflectionTestUtils.setField(svc, "coopOrderMapper", coopOrderMapper);
        ReflectionTestUtils.setField(svc, "coopSettlementMapper", coopSettlementMapper);
        ReflectionTestUtils.setField(svc, "pricingService", pricingService);
        ReflectionTestUtils.setField(svc, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(svc, "umsMemberMapper", umsMemberMapper);
        ReflectionTestUtils.setField(svc, "umsMemberBalanceDetailService", balanceDetailService);
        ReflectionTestUtils.setField(svc, "clubService", clubService);
        ReflectionTestUtils.setField(svc, "coopComplaintMapper", coopComplaintMapper);
        debtLedger = org.mockito.Mockito.mock(com.chengyinhub.business.service.support.SettlementDebtLedger.class);
        ReflectionTestUtils.setField(svc, "settlementDebtLedger", debtLedger);
        ReflectionTestUtils.setField(svc, "coopInviteMapper", coopInviteMapper);
        ReflectionTestUtils.setField(svc, "coopInviteService", coopInviteService);
        ReflectionTestUtils.setField(svc, "wechatPayService", wechatPayService);
        ReflectionTestUtils.setField(svc, "alarmService", alarmService);
        ReflectionTestUtils.setField(svc, "pointsAwardService", pointsAwardService);
        ReflectionTestUtils.setField(svc, "settlementRuleService", settlementRuleService);
        ReflectionTestUtils.setField(svc, "notifyService", mock(INotifyService.class));
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        ReflectionTestUtils.setField(svc, "transactionManager", transactionManager);
        when(coopSettlementMapper.insertCoopSettlement(any())).thenReturn(1);
    }

    private CoopOrder order(Long id, Long toId, int shareMode, String rate, String fee) {
        CoopOrder o = new CoopOrder();
        o.setId(id); o.setToId(toId); o.setShareMode(shareMode);
        o.setShareRate(rate == null ? null : new BigDecimal(rate));
        o.setFixedFee(fee == null ? null : new BigDecimal(fee));
        return o;
    }

    /**
     * 「每个商家都接待了全部玩家」——城市定向下玩家走完所有节点的合法场景(见缺陷⑨ 口径表首行)。
     * 本文件测的是五分项拆分/守恒/受款人解析,与「谁接待了谁」无关,故显式把这个假设摆出来;
     * 商家各自核销数不同的场景由 CoopSettlementPerMerchantTest 覆盖。
     */
    private void everyMerchantServedEveryone(Long topicId, int heads, String sales, Long... merchantIds) {
        for (Long m : merchantIds) {
            when(cmsRegistrationMapper.selectVerifiedHeadsByTopicAndMerchant(topicId, m)).thenReturn(heads);
            when(cmsRegistrationMapper.selectVerifiedSalesByTopicAndMerchant(topicId, m))
                    .thenReturn(new BigDecimal(sales));
        }
    }

    /** 捕获落库结算行,按 line_type 分组。 */
    private Map<String, List<CoopSettlement>> capturedLines() {
        ArgumentCaptor<CoopSettlement> cap = ArgumentCaptor.forClass(CoopSettlement.class);
        verify(coopSettlementMapper, atLeast(1)).insertCoopSettlement(cap.capture());
        Map<String, List<CoopSettlement>> m = new HashMap<>();
        for (CoopSettlement s : cap.getAllValues()) {
            m.computeIfAbsent(s.getLineType(), k -> new ArrayList<>()).add(s);
        }
        return m;
    }

    private void assertAmount(String expected, CoopSettlement s, String msg) {
        assertEquals(0, new BigDecimal(expected).compareTo(s.getAmount()), msg);
    }

    @Test
    void settleCoopTopic_splitsFiveLineItems_conserves() {
        CmsTopic topic = new CmsTopic();
        topic.setId(700L); topic.setMemberId(900001L);
        when(cmsRegistrationMapper.selectVerifiedSalesByTopicId(700L)).thenReturn(new BigDecimal("100"));
        when(cmsRegistrationMapper.selectVerifiedHeadsByTopicId(700L)).thenReturn(2);
        everyMerchantServedEveryone(700L, 2, "100", 900002L);
        // 1 商家分成30% → 30(有核销 → verifiedMerchantCount=1 → 保底率 5%+2%=7%)
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(700L))
                .thenReturn(Collections.singletonList(order(1L, 900002L, 1, "30", null)));
        when(coopSettlementMapper.countByOrderId(any())).thenReturn(0);

        svc.settleCoopTopic(topic, new BigDecimal("100"));

        Map<String, List<CoopSettlement>> lines = capturedLines();
        assertAmount("10.00", lines.get("PLATFORM").get(0), "平台 100×10%=10");
        assertEquals(Integer.valueOf(1), lines.get("PLATFORM").get(0).getStatus(), "平台行不放款(status=1)");
        assertAmount("7.00", lines.get("INITIATOR_GUARANTEE").get(0), "保底 100×7%=7");
        assertEquals(Long.valueOf(900001L), lines.get("INITIATOR_GUARANTEE").get(0).getMerchantId(), "保底给发起人");
        assertAmount("30.00", lines.get("MERCHANT_VERIFY").get(0), "商家分成 100×30%=30");
        assertEquals(Long.valueOf(900002L), lines.get("MERCHANT_VERIFY").get(0).getMerchantId(), "商家核销费给商家");
        assertAmount("53.00", lines.get("INITIATOR_MARGIN").get(0), "毛利残差 100-10-7-30=53");
        assertEquals(Long.valueOf(900001L), lines.get("INITIATOR_MARGIN").get(0).getMerchantId(), "毛利给发起人");
        // 守恒:10+7+30+53=100
        assertEquals(0, new BigDecimal("100").compareTo(
                lines.get("PLATFORM").get(0).getAmount()
                        .add(lines.get("INITIATOR_GUARANTEE").get(0).getAmount())
                        .add(lines.get("MERCHANT_VERIFY").get(0).getAmount())
                        .add(lines.get("INITIATOR_MARGIN").get(0).getAmount())), "五分项守恒==100");
        // 发起人不再即时到账(改 T+7 结算行)
        verify(umsMemberService, times(0)).addBalance(any(), any());
    }

    @Test
    void settleTopic_coopWritesEveryPayableBeforeEvent15() {
        CmsTopic topic = new CmsTopic();
        topic.setId(700L);
        topic.setMemberId(900001L);
        topic.setProductType(1);
        when(cmsTopicMapper.claimTopicForSettlement(700L)).thenReturn(1);
        when(cmsRegistrationMapper.selectTotalSalesByTopicId(700L)).thenReturn(new BigDecimal("100"));
        when(cmsRegistrationMapper.selectVerifiedSalesByTopicId(700L)).thenReturn(new BigDecimal("100"));
        when(cmsRegistrationMapper.selectVerifiedHeadsByTopicId(700L)).thenReturn(2);
        everyMerchantServedEveryone(700L, 2, "100", 900002L);
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(700L))
                .thenReturn(Collections.singletonList(order(1L, 900002L, 1, "30", null)));
        when(coopSettlementMapper.countByOrderId(any())).thenReturn(0);

        ReflectionTestUtils.invokeMethod(svc, "settleTopic", topic,
                new com.chengyinhub.business.domain.SettlementRule());

        InOrder order = inOrder(coopSettlementMapper, pointsAwardService);
        order.verify(coopSettlementMapper, times(4)).insertCoopSettlement(any());
        order.verify(pointsAwardService).awardPublisherSettlement(900001L, 700L);
    }

    @Test
    void settleTopic_coopPayableInsertZeroFailsClosedBeforeEvent15() {
        CmsTopic topic = new CmsTopic();
        topic.setId(700L);
        topic.setMemberId(900001L);
        topic.setProductType(1);
        when(cmsTopicMapper.claimTopicForSettlement(700L)).thenReturn(1);
        when(cmsRegistrationMapper.selectTotalSalesByTopicId(700L)).thenReturn(new BigDecimal("100"));
        when(cmsRegistrationMapper.selectVerifiedSalesByTopicId(700L)).thenReturn(new BigDecimal("100"));
        when(cmsRegistrationMapper.selectVerifiedHeadsByTopicId(700L)).thenReturn(2);
        everyMerchantServedEveryone(700L, 2, "100", 900002L);
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(700L))
                .thenReturn(Collections.singletonList(order(1L, 900002L, 1, "30", null)));
        when(coopSettlementMapper.countByOrderId(any())).thenReturn(0);
        when(coopSettlementMapper.insertCoopSettlement(any())).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(
                svc, "settleTopic", topic, new com.chengyinhub.business.domain.SettlementRule()));

        verify(pointsAwardService, never()).awardPublisherSettlement(any(), any());
    }

    /**
     * H8 只裁决了发布者现金→积分,没裁决 coop 幂等语义。已挂过 PLATFORM 行的主题(①经典定向/②漫游
     * 同样走这里)必须维持既有的「topic 级去重跳过」,不能因为 H8 把旧玩法改成硬失败要人工对账。
     */
    @Test
    void executeTopicSettlement_existingPlatformLineSkipsRelistingWithoutFailing() {
        primeTopicForSettlementBatch();
        CoopSettlement platformOnly = new CoopSettlement();
        platformOnly.setLineType("PLATFORM");
        when(coopSettlementMapper.selectByTopicId(700L))
                .thenReturn(Collections.singletonList(platformOnly));

        assertEquals(1, svc.executeTopicSettlement(), "既有 PLATFORM 行 → 幂等跳过,不是硬失败");

        verify(coopSettlementMapper, never()).insertCoopSettlement(any());
        verify(pointsAwardService).awardPublisherSettlement(900001L, 700L);
    }

    /** 去重口径同样按既有语义:只有 PLATFORM 行才代表"本主题已分项结算过",别的行不拦。 */
    @Test
    void executeTopicSettlement_nonPlatformHistoryStillSettlesUnderExistingSemantics() {
        primeTopicForSettlementBatch();
        CoopSettlement merchantLine = new CoopSettlement();
        merchantLine.setLineType("MERCHANT_VERIFY");
        when(coopSettlementMapper.selectByTopicId(700L))
                .thenReturn(Collections.singletonList(merchantLine));

        assertEquals(1, svc.executeTopicSettlement());

        verify(coopSettlementMapper, times(4)).insertCoopSettlement(any());
        verify(pointsAwardService).awardPublisherSettlement(900001L, 700L);
    }

    /** 一个有生效 coop 单、有销售、有核销的主题批次,供公共入口 executeTopicSettlement 驱动。 */
    private void primeTopicForSettlementBatch() {
        CmsTopic topic = new CmsTopic();
        topic.setId(700L);
        topic.setMemberId(900001L);
        topic.setProductType(1);
        when(cmsTopicMapper.selectTopicsForSettlement())
                .thenReturn(Collections.singletonList(topic));
        when(cmsTopicMapper.claimTopicForSettlement(700L)).thenReturn(1);
        when(cmsRegistrationMapper.selectTotalSalesByTopicId(700L)).thenReturn(new BigDecimal("100"));
        when(cmsRegistrationMapper.selectVerifiedSalesByTopicId(700L)).thenReturn(new BigDecimal("100"));
        when(cmsRegistrationMapper.selectVerifiedHeadsByTopicId(700L)).thenReturn(2);
        everyMerchantServedEveryone(700L, 2, "100", 900002L);
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(700L))
                .thenReturn(Collections.singletonList(order(1L, 900002L, 1, "30", null)));
        when(coopSettlementMapper.countByOrderId(any())).thenReturn(0);
        SettlementRule topicRule = new SettlementRule();
        topicRule.setMerchantRate(new BigDecimal("0.90"));
        topicRule.setCreatorRate(BigDecimal.ZERO);
        when(settlementRuleService.getActiveRule(0L, "topic")).thenReturn(topicRule);
    }

    @Test
    void settleCoopTopic_clubInvitee_leadFeePayeeIsClubOwner() {
        CmsTopic topic = new CmsTopic();
        topic.setId(710L); topic.setMemberId(900001L);
        when(cmsRegistrationMapper.selectVerifiedSalesByTopicId(710L)).thenReturn(new BigDecimal("100"));
        when(cmsRegistrationMapper.selectVerifiedHeadsByTopicId(710L)).thenReturn(2);
        CoopOrder clubOrder = order(1L, 5000L, 1, "30", null);
        clubOrder.setToType("club");
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(710L)).thenReturn(Collections.singletonList(clubOrder));
        when(coopSettlementMapper.countByOrderId(any())).thenReturn(0);
        Club club = new Club(); club.setId(5000L); club.setMemberId(900009L);
        when(clubService.selectClubById(5000L)).thenReturn(club);

        svc.settleCoopTopic(topic, new BigDecimal("100"));

        Map<String, List<CoopSettlement>> lines = capturedLines();
        // 俱乐部承接 → LEAD_FEE 行,受款人=俱乐部主理人 member_id(非裸 club_id);修复前 club_id 打不出(资损)
        assertEquals(Long.valueOf(900009L), lines.get("LEAD_FEE").get(0).getMerchantId(),
                "toType=club → 带队费给俱乐部主理人 member_id");
        assertAmount("30.00", lines.get("LEAD_FEE").get(0), "带队费 100×30%=30");
        // 俱乐部不计入 +2%,保底率仅 5%
        assertAmount("5.00", lines.get("INITIATOR_GUARANTEE").get(0), "俱乐部不算被邀商家,保底仅5%");
    }

    @Test
    void settleCoopTopic_antiDilution_perMerchantIndependent() {
        CmsTopic topic = new CmsTopic();
        topic.setId(701L); topic.setMemberId(900001L);
        when(cmsRegistrationMapper.selectVerifiedSalesByTopicId(701L)).thenReturn(new BigDecimal("100"));
        when(cmsRegistrationMapper.selectVerifiedHeadsByTopicId(701L)).thenReturn(2);
        everyMerchantServedEveryone(701L, 2, "100", 900002L, 900003L);
        // 2 商家各分成20% → 各 20(互不稀释);verifiedMerchantCount=2 → 保底 5%+4%=9%
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(701L)).thenReturn(Arrays.asList(
                order(1L, 900002L, 1, "20", null),
                order(2L, 900003L, 1, "20", null)));
        when(coopSettlementMapper.countByOrderId(any())).thenReturn(0);

        svc.settleCoopTopic(topic, new BigDecimal("100"));

        Map<String, List<CoopSettlement>> lines = capturedLines();
        assertEquals(2, lines.get("MERCHANT_VERIFY").size(), "两商家各一行");
        for (CoopSettlement s : lines.get("MERCHANT_VERIFY")) {
            assertAmount("20.00", s, "每商家各结自己 20,加人不稀释");
        }
        assertAmount("9.00", lines.get("INITIATOR_GUARANTEE").get(0), "2 商家 → 保底 5%+2×2%=9%");
        // 守恒:10 + 9 + 40 + 41 = 100
        assertAmount("41.00", lines.get("INITIATOR_MARGIN").get(0), "毛利 100-10-9-40=41");
    }

    @Test
    void settleCoopTopic_deductsUnpaidDepositFromActualPayersReceivable() {
        CmsTopic topic = new CmsTopic();
        topic.setId(706L); topic.setMemberId(900001L);
        when(cmsRegistrationMapper.selectVerifiedSalesByTopicId(706L)).thenReturn(new BigDecimal("100"));
        when(cmsRegistrationMapper.selectVerifiedHeadsByTopicId(706L)).thenReturn(2);
        everyMerchantServedEveryone(706L, 2, "100", 900002L);
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(706L))
                .thenReturn(Collections.singletonList(order(1L, 900002L, 1, "50", null)));
        when(coopSettlementMapper.countByOrderId(any())).thenReturn(0);

        CoopInvite due = new CoopInvite();
        due.setId(88L); due.setTopicId(706L); due.setStatus(1); due.setInviteType(0);
        due.setToType("merchant"); due.setToId(900002L); due.setDepositStatus(5);
        due.setDepositAmount(new BigDecimal("50.00"));
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(Collections.singletonList(due));
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(88L)).thenReturn(due);
        when(coopInviteService.depositOwedParty(due)).thenReturn(900002L);
        when(coopInviteMapper.updateDepositIfCurrent(88L, 5, new BigDecimal("50.00"), 3, new BigDecimal("50.00")))
                .thenReturn(1);

        svc.settleCoopTopic(topic, new BigDecimal("100"));

        Map<String, List<CoopSettlement>> lines = capturedLines();
        assertAmount("0.00", lines.get("MERCHANT_VERIFY").get(0), "仅从应缴商家自己的应收抵扣");
        assertAmount("50.00", lines.get("DEPOSIT_DEDUCTION").get(0), "抵扣额单列平台入账以便对账");
        assertAmount("33.00", lines.get("INITIATOR_MARGIN").get(0), "非应缴方发起人收益不得被错扣");
        verify(coopInviteMapper).updateDepositIfCurrent(eq(88L), eq(5), eq(new BigDecimal("50.00")), eq(3), any());
    }

    @Test
    void settleCoopTopic_resolvesPaymentInProgressBeforeDeducting() {
        CmsTopic topic = new CmsTopic();
        topic.setId(707L); topic.setMemberId(900001L);
        when(cmsRegistrationMapper.selectVerifiedSalesByTopicId(707L)).thenReturn(new BigDecimal("100"));
        when(cmsRegistrationMapper.selectVerifiedHeadsByTopicId(707L)).thenReturn(2);
        everyMerchantServedEveryone(707L, 2, "100", 900002L);
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(707L))
                .thenReturn(Collections.singletonList(order(1L, 900002L, 1, "50", null)));
        when(coopSettlementMapper.countByOrderId(any())).thenReturn(0);

        CoopInvite pendingPayment = new CoopInvite();
        pendingPayment.setId(89L); pendingPayment.setTopicId(707L); pendingPayment.setStatus(1);
        pendingPayment.setDepositStatus(1); pendingPayment.setDepositOutTradeNo("DEP89T100");
        pendingPayment.setDepositAmount(new BigDecimal("50.00"));
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(Collections.singletonList(pendingPayment));
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(89L)).thenReturn(pendingPayment);
        when(coopInviteService.depositOwedParty(pendingPayment)).thenReturn(900002L);
        Map<String, String> notPaid = new HashMap<>();
        notPaid.put("trade_state", "NOTPAY");
        notPaid.put("out_trade_no", "DEP89T100"); // 查单回包必须回显原支付单号,否则按归属不匹配拒绝
        notPaid.put("wx_out_trade_no", "WXP-deposit-89");
        Map<String, String> closed = new HashMap<>(notPaid);
        closed.put("trade_state", "CLOSED");
        when(coopInviteMapper.updateDepositIfCurrent(89L, 1, new BigDecimal("50.00"), 5, new BigDecimal("50.00")))
                .thenReturn(1);
        when(coopInviteMapper.updateDepositIfCurrent(89L, 5, new BigDecimal("50.00"), 3, new BigDecimal("50.00")))
                .thenReturn(1);
        when(wechatPayService.queryOrderStatus("DEP89T100")).thenReturn(notPaid);
        when(wechatPayService.closePaymentAttempt("DEP89T100", "WXP-deposit-89")).thenReturn(true);
        when(wechatPayService.queryOrderStatus("WXP-deposit-89")).thenReturn(closed);
        when(wechatPayService.lockPaymentAttemptForUpdate("DEP89T100", "WXP-deposit-89")).thenReturn(true);

        svc.settleCoopTopic(topic, new BigDecimal("100"));

        Map<String, List<CoopSettlement>> lines = capturedLines();
        assertAmount("50.00", lines.get("DEPOSIT_DEDUCTION").get(0), "未支付订单关闭后才抵扣");
        InOrder paymentOrder = inOrder(wechatPayService, coopInviteMapper);
        paymentOrder.verify(wechatPayService).queryOrderStatus("DEP89T100");
        paymentOrder.verify(wechatPayService).closePaymentAttempt("DEP89T100", "WXP-deposit-89");
        paymentOrder.verify(wechatPayService).queryOrderStatus("WXP-deposit-89");
        paymentOrder.verify(wechatPayService).lockPaymentAttemptForUpdate("DEP89T100", "WXP-deposit-89");
        paymentOrder.verify(coopInviteMapper).updateDepositIfCurrent(eq(89L), eq(1), any(), eq(5), any());
        verify(wechatPayService, never()).closeOrder(any());
        verify(coopInviteMapper).updateDepositIfCurrent(eq(89L), eq(1), any(), eq(5), any());
        verify(coopInviteMapper).updateDepositIfCurrent(eq(89L), eq(5), any(), eq(3), any());
    }

    /**
     * d7593ff37 起结算不再自己同步调渠道退款:只登记持久退款意图(requestTopicDepositRefunds),
     * 失败保留冻结与告警的职责迁到 CoopDepositRefundService(见 CoopDepositRefundAlarmTest)。
     */
    @Test
    void completedDepositRefund_delegatesToDurableIntentWithoutDirectChannelCallOrRefundedWrite() {
        CoopInvite held = new CoopInvite();
        held.setId(90L); held.setTopicId(708L); held.setStatus(1); held.setDepositStatus(2);
        held.setDepositOutTradeNo("DEP90T100"); held.setDepositAmount(new BigDecimal("50.00"));
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(Collections.singletonList(held));

        ReflectionTestUtils.invokeMethod(svc, "refundTopicDeposits", 708L);

        verify(coopInviteService).requestTopicDepositRefunds(708L);
        verify(wechatPayService, never()).refund(any(), any(), any(), any(), any());
        verify(coopInviteMapper, never()).updateDeposit(eq(90L), eq(4), any(), any(), any());
    }

    @Test
    void completedDepositRefund_legacyType2IsReadOnly() {
        CoopInvite legacy = new CoopInvite();
        legacy.setId(91L); legacy.setInviteType(CoopInvite.TYPE_LEGACY_MERCHANT_NODE);
        legacy.setTopicId(709L); legacy.setStatus(1); legacy.setDepositStatus(2);
        legacy.setDepositOutTradeNo("DEP91T100"); legacy.setDepositAmount(new BigDecimal("50.00"));
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(Collections.singletonList(legacy));

        ReflectionTestUtils.invokeMethod(svc, "refundTopicDeposits", 709L);

        verify(wechatPayService, never()).refund(any(), any(), any(), any(), any());
        verify(coopInviteMapper, never()).updateDeposit(eq(91L), any(), any(), any(), any());
    }

    @Test
    void unpaidDepositSetoff_legacyType2IsReadOnly() {
        CmsTopic topic = new CmsTopic();
        topic.setId(710L);
        CoopInvite legacy = new CoopInvite();
        legacy.setId(92L); legacy.setInviteType(CoopInvite.TYPE_LEGACY_MERCHANT_NODE);
        legacy.setTopicId(710L); legacy.setStatus(1); legacy.setDepositStatus(5);
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(Collections.singletonList(legacy));

        ReflectionTestUtils.invokeMethod(svc, "applyUnpaidDepositSetoffs", topic,
                new ArrayList<CoopSettlement>(), new Date(), new Date(), 0, BigDecimal.ZERO, "");

        verify(coopInviteMapper, never()).selectCoopInviteByIdForUpdate(92L);
        verify(wechatPayService, never()).queryOrderStatus(any());
        verify(coopInviteMapper, never()).updateDeposit(eq(92L), any(), any(), any(), any());
    }

    @Test
    void settleCoopTopic_overDistribution_alertsAndWritesNothing() {
        CmsTopic topic = new CmsTopic();
        topic.setId(702L); topic.setMemberId(900001L);
        when(cmsRegistrationMapper.selectVerifiedSalesByTopicId(702L)).thenReturn(new BigDecimal("100"));
        when(cmsRegistrationMapper.selectVerifiedHeadsByTopicId(702L)).thenReturn(2);
        everyMerchantServedEveryone(702L, 2, "100", 900002L, 900003L);
        // 两商家各分成60% → 商家 120 > 100,平台10+保底9 → 毛利=100-10-9-120=-39 超拆
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(702L)).thenReturn(Arrays.asList(
                order(1L, 900002L, 1, "60", null),
                order(2L, 900003L, 1, "60", null)));
        when(coopSettlementMapper.countByOrderId(any())).thenReturn(0);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.settleCoopTopic(topic, new BigDecimal("100")));
        assertTrue(ex.getMessage().contains("超拆"), "越界应报超拆");
        // 守恒在动钱之前 → 一行没插
        verify(coopSettlementMapper, times(0)).insertCoopSettlement(any());
        verify(umsMemberService, times(0)).addBalance(any(), any());
    }

    @Test
    void settleCoopTopic_snapshotsVerifiedRegistrationIds_onOrderLine() {
        CmsTopic topic = new CmsTopic();
        topic.setId(703L); topic.setMemberId(900001L);
        when(cmsRegistrationMapper.selectVerifiedSalesByTopicId(703L)).thenReturn(new BigDecimal("100"));
        when(cmsRegistrationMapper.selectVerifiedHeadsByTopicId(703L)).thenReturn(2);
        when(cmsRegistrationMapper.selectVerifiedRegistrationIdsByTopicId(703L)).thenReturn(Arrays.asList(10L, 11L, 12L));
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(703L)).thenReturn(
                Collections.singletonList(order(1L, 900002L, 1, "30", null)));
        when(coopSettlementMapper.countByOrderId(any())).thenReturn(0);

        svc.settleCoopTopic(topic, new BigDecimal("100"));

        Map<String, List<CoopSettlement>> lines = capturedLines();
        assertEquals("10,11,12", lines.get("MERCHANT_VERIFY").get(0).getVerifiedRegIds(), "商家核销行附核销报名id快照");
    }

    @Test
    void settleCoopTopic_skipsAlreadySettledOrder_stillWritesInitiatorAndPlatform() {
        CmsTopic topic = new CmsTopic();
        topic.setId(704L); topic.setMemberId(900001L);
        when(cmsRegistrationMapper.selectVerifiedSalesByTopicId(704L)).thenReturn(new BigDecimal("100"));
        when(cmsRegistrationMapper.selectVerifiedHeadsByTopicId(704L)).thenReturn(2);
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(704L)).thenReturn(
                Collections.singletonList(order(1L, 900002L, 1, "30", null)));
        when(coopSettlementMapper.countByOrderId(1L)).thenReturn(1); // 已挂单,商家行跳过

        svc.settleCoopTopic(topic, new BigDecimal("100"));

        Map<String, List<CoopSettlement>> lines = capturedLines();
        // 商家行跳过(0),但平台/保底/毛利照挂;merchantTotal=0 → 保底5%、毛利=100-10-5-0=85
        assertTrue(!lines.containsKey("MERCHANT_VERIFY"), "已挂单商家行不重复");
        assertAmount("5.00", lines.get("INITIATOR_GUARANTEE").get(0), "无核销商家 → 保底5%");
        assertAmount("85.00", lines.get("INITIATOR_MARGIN").get(0), "毛利 100-10-5=85");
    }

    @Test
    void payoutDue_paysMerchantAndMarksPaid() {
        CoopSettlement s = new CoopSettlement();
        s.setId(5L); s.setMerchantId(900002L); s.setTopicId(700L); s.setAmount(new BigDecimal("30")); s.setStatus(0);
        when(coopSettlementMapper.selectDuePayouts(any())).thenReturn(Collections.singletonList(s));
        when(coopSettlementMapper.selectDuePayoutForUpdate(eq(5L), any())).thenReturn(s);
        when(coopSettlementMapper.markPaidIfPending(eq(5L), any())).thenReturn(1);
        UmsMember m = new UmsMember(); m.setId(900002L); m.setBalance(new BigDecimal("5"));
        UmsMember after = new UmsMember(); after.setId(900002L); after.setBalance(new BigDecimal("35"));
        when(umsMemberMapper.lockMemberById(900002L)).thenReturn(900002L);
        when(umsMemberMapper.selectUmsMemberById(900002L)).thenReturn(m, after);
        when(umsMemberMapper.addBalance(900002L, new BigDecimal("30"))).thenReturn(1);
        when(balanceDetailService.insertUmsMemberBalanceDetail(any())).thenReturn(1);

        int paid = svc.payoutDueCoopSettlements();

        assertEquals(1, paid);
        verify(umsMemberMapper).addBalance(eq(900002L), eq(new BigDecimal("30")));
        verify(coopSettlementMapper).markPaidIfPending(eq(5L), any());
        // 9-16 拍板第 2 条:结算入账先抵收款方欠款
        verify(debtLedger).offsetFromSettlement(900002L, new BigDecimal("30"), 5L);
    }

    @Test
    void payoutDue_frozenByOpenComplaint_skips() {
        CoopSettlement s = new CoopSettlement();
        s.setId(9L); s.setMerchantId(900002L); s.setTopicId(800L); s.setAmount(new BigDecimal("30")); s.setStatus(0);
        when(coopSettlementMapper.selectDuePayouts(any())).thenReturn(Collections.singletonList(s));
        when(coopSettlementMapper.selectDuePayoutForUpdate(eq(9L), any())).thenReturn(s);
        when(coopComplaintMapper.countOpenByTopicId(800L)).thenReturn(1); // 有未结客诉工单

        int paid = svc.payoutDueCoopSettlements();

        assertEquals(0, paid, "有未结客诉 → 冻结不放款");
        verify(umsMemberMapper, times(0)).addBalance(any(), any());
        verify(coopSettlementMapper, times(0)).markPaidIfPending(any(), any()); // 不改状态,工单关闭后下轮重试
    }

    @Test
    void payoutDue_zeroAmount_marksPaidWithoutBalance() {
        CoopSettlement s = new CoopSettlement();
        s.setId(6L); s.setMerchantId(900003L); s.setAmount(BigDecimal.ZERO); s.setStatus(0);
        when(coopSettlementMapper.selectDuePayouts(any())).thenReturn(Collections.singletonList(s));
        when(coopSettlementMapper.selectDuePayoutForUpdate(eq(6L), any())).thenReturn(s);
        when(coopSettlementMapper.markPaidIfPending(eq(6L), any())).thenReturn(1);

        int paid = svc.payoutDueCoopSettlements();

        assertEquals(0, paid, "0元不计入打款笔数");
        verify(umsMemberMapper, times(0)).addBalance(any(), any());
        verify(coopSettlementMapper).markPaidIfPending(eq(6L), any());
    }

    /** 1-04:受款人不存在的行不能静默跳过;其余到期行照常打款,末尾统一抛出让 Quartz 记 FAIL。 */
    @Test
    void payoutDue_missingPayee_continuesOthersThenThrows() {
        CoopSettlement orphan = new CoopSettlement();
        orphan.setId(11L); orphan.setMerchantId(900404L); orphan.setAmount(new BigDecimal("30")); orphan.setStatus(0);
        CoopSettlement ok = new CoopSettlement();
        ok.setId(12L); ok.setMerchantId(900002L); ok.setAmount(new BigDecimal("30")); ok.setStatus(0);
        when(coopSettlementMapper.selectDuePayouts(any())).thenReturn(java.util.Arrays.asList(orphan, ok));
        when(coopSettlementMapper.selectDuePayoutForUpdate(eq(11L), any())).thenReturn(orphan);
        when(coopSettlementMapper.selectDuePayoutForUpdate(eq(12L), any())).thenReturn(ok);
        when(coopSettlementMapper.markPaidIfPending(eq(12L), any())).thenReturn(1);
        when(umsMemberMapper.lockMemberById(900404L)).thenReturn(null); // 受款人已删
        UmsMember m = new UmsMember(); m.setId(900002L); m.setBalance(new BigDecimal("5"));
        UmsMember after = new UmsMember(); after.setId(900002L); after.setBalance(new BigDecimal("35"));
        when(umsMemberMapper.lockMemberById(900002L)).thenReturn(900002L);
        when(umsMemberMapper.selectUmsMemberById(900002L)).thenReturn(m, after);
        when(umsMemberMapper.addBalance(900002L, new BigDecimal("30"))).thenReturn(1);
        when(balanceDetailService.insertUmsMemberBalanceDetail(any())).thenReturn(1);

        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> svc.payoutDueCoopSettlements());

        assertTrue(e.getMessage().contains("settlementId=11"), e.getMessage());
        verify(coopSettlementMapper).markPaidIfPending(eq(12L), any());
        verify(coopSettlementMapper, times(0)).markPaidIfPending(eq(11L), any());
    }
}
