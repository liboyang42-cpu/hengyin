package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.CoopSettlement;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.CoopSettlementAdjustment;
import com.chengyinhub.business.domain.CoopSettlementDetail;
import com.chengyinhub.business.domain.SettlementClaim;
import com.chengyinhub.business.domain.dto.CoopSettlementCandidate;
import com.chengyinhub.business.domain.dto.CoopSettlementSnapshot;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.mapper.CoopSettlementOriginalMapper;
import com.chengyinhub.business.mapper.CoopSettlementAdjustmentMapper;
import com.chengyinhub.business.mapper.CoopSettlementDetailMapper;
import com.chengyinhub.business.mapper.SettlementClaimMapper;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
import static org.mockito.ArgumentMatchers.anyString;
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
 * WP04：显式计划替身下的五分项组合测试；不证明模拟的 CLUB/团路径已开放现金结算。
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
        PreparedCoopPlanFixture.install(svc, cmsRegistrationMapper, coopOrderMapper, null, coopSettlementMapper);
        completeLegacyCompositionHeaders();
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
        PlatformTransactionManager transactionManager = unitTransactions();
        ReflectionTestUtils.setField(svc, "transactionManager", transactionManager);
        ReflectionTestUtils.setField(svc, "coopSettlementOriginalMapper", mock(CoopSettlementOriginalMapper.class));
        ReflectionTestUtils.setField(svc, "coopSettlementAdjustmentMapper", mock(CoopSettlementAdjustmentMapper.class));
        when(coopSettlementMapper.insertCoopSettlement(any())).thenReturn(1);
        var noOriginalRefunds = (CoopSettlementOriginalMapper)
                ReflectionTestUtils.getField(svc, "coopSettlementOriginalMapper");
        when(noOriginalRefunds.selectApprovedRegistrationRefundIdsForOriginalCloseForUpdate(any())).thenReturn(List.of());
        when(noOriginalRefunds.selectAdjustmentsForSettlementForUpdate(any())).thenReturn(List.of());
    }

    /**
     * Preserve the old amount/payee combination scenarios, but supply actual Plan/Line DTOs
     * to the new cross-order validator. This is still an explicit composition fixture,
     * not evidence that the runtime frozen reader admits CLUB or unassigned scan ranges.
     */
    private void completeLegacyCompositionHeaders() {
        var delegate = (com.chengyinhub.business.service.ICoopSettlementClaimService)
                ReflectionTestUtils.getField(svc, "coopSettlementClaimService");
        var complete = mock(com.chengyinhub.business.service.ICoopSettlementClaimService.class,
                org.mockito.AdditionalAnswers.delegatesTo(delegate));
        org.mockito.Mockito.doAnswer(call -> {
            CoopOrder order = call.getArgument(0);
            Date observedAt = call.getArgument(1);
            var legacy = delegate.prepare(order, observedAt);
            var snapshot = new CoopSettlementSnapshot();
            org.springframework.beans.BeanUtils.copyProperties(legacy.getSnapshot(), snapshot);
            boolean club = "club".equals(order.getToType());
            snapshot.setProductType(1); snapshot.setTermsVersion("v1");
            snapshot.setCatalogFrozenAt(new Date(0)); snapshot.setSettlementSnapshotAt(observedAt);
            snapshot.setFulfillmentStartInclusive(new Date(0)); snapshot.setFulfillmentEndExclusive(new Date(1000000));
            snapshot.setCommercialContextType("TOPIC_EDITION"); snapshot.setCommercialContextId(snapshot.getTopicId());
            snapshot.setBeneficiaryRole(club ? "CLUB" : "MERCHANT"); snapshot.setBeneficiaryId(order.getToId());
            snapshot.setServiceUnit(club ? "ORGANIZER_LEAD" : "MERCHANT_PARTICIPATION");
            snapshot.setScopeType("TOPIC"); snapshot.setScopeId(snapshot.getTopicId());
            snapshot.setEligiblePurchaseKinds(List.of("GUIDED_TICKET"));
            snapshot.setFulfillmentEvidenceRule(club ? "COOP_GROUP_VERIFY" : "REGISTRATION_SCAN");
            snapshot.setCompensationMode(Integer.valueOf(0).equals(order.getShareMode()) ? "NONE"
                    : Integer.valueOf(2).equals(order.getShareMode()) ? "FIXED_PER_FULFILLMENT" : "SALES_PERCENT");
            snapshot.setShareRate(order.getShareRate()); snapshot.setFixedFee(order.getFixedFee());
            List<Long> ids = cmsRegistrationMapper.selectVerifiedRegistrationIdsByTopicId(snapshot.getTopicId());
            if (ids == null || ids.isEmpty()) {
                ids = new ArrayList<>();
                for (int i=0; i<legacy.getVerifiedHeads(); i++) ids.add(10000L+i);
            }
            List<CoopSettlementCandidate> candidates = new ArrayList<>();
            for (int i=0; i<ids.size(); i++) {
                var candidate = depositCandidate(snapshot.getTopicId(), ids.get(i));
                candidate.setEligibleSalesSnapshot(i==0 ? legacy.getEligibleSales() : BigDecimal.ZERO);
                // These old pure combination inputs explicitly include a simulated club plan.
                if (club) { candidate.setOrganizerScopeCovered(true); candidate.setVerificationTime(new Date(900000));
                    candidate.setGroupMemberVerifyTime(new Date(900000)); }
                candidates.add(candidate);
            }
            var lines = com.chengyinhub.business.service.support.CoopSettlementContract.originLines(
                    snapshot, candidates, snapshot.getFulfillmentEndExclusive());
            var plan = new com.chengyinhub.business.domain.dto.CoopSettlementPlan(snapshot,
                    "NONE".equals(snapshot.getCompensationMode()) ? "NONE" : "COOP_ORDER", lines);
            assertEquals(0, legacy.getOriginAmount().compareTo(plan.getOriginAmount()), "Preserve the original combination amount");
            return plan;
        }).when(complete).prepare(any(), any());
        ReflectionTestUtils.setField(svc, "coopSettlementClaimService", complete);
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
        PreparedCoopPlanFixture.topic(topicId);
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

        PreparedCoopPlanFixture.topic(topic.getId());

        PreparedCoopPlanFixture.settle(svc, topic, new BigDecimal("100"));

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
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(topic.getId())).thenReturn(topic);
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
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(topic.getId())).thenReturn(topic);
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
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(topic.getId())).thenReturn(topic);
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

        PreparedCoopPlanFixture.topic(topic.getId());

        PreparedCoopPlanFixture.settle(svc, topic, new BigDecimal("100"));

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

        PreparedCoopPlanFixture.topic(topic.getId());

        PreparedCoopPlanFixture.settle(svc, topic, new BigDecimal("100"));

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
    void settleCoopTopic_preservesClaimOriginUntilDepositAdjustmentExists() throws Exception {
        SetoffFixture fixture = new SetoffFixture(706L, order(1L, 900002L, 1, "50", null));
        CoopInvite due = unpaidInvite(fixture, 88L, 5, "50.00");
        fixture.rejectAdjustmentWrites = true;

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> svc.executeTopicSettlement());

        assertTrue(failure.getMessage().contains("LEDGER_CONFLICT"));
        assertAmount("50.00", fixture.orderHead(1L), "调整写失败不能把原始头改为净额");
        assertTrue(fixture.adjustments.isEmpty());
        assertEquals(Integer.valueOf(5), due.getDepositStatus());
        verify(coopInviteMapper, never()).updateDepositIfCurrent(any(), any(), any(), any(), any());
        verify(umsMemberService, never()).addBalance(any(), any());
        verify(pointsAwardService, never()).awardPublisherSettlement(any(), any());
        // Map storage does not prove rollback of the previously inserted gross head/details; G's MySQL test owns it.
    }

    @Test
    void settleCoopTopic_pendingPaymentRequiresReconciliationOutsideSettlement() {
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
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PreparedCoopPlanFixture.settle(svc, topic, new BigDecimal("100")));
        assertTrue(failure.getMessage().contains("DEPOSIT_PAYMENT_RECONCILIATION_REQUIRED"));
        verify(wechatPayService, never()).queryOrderStatus(any());
        verify(wechatPayService, never()).closeOrder(any());
        verify(coopSettlementMapper, never()).insertCoopSettlement(any());
        verify(coopInviteMapper, never()).updateDepositIfCurrent(any(), any(), any(), any(), any());
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

        PlatformTransactionManager manager = (PlatformTransactionManager)ReflectionTestUtils.getField(svc, "transactionManager");
        new TransactionTemplate(manager).executeWithoutResult(status ->
                ReflectionTestUtils.invokeMethod(svc, "applyUnpaidDepositSetoffs", topic,
                        new ArrayList<CoopSettlement>(), new Date(), new Date(), 0, BigDecimal.ZERO, ""));

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
                () -> PreparedCoopPlanFixture.settle(svc, topic, new BigDecimal("100")));
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

        PreparedCoopPlanFixture.topic(topic.getId());

        PreparedCoopPlanFixture.settle(svc, topic, new BigDecimal("100"));

        Map<String, List<CoopSettlement>> lines = capturedLines();
        assertEquals("10,11,12", lines.get("MERCHANT_VERIFY").get(0).getVerifiedRegIds(), "商家核销行附核销报名id快照");
    }

    @Test
    void settleCoopTopic_skipsAlreadySettledOrder_stillWritesInitiatorAndPlatform() {
        CmsTopic topic = new CmsTopic();
        topic.setId(704L); topic.setMemberId(900001L);
        when(cmsRegistrationMapper.selectVerifiedSalesByTopicId(704L)).thenReturn(new BigDecimal("100"));
        when(cmsRegistrationMapper.selectVerifiedHeadsByTopicId(704L)).thenReturn(2);
        // The original settled merchant contract is still prepared/validated before
        // its existing head suppresses a second write. Give it its original scope,
        // rather than an empty default plan that cannot prove the original identity.
        when(cmsRegistrationMapper.selectVerifiedRegistrationIdsByTopicId(704L)).thenReturn(List.of(10L, 11L));
        when(cmsRegistrationMapper.selectVerifiedSalesByTopicAndMerchant(704L, 900002L)).thenReturn(new BigDecimal("100"));
        when(cmsRegistrationMapper.selectVerifiedHeadsByTopicAndMerchant(704L, 900002L)).thenReturn(2);
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(704L)).thenReturn(
                Collections.singletonList(order(1L, 900002L, 1, "30", null)));
        when(coopSettlementMapper.countByOrderId(1L)).thenReturn(1); // 已挂单,商家行跳过

        PreparedCoopPlanFixture.topic(topic.getId());

        PreparedCoopPlanFixture.settle(svc, topic, new BigDecimal("100"));

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
        CmsTopic payoutTopic = new CmsTopic(); payoutTopic.setId(s.getTopicId());
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(s.getTopicId())).thenReturn(payoutTopic);
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
        CmsTopic payoutTopic = new CmsTopic(); payoutTopic.setId(s.getTopicId());
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(s.getTopicId())).thenReturn(payoutTopic);
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
        s.setTopicId(700L);
        s.setId(6L); s.setMerchantId(900003L); s.setAmount(BigDecimal.ZERO); s.setStatus(0);
        CmsTopic payoutTopic = new CmsTopic(); payoutTopic.setId(s.getTopicId());
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(s.getTopicId())).thenReturn(payoutTopic);
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
        orphan.setTopicId(700L);
        orphan.setId(11L); orphan.setMerchantId(900404L); orphan.setAmount(new BigDecimal("30")); orphan.setStatus(0);
        CoopSettlement ok = new CoopSettlement();
        ok.setTopicId(700L);
        ok.setId(12L); ok.setMerchantId(900002L); ok.setAmount(new BigDecimal("30")); ok.setStatus(0);
        CmsTopic payoutTopic = new CmsTopic(); payoutTopic.setId(700L);
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(700L)).thenReturn(payoutTopic);
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
    @Test
    void settleCoopTopic_deductsUnpaidDepositFromActualPayersReceivable() throws Exception {
        SetoffFixture fixture = new SetoffFixture(706L, order(1L, 900002L, 1, "50", null));
        CoopInvite due = unpaidInvite(fixture, 88L, 5, "50.00");

        assertEquals(1, svc.executeTopicSettlement());

        Map<String,List<CoopSettlement>> lines = capturedLines();
        assertClaimDepositSetoff(fixture, due, 1L, "50.00", "50.00", "0.00");
        assertLineTotal("50.00", lines.get("DEPOSIT_DEDUCTION"), "抵扣额单列平台入账以便对账");
        assertAmount("33.00", lines.get("INITIATOR_MARGIN").get(0), "非应缴方发起人收益不得被错扣");
        assertEquals(Integer.valueOf(3), due.getDepositStatus());
        verify(coopInviteMapper).updateDepositIfCurrent(eq(88L), eq(5), eq(new BigDecimal("50.00")), eq(3), eq(new BigDecimal("50.00")));
        verify(wechatPayService, never()).queryOrderStatus(any());
        verify(umsMemberService, never()).addBalance(any(), any());
    }

    @Test
    void settleCoopTopic_resolvesPaymentInProgressBeforeDeducting() throws Exception {
        SetoffFixture fixture = new SetoffFixture(707L, order(1L, 900002L, 1, "50", null));
        CoopInvite paying = unpaidInvite(fixture, 89L, 1, "50.00");
        closedOriginalAttempt(paying, true, "CLOSED");

        assertEquals(1, svc.executeTopicSettlement());

        assertClaimDepositSetoff(fixture, paying, 1L, "50.00", "50.00", "0.00");
        Map<String,List<CoopSettlement>> lines = capturedLines();
        assertLineTotal("50.00", lines.get("DEPOSIT_DEDUCTION"), "原 attempt 确认关闭后才抵扣");
        InOrder paymentOrder = inOrder(wechatPayService, coopInviteMapper);
        paymentOrder.verify(wechatPayService).queryOrderStatus("DEP89T100");
        paymentOrder.verify(wechatPayService).closePaymentAttempt("DEP89T100", "WXP-deposit-89");
        paymentOrder.verify(wechatPayService).queryOrderStatus("WXP-deposit-89");
        paymentOrder.verify(wechatPayService).lockClosablePaymentAttemptForUpdate("DEP89T100", "WXP-deposit-89");
        paymentOrder.verify(coopInviteMapper).updateDepositIfCurrent(eq(89L), eq(1), any(), eq(5), any());
        verify(wechatPayService, never()).closeOrder(any());
        verify(coopInviteMapper).updateDepositIfCurrent(eq(89L), eq(1), any(), eq(5), any());
        verify(coopInviteMapper).updateDepositIfCurrent(eq(89L), eq(5), any(), eq(3), any());
    }

    @Test
    void settleCoopTopic_knownDepositWithoutPayerReceivableKeepsDebtAndOtherPayeeGross() throws Exception {
        SetoffFixture fixture = new SetoffFixture(711L, order(1L, 900003L, 1, "30", null));
        CoopInvite due = unpaidInvite(fixture, 93L, 5, "50.00");

        assertEquals(1, svc.executeTopicSettlement());

        assertAmount("30.00", fixture.orderHead(1L), "不得拿其他受款人应收抵缴保证金");
        assertEquals(0, new BigDecimal("30.00").compareTo(fixture.net(fixture.orderHead(1L))));
        assertTrue(fixture.adjustments.isEmpty());
        assertEquals(Integer.valueOf(5), due.getDepositStatus());
        assertEquals(0, new BigDecimal("50.00").compareTo(due.getDepositAmount()));
        assertTrue(!capturedLines().containsKey("DEPOSIT_DEDUCTION"));
        verify(coopInviteMapper, never()).updateDepositIfCurrent(eq(93L), any(), any(), eq(3), any());
    }

    @Test
    void settleCoopTopic_insufficientPayerReceivableCollectsOnlyAvailableAndKeepsRemainder() throws Exception {
        SetoffFixture fixture = new SetoffFixture(712L, order(1L, 900002L, 1, "30", null));
        CoopInvite due = unpaidInvite(fixture, 94L, 5, "50.00");

        assertEquals(1, svc.executeTopicSettlement());

        assertClaimDepositSetoff(fixture, due, 1L, "30.00", "30.00", "0.00");
        assertLineTotal("30.00", capturedLines().get("DEPOSIT_DEDUCTION"), "不足50仅收可用30");
        assertEquals(Integer.valueOf(5), due.getDepositStatus());
        assertEquals(0, new BigDecimal("20.00").compareTo(due.getDepositAmount()));
        verify(coopInviteMapper).updateDepositIfCurrent(eq(94L), eq(5), eq(new BigDecimal("50.00")), eq(5), eq(new BigDecimal("20.00")));
        verify(coopInviteMapper, never()).updateDepositIfCurrent(eq(94L), any(), any(), eq(3), any());
    }

    @Test
    void settleCoopTopic_repeatedCloseAndOriginalAdjustmentEventNeverDeductAgain() throws Exception {
        SetoffFixture fixture = new SetoffFixture(713L, order(1L, 900002L, 1, "50", null));
        CoopInvite due = unpaidInvite(fixture, 95L, 5, "50.00");
        assertEquals(1, svc.executeTopicSettlement());
        var originalEvents = new ArrayList<>(fixture.adjustments.values());
        assertTrue(!originalEvents.isEmpty());
        int originalHeads = fixture.heads.size();

        assertEquals(1, svc.executeTopicSettlement());
        for (var event : originalEvents) fixture.repeatAdjustment(event);

        assertEquals(originalHeads, fixture.heads.size());
        assertEquals(originalEvents.size(), fixture.adjustments.size());
        for (var event : originalEvents) assertTrue(fixture.adjustments.values().stream().anyMatch(current ->
                current.getClaimId().equals(event.getClaimId()) && current.getEventKey().equals(event.getEventKey())
                        && current.getAmount().compareTo(event.getAmount()) == 0));
        assertClaimDepositSetoff(fixture, due, 1L, "50.00", "50.00", "0.00");
        verify(coopInviteMapper, times(1)).updateDepositIfCurrent(eq(95L), eq(5), any(), eq(3), any());
    }

    @Test
    void settleCoopTopic_closeAckWithoutOriginalClosedFactBlocksAllEconomicWrites() throws Exception {
        SetoffFixture fixture = new SetoffFixture(714L, order(1L, 900002L, 1, "50", null));
        CoopInvite paying = unpaidInvite(fixture, 96L, 1, "50.00");
        closedOriginalAttempt(paying, true, "NOTPAY");

        assertThrows(IllegalStateException.class, () -> svc.executeTopicSettlement());

        assertTrue(fixture.heads.isEmpty()); assertTrue(fixture.details.isEmpty()); assertTrue(fixture.adjustments.isEmpty());
        assertEquals(Integer.valueOf(1), paying.getDepositStatus());
        verify(wechatPayService).queryOrderStatus("WXP-deposit-96");
        verify(coopInviteMapper, never()).updateDepositIfCurrent(any(), any(), any(), any(), any());
        verify(pointsAwardService, never()).awardPublisherSettlement(any(), any());
    }

    @Test
    void settleCoopTopic_unknownOriginalPaymentBlocksSetoffWithoutNewAttempt() throws Exception {
        SetoffFixture fixture = new SetoffFixture(715L, order(1L, 900002L, 1, "50", null));
        CoopInvite paying = unpaidInvite(fixture, 97L, 1, "50.00");
        when(wechatPayService.queryOrderStatus("DEP97T100"))
                .thenReturn(depositObservation("DEP97T100", "WXP-deposit-97", "UNKNOWN"));

        assertThrows(IllegalStateException.class, () -> svc.executeTopicSettlement());

        assertTrue(fixture.heads.isEmpty()); assertTrue(fixture.details.isEmpty()); assertTrue(fixture.adjustments.isEmpty());
        assertEquals(Integer.valueOf(1), paying.getDepositStatus());
        verify(wechatPayService, never()).closePaymentAttempt(any(), any());
        verify(coopInviteMapper, never()).updateDepositIfCurrent(any(), any(), any(), any(), any());
        verify(coopSettlementMapper, never()).insertCoopSettlement(any());
    }

    @Test
    void settleCoopTopic_failedCloseAckWithConfirmedOriginalClosedFactCanDeduct() throws Exception {
        SetoffFixture fixture = new SetoffFixture(716L, order(1L, 900002L, 1, "50", null));
        CoopInvite paying = unpaidInvite(fixture, 98L, 1, "50.00");
        closedOriginalAttempt(paying, false, "CLOSED");

        assertEquals(1, svc.executeTopicSettlement());

        assertClaimDepositSetoff(fixture, paying, 1L, "50.00", "50.00", "0.00");
        verify(wechatPayService).queryOrderStatus("WXP-deposit-98");
        assertEquals(Integer.valueOf(3), paying.getDepositStatus());
    }

    @Test
    void settleCoopTopic_closeReadbackSuccessConsumesExactTypedReferenceAndNeverSetsOff() throws Exception {
        SetoffFixture fixture = new SetoffFixture(717L, order(1L, 900002L, 1, "50", null));
        CoopInvite paying = unpaidInvite(fixture, 99L, 1, "50.00");
        closedOriginalAttempt(paying, true, "SUCCESS");
        var reference = new WechatVerifiedPaymentFact.Reference(90099L, "a".repeat(64));
        Map<String,String> success = depositObservation("DEP99T100", "WXP-deposit-99", "SUCCESS");
        success.put("currency", "CNY"); success.put("total", "5000"); success.put("transaction_id", "WX-DEPOSIT-99");
        success.put("verified_fact_id", Long.toString(reference.id())); success.put("verified_fact_hash", reference.hash());
        when(wechatPayService.queryOrderStatus("WXP-deposit-99")).thenAnswer(call -> {
            assertTrue(!TransactionSynchronizationManager.isActualTransactionActive());
            return success;
        });
        when(wechatPayService.lockPaymentAttemptForUpdate("DEP99T100", "WXP-deposit-99")).thenAnswer(call -> {
            requireUnitTransaction(); return true;
        });
        when(coopInviteService.markDepositPaid("DEP99T100", 5000L, "WX-DEPOSIT-99", reference)).thenAnswer(call -> {
            requireUnitTransaction(); paying.setDepositStatus(2); paying.setDepositPaidTransactionId("WX-DEPOSIT-99"); return true;
        });

        assertEquals(1, svc.executeTopicSettlement());

        assertAmount("50.00", fixture.orderHead(1L), "已付保证金不再扣合作应收");
        assertEquals(0, new BigDecimal("50.00").compareTo(fixture.net(fixture.orderHead(1L))));
        assertTrue(fixture.adjustments.isEmpty()); assertEquals(Integer.valueOf(2), paying.getDepositStatus());
        assertTrue(!capturedLines().containsKey("DEPOSIT_DEDUCTION"));
        verify(coopInviteService).markDepositPaid("DEP99T100", 5000L, "WX-DEPOSIT-99", reference);
        verify(coopInviteService).requestTopicDepositRefunds(717L);
        verify(coopInviteMapper, never()).updateDepositIfCurrent(eq(99L), any(), any(), eq(5), any());
        verify(coopInviteMapper, never()).updateDepositIfCurrent(eq(99L), any(), any(), eq(3), any());
    }

    @Test
    void settleCoopTopic_closeReadbackSuccessWithoutReferenceKeepsPendingAndNeverSetsOff() throws Exception {
        SetoffFixture fixture = new SetoffFixture(717L, order(1L, 900002L, 1, "50", null));
        CoopInvite paying = unpaidInvite(fixture, 99L, 1, "50.00");
        closedOriginalAttempt(paying, true, "SUCCESS");
        Map<String, String> success = depositObservation("DEP99T100", "WXP-deposit-99", "SUCCESS");
        success.put("currency", "CNY"); success.put("total", "5000"); success.put("transaction_id", "WX-DEPOSIT-99");
        when(wechatPayService.queryOrderStatus("WXP-deposit-99")).thenAnswer(call -> {
            assertTrue(!TransactionSynchronizationManager.isActualTransactionActive()); return success;
        });
        IllegalStateException rejected = assertThrows(IllegalStateException.class, () -> svc.executeTopicSettlement());
        assertTrue(rejected.getMessage().contains("保证金原支付事实引用缺失"));
        assertEquals(Integer.valueOf(1), paying.getDepositStatus());
        assertEquals(0, new BigDecimal("50.00").compareTo(paying.getDepositAmount()));
        assertTrue(fixture.heads.isEmpty()); assertTrue(fixture.claimsByKey.isEmpty());
        assertTrue(fixture.details.isEmpty()); assertTrue(fixture.adjustments.isEmpty());
        verify(coopInviteService, never()).markDepositPaid(anyString(), any(), any(), any(WechatVerifiedPaymentFact.Reference.class));
        verify(coopInviteService, never()).requestTopicDepositRefunds(any());
        verify(coopInviteMapper, never()).updateDepositIfCurrent(eq(99L), any(), any(), any(), any());
        verify(coopSettlementMapper, never()).insertCoopSettlement(any());
    }

    @Test
    void unpaidDepositSetoff_usesOnlyPositiveNetOfEachOriginalOrder() throws Exception {
        CoopOrder first = order(1L, 900002L, 1, "30", null), second = order(2L, 900002L, 1, "30", null);
        SetoffFixture fixture = new SetoffFixture(718L, first, second);
        CoopInvite due = unpaidInvite(fixture, 100L, 5, "50.00");
        List<CoopSettlement> pending = new ArrayList<>();
        new TransactionTemplate(fixture.transactions).executeWithoutResult(status -> {
            for (CoopOrder order : List.of(first, second)) {
                CoopSettlement head = new CoopSettlement();
                head.setTopicId(718L); head.setCoopOrderId(order.getId()); head.setMerchantId(900002L);
                head.setLineType("MERCHANT_VERIFY"); head.setPayeeType("merchant"); head.setStatus(0); head.setCashHeadKind("MEMBER"); head.setDelFlag(0);
                fixture.claimService.persist(fixture.claimService.prepare(order, new Date()), head);
                pending.add(head);
            }
            CoopSettlement firstHead = fixture.orderHead(1L);
            for (var detail : fixture.details.values())
                if (firstHead.getId().equals(detail.getSettlementId()))
                    fixture.claimService.appendExecutedAdjustment(detail.getClaimId(), "UNIT_PRIOR_ADJUSTMENT",
                            detail.getOriginAmount().negate());
            ReflectionTestUtils.invokeMethod(svc, "applyUnpaidDepositSetoffs", fixture.topic,
                    pending, new Date(), new Date(), 2, new BigDecimal("100"), "10,11,20,21");
        });

        assertAmount("30.00", fixture.orderHead(1L), "已调整订单毛额不变");
        assertAmount("30.00", fixture.orderHead(2L), "可用订单毛额不变");
        assertEquals(0, fixture.net(fixture.orderHead(1L)).compareTo(BigDecimal.ZERO));
        assertEquals(0, fixture.net(fixture.orderHead(2L)).compareTo(BigDecimal.ZERO));
        assertEquals(Integer.valueOf(5), due.getDepositStatus());
        assertEquals(0, new BigDecimal("20.00").compareTo(due.getDepositAmount()));
        assertLineTotal("30.00", pending.stream().filter(h -> "DEPOSIT_DEDUCTION".equals(h.getLineType())).toList(),
                "可用净额0+30只收30，不能再次抵扣已归零订单");
        verify(coopInviteMapper).updateDepositIfCurrent(eq(100L), eq(5), eq(new BigDecimal("50.00")), eq(5), eq(new BigDecimal("20.00")));
        for (var adjustment : fixture.adjustments.values()) {
            var detail = fixture.details.get(adjustment.getClaimId());
            assertEquals(detail.getSettlementId(), adjustment.getSettlementId());
            if (detail.getSettlementId().equals(fixture.orderHead(1L).getId()))
                assertEquals("UNIT_PRIOR_ADJUSTMENT", adjustment.getEventKey(), "已归零的原订单不能追加第二次保证金扣减");
        }
    }

    private CoopInvite unpaidInvite(SetoffFixture fixture, long inviteId, int state, String amount) {
        CoopInvite invite = new CoopInvite(); invite.setId(inviteId); invite.setTopicId(fixture.topic.getId());
        invite.setStatus(1); invite.setInviteType(0); invite.setToType("merchant"); invite.setToId(900002L);
        invite.setDepositStatus(state); invite.setDepositAmount(new BigDecimal(amount));
        if (state == 1) invite.setDepositOutTradeNo("DEP" + inviteId + "T100");
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(List.of(invite));
        when(coopInviteMapper.selectAcceptedByTopicForUpdate(fixture.topic.getId())).thenReturn(List.of(invite));
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(inviteId)).thenReturn(invite);
        when(coopInviteService.depositOwedParty(invite)).thenReturn(900002L);
        when(coopInviteMapper.updateDepositIfCurrent(eq(inviteId), any(), any(), any(), any())).thenAnswer(call -> {
            requireUnitTransaction();
            if (!java.util.Objects.equals(invite.getDepositStatus(), call.getArgument(1))
                    || invite.getDepositAmount().compareTo(call.getArgument(2)) != 0) return 0;
            if (Integer.valueOf(5).equals(call.getArgument(1)))
                for (var adjustment : fixture.adjustments.values())
                    if (adjustment.getEventKey().startsWith("UNPAID_DEPOSIT:" + fixture.topic.getId() + ":" + inviteId + ":"))
                        org.junit.jupiter.api.Assertions.assertSame(fixture.adjustmentTransactions.get(adjustment.getId()),
                                fixture.transactionResource(), "欠款 CAS 必须与原负调整同事务");
            invite.setDepositStatus(call.getArgument(3)); invite.setDepositAmount(call.getArgument(4)); return 1;
        });
        return invite;
    }

    private Map<String,String> depositObservation(String business, String attempt, String state) {
        Map<String,String> observation = new HashMap<>();
        observation.put("out_trade_no", business); observation.put("wx_out_trade_no", attempt);
        observation.put("trade_state", state); return observation;
    }

    private void closedOriginalAttempt(CoopInvite invite, boolean closeAck, String terminalState) {
        String business = invite.getDepositOutTradeNo(), attempt = "WXP-deposit-" + invite.getId();
        Map<String,String> paying = depositObservation(business, attempt, "NOTPAY");
        Map<String,String> terminal = depositObservation(business, attempt, terminalState);
        when(wechatPayService.queryOrderStatus(business)).thenAnswer(call -> {
            assertTrue(!TransactionSynchronizationManager.isActualTransactionActive());
            return paying;
        });
        when(wechatPayService.closePaymentAttempt(business, attempt)).thenAnswer(call -> {
            assertTrue(!TransactionSynchronizationManager.isActualTransactionActive());
            return closeAck;
        });
        when(wechatPayService.queryOrderStatus(attempt)).thenAnswer(call -> {
            assertTrue(!TransactionSynchronizationManager.isActualTransactionActive());
            return terminal;
        });
        when(wechatPayService.lockClosablePaymentAttemptForUpdate(business, attempt)).thenAnswer(call -> {
            requireUnitTransaction(); return true;
        });
    }

    private void assertClaimDepositSetoff(SetoffFixture fixture, CoopInvite invite,
            long orderId, String gross, String deduction, String net) {
        CoopSettlement head = fixture.orderHead(orderId);
        assertAmount(gross, head, "原订单毛额头不可被保证金抵扣覆盖");
        assertEquals(Long.valueOf(orderId), head.getSourceContractId());
        assertEquals("COOP_ORDER", head.getSourceContractType());
        BigDecimal detailGross = fixture.details.values().stream().filter(d -> head.getId().equals(d.getSettlementId()))
                .map(CoopSettlementDetail::getOriginAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal(gross).compareTo(detailGross));
        BigDecimal signed = fixture.adjustments.values().stream().filter(a -> head.getId().equals(a.getSettlementId()))
                .map(CoopSettlementAdjustment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal(deduction).negate().compareTo(signed));
        assertEquals(0, new BigDecimal(net).compareTo(fixture.net(head)));
        for (var adjustment : fixture.adjustments.values()) {
            var detail = fixture.details.get(adjustment.getClaimId());
            assertTrue(detail != null && detail.getSettlementId().equals(adjustment.getSettlementId()));
            var claim = fixture.claimsByKey.values().stream().filter(c -> c.getId().equals(adjustment.getClaimId())).findFirst().orElseThrow();
            assertEquals(detail.getRegistrationId(), claim.getFulfillmentId());
            assertEquals("COOP_ORDER", claim.getSettlementRoute());
            assertEquals("COOP_ORDER", claim.getSourceContractType());
            assertEquals(fixture.heads.get(detail.getSettlementId()).getCoopOrderId(), claim.getSourceContractId());
            assertEquals("TOPIC_EDITION", claim.getCommercialContextType());
            assertEquals(fixture.topic.getId(), claim.getCommercialContextId());
            assertEquals(Long.valueOf(900002L), claim.getBeneficiaryId());
            assertEquals("ADJUSTMENT", adjustment.getEntryKind()); assertEquals("EXECUTED", adjustment.getStatus());
            assertTrue(adjustment.getAmount().signum() < 0);
            CoopSettlement originalHead = fixture.heads.get(detail.getSettlementId());
            assertEquals("UNPAID_DEPOSIT:" + fixture.topic.getId() + ":" + invite.getId() + ":"
                    + originalHead.getCoopOrderId() + ":" + originalHead.getId(), adjustment.getEventKey());
            var receipts = fixture.heads.values().stream().filter(receipt ->
                    "DEPOSIT_DEDUCTION".equals(receipt.getLineType()) && adjustment.getId().equals(receipt.getSourceContractId())).toList();
            assertEquals(1, receipts.size(), "每条负调整只生成一个保证金收款凭证");
            assertEquals("DEPOSIT_SETOFF", receipts.get(0).getSourceContractType());
            assertEquals("DEPOSIT_SETOFF", receipts.get(0).getSettlementRoute());
            assertEquals(0, adjustment.getAmount().negate().compareTo(receipts.get(0).getAmount()));
        }
        verify(coopSettlementMapper, never()).updateCoopSettlement(any());
    }

    private void assertLineTotal(String expected, List<CoopSettlement> lines, String message) {
        BigDecimal total = lines.stream().map(CoopSettlement::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal(expected).compareTo(total), message);
    }

    /**
     * Unit storage boundary only: real prepare/claim/persist/append code runs above these maps.
     * This does not simulate MySQL rollback, provider verification or concurrent locking.
     */
    final class SetoffFixture {
        final Map<Long, CoopSettlement> heads = new java.util.LinkedHashMap<>();
        final Map<String, SettlementClaim> claimsByKey = new java.util.LinkedHashMap<>();
        final Map<Long, CoopSettlementDetail> details = new java.util.LinkedHashMap<>();
        final Map<String, CoopSettlementAdjustment> adjustments = new java.util.LinkedHashMap<>();
        final Map<Long,Object> adjustmentTransactions = new HashMap<>();
        final CoopSettlementDetailMapper detailMapper =
                mock(CoopSettlementDetailMapper.class);
        final CoopSettlementAdjustmentMapper adjustmentMapper =
                mock(CoopSettlementAdjustmentMapper.class);
        final CoopSettlementClaimServiceImpl claimService;
        final Map<Long, CoopSettlementSnapshot> frozen = new HashMap<>();
        final Map<Long, CoopOrder> originalOrders = new java.util.LinkedHashMap<>();
        final Map<Long, List<CoopSettlementCandidate>> fulfillment = new HashMap<>();
        final CmsTopic topic;
        final PlatformTransactionManager transactions;
        long nextId = 1000L;
        boolean rejectAdjustmentWrites;

        SetoffFixture(long topicId, CoopOrder... orders) throws Exception {
            transactions = unitTransactions();
            topic = new CmsTopic(); topic.setId(topicId); topic.setMemberId(900001L); topic.setProductType(1);
            when(cmsTopicMapper.selectCmsTopicByIdForUpdate(topicId)).thenReturn(topic);
            when(cmsTopicMapper.selectTopicsForSettlement()).thenReturn(List.of(topic));
            when(cmsTopicMapper.claimTopicForSettlement(topicId)).thenReturn(1);
            when(cmsRegistrationMapper.selectTotalSalesByTopicId(topicId)).thenReturn(new BigDecimal("100"));
            when(cmsRegistrationMapper.selectVerifiedSalesByTopicId(topicId)).thenReturn(new BigDecimal("100"));
            when(cmsRegistrationMapper.selectVerifiedHeadsByTopicId(topicId)).thenReturn(2);
            everyMerchantServedEveryone(topicId, 2, "100", 900002L);
            when(coopOrderMapper.selectEffectiveOrdersByTopicId(topicId)).thenReturn(Arrays.asList(orders));
            SettlementRule rule = new SettlementRule(); rule.setMerchantRate(new BigDecimal("0.90"));
            rule.setCreatorRate(BigDecimal.ZERO);
            when(settlementRuleService.getActiveRule(0L, "topic")).thenReturn(rule);
            OriginalCloseRefundHistoryFixture.install(svc, topicId, List.of());
            var originalRows = mock(CoopSettlementOriginalMapper.class);
            when(originalRows.selectApprovedRegistrationRefundIdsForOriginalCloseForUpdate(topicId)).thenReturn(List.of());
            when(originalRows.selectOriginalDetailsForHeadForUpdate(any())).thenAnswer(call ->
                    details.values().stream().filter(d -> java.util.Objects.equals(d.getSettlementId(), call.getArgument(0))).toList());
            when(originalRows.selectAdjustmentsForSettlementForUpdate(any())).thenReturn(List.of());
            ReflectionTestUtils.setField(svc, "coopSettlementOriginalMapper", originalRows);
            ReflectionTestUtils.setField(svc, "transactionManager", transactions);
            ReflectionTestUtils.setField(svc, "coopSettlementAdjustmentMapper", adjustmentMapper);

            SettlementClaimMapper claimMapper =
                    mock(SettlementClaimMapper.class);
            when(claimMapper.insertClaim(any())).thenAnswer(call -> {
                requireUnitTransaction();
                SettlementClaim claim = call.getArgument(0);
                if (claimsByKey.containsKey(claim.economicKey()))
                    throw new org.springframework.dao.DuplicateKeyException("unit economic key");
                claim.setId(nextId++); claimsByKey.put(claim.economicKey(), claim); return 1;
            });
            when(claimMapper.selectByEconomicKeyForUpdate(any())).thenAnswer(call ->
                    claimsByKey.get(((SettlementClaim)call.getArgument(0)).economicKey()));
            when(coopSettlementMapper.insertCoopSettlement(any())).thenAnswer(call -> {
                requireUnitTransaction();
                CoopSettlement head = call.getArgument(0); head.setId(nextId++);
                if ("DEPOSIT_DEDUCTION".equals(head.getLineType()))
                    org.junit.jupiter.api.Assertions.assertSame(adjustmentTransactions.get(head.getSourceContractId()),
                            transactionResource(), "保证金正凭证与原负调整必须属于同一事务");
                heads.put(head.getId(), copyHead(head)); return 1;
            });
            when(coopSettlementMapper.selectByTopicId(topicId)).thenAnswer(call ->
                    heads.values().stream().filter(h -> topicId == h.getTopicId()).map(this::copyHead).toList());
            when(coopSettlementMapper.countByOrderId(any())).thenAnswer(call ->
                    (int)heads.values().stream().filter(h -> java.util.Objects.equals(h.getCoopOrderId(), call.getArgument(0))).count());
            when(coopSettlementMapper.selectByIdForUpdate(any())).thenAnswer(call -> copyHead(heads.get(call.getArgument(0))));
            when(coopSettlementMapper.selectByClaimId(any())).thenAnswer(call -> {
                var detail = details.get(call.getArgument(0));
                return detail == null ? null : copyHead(heads.get(detail.getSettlementId()));
            });
            when(detailMapper.insertIgnoreDetail(any())).thenAnswer(call -> {
                requireUnitTransaction();
                CoopSettlementDetail detail = call.getArgument(0);
                if (details.containsKey(detail.getClaimId())) return 0;
                detail.setId(nextId++); details.put(detail.getClaimId(), detail); return 1;
            });
            when(detailMapper.selectByClaimIdForUpdate(any())).thenAnswer(call -> details.get(call.getArgument(0)));
            when(detailMapper.refreshOriginAggregate(any())).thenAnswer(call -> {
                requireUnitTransaction();
                CoopSettlement head = heads.get(call.getArgument(0)); if (head == null) return 0;
                var rows = details.values().stream().filter(d -> head.getId().equals(d.getSettlementId())).toList();
                head.setAmount(rows.stream().map(CoopSettlementDetail::getOriginAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
                head.setVerifiedHeads((int)rows.stream().map(CoopSettlementDetail::getRegistrationId).distinct().count());
                return 1;
            });
            when(adjustmentMapper.insertIgnoreAdjustment(any())).thenAnswer(call -> {
                requireUnitTransaction();
                CoopSettlementAdjustment row = call.getArgument(0);
                String key = row.getClaimId() + "|" + row.getEntryKind() + "|" + row.getEventKey();
                if (rejectAdjustmentWrites || adjustments.containsKey(key)) return 0;
                row.setId(nextId++); adjustments.put(key, row);
                adjustmentTransactions.put(row.getId(), transactionResource()); return 1;
            });
            when(adjustmentMapper.selectByClaimEventForUpdate(any(), any(), any())).thenAnswer(call ->
                    adjustments.get(call.getArgument(0) + "|" + call.getArgument(1) + "|" + call.getArgument(2)));
            when(adjustmentMapper.selectExecutedBySettlementForUpdate(any())).thenAnswer(call ->
                    adjustments.values().stream().filter(a -> java.util.Objects.equals(a.getSettlementId(), call.getArgument(0))
                            && "EXECUTED".equals(a.getStatus())).toList());
            when(adjustmentMapper.sumExecutedBySettlement(any())).thenAnswer(call ->
                    adjustments.values().stream().filter(a -> java.util.Objects.equals(a.getSettlementId(), call.getArgument(0))
                            && "EXECUTED".equals(a.getStatus())).map(CoopSettlementAdjustment::getAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add));

            com.chengyinhub.business.service.ICoopOrderSettlementSnapshotReader snapshots =
                    mock(com.chengyinhub.business.service.ICoopOrderSettlementSnapshotReader.class);
            for (CoopOrder order : orders) {
                order.setTopicId(topicId); order.setStatus(0); order.setDelFlag(0); order.setToType("merchant");
                originalOrders.put(order.getId(), order);
                var snapshot = new CoopSettlementSnapshot();
                snapshot.setOrderId(order.getId()); snapshot.setTopicId(topicId); snapshot.setInitiatorMemberId(900001L);
                snapshot.setProductType(1); snapshot.setTermsVersion("v1"); snapshot.setCatalogFrozenAt(new Date(0));
                snapshot.setFulfillmentStartInclusive(new Date(0)); snapshot.setFulfillmentEndExclusive(new Date(1000000));
                snapshot.setCommercialContextType("TOPIC_EDITION"); snapshot.setCommercialContextId(topicId);
                snapshot.setBeneficiaryRole("MERCHANT"); snapshot.setBeneficiaryId(order.getToId());
                snapshot.setServiceUnit("MERCHANT_PARTICIPATION"); snapshot.setScopeType("TOPIC"); snapshot.setScopeId(topicId);
                snapshot.setEligiblePurchaseKinds(List.of("GUIDED_TICKET")); snapshot.setFulfillmentEvidenceRule("REGISTRATION_SCAN");
                snapshot.setCompensationMode(Integer.valueOf(0).equals(order.getShareMode()) ? "NONE"
                        : Integer.valueOf(2).equals(order.getShareMode()) ? "FIXED_PER_FULFILLMENT" : "SALES_PERCENT");
                snapshot.setShareRate(order.getShareRate()); snapshot.setFixedFee(order.getFixedFee());
                frozen.put(order.getId(), snapshot);
                fulfillment.put(order.getId(), List.of(depositCandidate(topicId, order.getId()*10),
                        depositCandidate(topicId, order.getId()*10+1)));
            }
            when(snapshots.read(any())).thenAnswer(call -> frozen.get(((CoopOrder)call.getArgument(0)).getId()));
            TopicSettlementEvidenceService evidence = mock(TopicSettlementEvidenceService.class);
            when(evidence.lockCandidates(any(), any())).thenAnswer(call -> {
                CoopSettlementSnapshot snapshot = call.getArgument(0);
                return fulfillment.get(snapshot.getOrderId());
            });
            claimService = new CoopSettlementClaimServiceImpl(snapshots, new SnapshotFulfillmentWindowResolver(), evidence,
                    new SettlementClaimServiceImpl(claimMapper), coopSettlementMapper, detailMapper, adjustmentMapper);
            ReflectionTestUtils.setField(claimService, "topicMapper", cmsTopicMapper);
            ReflectionTestUtils.setField(svc, "coopSettlementClaimService", claimService);
        }

        /** Shared unit assembly only; the production service and real claim writer decide admission. */
        int settle() { return svc.executeTopicSettlement(); }
        void scope(long orderId, List<CoopSettlementCandidate> candidates) {
            if (!frozen.containsKey(orderId)) throw new IllegalArgumentException("Unknown original order");
            fulfillment.put(orderId, List.copyOf(candidates));
        }

        void cashScope(List<CoopSettlementCandidate> originals) {
            List<com.chengyinhub.business.domain.CmsRegistration> current = new ArrayList<>();
            for (var source : originals) {
                var row = new com.chengyinhub.business.domain.CmsRegistration();
                row.setId(source.getRegistrationId()); row.setOwnerType(1L); row.setOwnerId(topic.getId());
                row.setMemberId(source.getRegistrationId()); row.setPaymentStatus(2); row.setRegistrationStatus(2);
                row.setVerificationStatus(1); row.setPayableAmount(source.getEligibleSalesSnapshot());
                current.add(row);
            }
            var mapper = (com.chengyinhub.business.mapper.CoopSettlementEvidenceMapper)
                    ReflectionTestUtils.getField(svc, "coopSettlementEvidenceMapper");
            when(mapper.selectTopicCashRowsForUpdate(topic.getId())).thenReturn(current);
            when(mapper.selectTopicActivityCashRowsForUpdate(topic.getId())).thenReturn(List.of());
        }
        /** Real frozen lineup/reader and evidence service; only mapper responses are controlled, not SQL evidence. */
        void currentFrozenScanSource(List<CoopSettlementCandidate> scanRows) {
            topic.setDelFlag(0); topic.setLifecycle(3); topic.setSellingStartedAt(new Date(1));
            topic.setFeePlatformRate(new BigDecimal("0.10")); topic.setFeeGuaranteeBaseRate(new BigDecimal("0.05"));
            topic.setFeeGuaranteeStepRate(new BigDecimal("0.02")); topic.setFeeGuaranteeMaxMerchant(5);
            when(cmsTopicMapper.selectCmsTopicById(topic.getId())).thenReturn(topic);
            for (var order : originalOrders.values()) {
                order.setFromId(topic.getMemberId()); order.setTermsVersion("v1"); order.setRoleSnapshot("SUPPLY");
                order.setScopeTypeSnapshot("TOPIC"); order.setScopeIdSnapshot(topic.getId());
                order.setEligiblePurchaseKindsSnapshot("GUIDED_TICKET"); order.setFulfillmentEvidenceRuleSnapshot("REGISTRATION_SCAN");
                Map<String,Object> terms = new HashMap<>(); terms.put("shareMode", order.getShareMode());
                terms.put("shareRate", order.getShareRate()); terms.put("fixedFee", order.getFixedFee());
                terms.put("compensationMode", frozen.get(order.getId()).getCompensationMode());
                order.setTermsSnapshot(com.alibaba.fastjson2.JSON.toJSONString(terms));
            }
            var tickets = mock(com.chengyinhub.business.mapper.OmsTicketMapper.class);
            var ticket = new com.chengyinhub.business.domain.OmsTicket(); ticket.setId(88001L);
            ticket.setOwnerType(1L); ticket.setOwnerId(topic.getId()); ticket.setDelFlag(0); ticket.setMode(1); ticket.setSubType("guided");
            ticket.setStartTime(new Date(0)); ticket.setEndTime(new Date(1000000));
            when(tickets.selectOmsTicketList(any())).thenReturn(List.of(ticket));
            var reader = new FrozenCoopOrderSettlementSnapshotReader(
                    new com.chengyinhub.business.service.support.FailClosedCoopOrderLineupSource(coopOrderMapper, coopInviteMapper),
                    cmsTopicMapper, mock(com.chengyinhub.business.mapper.CmsTopicNodeMapper.class), tickets);
            var rows = (com.chengyinhub.business.mapper.CoopSettlementEvidenceMapper)
                    ReflectionTestUtils.getField(svc, "coopSettlementEvidenceMapper");
            when(rows.selectCashBasisBlockingRefundIdsForUpdate(topic.getId())).thenReturn(List.of());
            when(rows.selectTopicEvidenceCandidatesForUpdate(any(), any(), any())).thenAnswer(call -> {
                CoopSettlementSnapshot selected = call.getArgument(0);
                assertEquals("REGISTRATION_SCAN", selected.getFulfillmentEvidenceRule());
                assertEquals("TOPIC", selected.getScopeType()); assertEquals(topic.getId(), selected.getScopeId());
                // Current SQL has no original order discriminator for this merchant's scans.
                return List.copyOf(scanRows);
            });
            var evidence = new TopicSettlementEvidenceService(mock(com.chengyinhub.business.mapper.CmsActivityMapper.class),
                    cmsRegistrationMapper, mock(com.chengyinhub.business.mapper.CoopGroupVerifyMapper.class), rows);
            ReflectionTestUtils.setField(claimService, "snapshotReader", reader);
            ReflectionTestUtils.setField(claimService, "evidenceService", evidence);
            cashScope(scanRows);
        }

        /** Unit status double only. Real SQL rollback must be validated by G's storage suite. */
        void originalTopicClaim() {
            topic.setIsSettlement(0);
            when(cmsTopicMapper.claimTopicForSettlement(topic.getId())).thenAnswer(call -> {
                requireUnitTransaction();
                if (Integer.valueOf(1).equals(topic.getIsSettlement())) return 0;
                topic.setIsSettlement(1);
                TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override public void afterCompletion(int status) {
                                if (status != STATUS_COMMITTED) topic.setIsSettlement(0);
                            }
                        });
                return 1;
            });
        }
        void assertRejectedWithoutEconomicWrites() {
            assertEquals(Integer.valueOf(0), topic.getIsSettlement());
            assertTrue(heads.isEmpty()); assertTrue(claimsByKey.isEmpty());
            assertTrue(details.isEmpty()); assertTrue(adjustments.isEmpty());
            verify(coopSettlementMapper, never()).insertCoopSettlement(any());
            var producer = (CoopSettlementOriginalProducer)
                    ReflectionTestUtils.getField(svc, "coopSettlementOriginalProducer");
            verify(producer, never()).freezeOriginals(any(), any(), any());
            verify(pointsAwardService, never()).awardPublisherSettlement(any(), any());
            verify(umsMemberService, never()).addBalance(any(), any());
        }
        void assertOriginalCloseCount(int count) {
            var producer = (CoopSettlementOriginalProducer)
                    ReflectionTestUtils.getField(svc, "coopSettlementOriginalProducer");
            verify(producer, times(count)).freezeOriginals(eq(topic), any(), any());
            verify(pointsAwardService, times(count)).awardPublisherSettlement(topic.getMemberId(), topic.getId());
        }

        Object transactionResource() {
            requireUnitTransaction();
            var source = ((org.springframework.jdbc.datasource.DataSourceTransactionManager)transactions).getDataSource();
            Object resource = TransactionSynchronizationManager.getResource(source);
            assertTrue(resource != null, "actual Spring JDBC transaction resource required");
            return resource;
        }
        CoopSettlement copyHead(CoopSettlement source) {
            if (source == null) return null;
            CoopSettlement copy = new CoopSettlement(); org.springframework.beans.BeanUtils.copyProperties(source, copy); return copy;
        }
        CoopSettlement orderHead(long orderId) {
            return heads.values().stream().filter(h -> Long.valueOf(orderId).equals(h.getCoopOrderId())).findFirst().orElseThrow();
        }
        BigDecimal net(CoopSettlement head) {
            return new TransactionTemplate(transactions).execute(status ->
                    ReflectionTestUtils.invokeMethod(svc, "currentCoopHeadNet", copyHead(head)));
        }
        void repeatAdjustment(CoopSettlementAdjustment row) {
            new TransactionTemplate(transactions).executeWithoutResult(status ->
                    claimService.appendExecutedAdjustment(row.getClaimId(), row.getEventKey(), row.getAmount()));
        }
    }

    private static void requireUnitTransaction() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive(),
                "claim/detail/adjustment writes must participate in the same business transaction");
    }

    private static CoopSettlementCandidate depositCandidate(long topicId, long registrationId) {
        var row = new CoopSettlementCandidate();
        row.setRegistrationId(registrationId); row.setSourceTopicId(topicId); row.setOrderQuantity(1L);
        row.setTicketOwnerMatches(true); row.setFulfillmentStartInclusive(new Date(0));
        row.setFulfillmentEndExclusive(new Date(1000000)); row.setOwnerType(1L); row.setOwnerId(topicId);
        row.setRegistrationStatus(2); row.setPaymentStatus(2); row.setVerificationStatus(1);
        row.setPurchaseKind("GUIDED_TICKET"); row.setEligibleSalesSnapshot(new BigDecimal("50.00"));
        row.setScopeCovered(true); row.setMerchantScanPresent(true); row.setMerchantScanTime(new Date(900000));
        return row;
    }

    private static PlatformTransactionManager unitTransactions() {
        // G donor wiring: real Spring transaction synchronization, mocked JDBC only; no SQL rollback claim.
        var data = mock(javax.sql.DataSource.class);
        var connection = mock(java.sql.Connection.class);
        try {
            when(data.getConnection()).thenReturn(connection);
        } catch (java.sql.SQLException impossibleMockFailure) {
            throw new AssertionError("Unit connection fixture setup failed", impossibleMockFailure);
        }
        return new org.springframework.jdbc.datasource.DataSourceTransactionManager(data);
    }

}
