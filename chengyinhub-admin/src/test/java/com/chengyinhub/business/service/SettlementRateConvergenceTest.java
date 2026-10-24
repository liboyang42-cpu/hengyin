package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsSetting;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.TopicCreateDTO;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMerchantMapper;
import com.chengyinhub.business.mapper.CmsSettingMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.CoopSettlementEvidenceMapper;
import com.chengyinhub.business.mapper.SettlementDetailMapper;
import com.chengyinhub.business.service.impl.CmsActivityServiceImpl;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-28 抽成收敛行为测试:证明结算从 cms_setting(百分数)切到 settlement_rule(小数)真源,
 * 且入口方法 executeActivitySettlement / executeTopicSettlement 真的按新真源打款。
 *
 * <p>关键手法(见 handoff #5「闸放错层」):断言全部经由**入口方法**触发,不直接调 private 计算方法;
 * cms_setting 的费率列故意配成与 settlement_rule 不同的值,使「读错真源」立刻表现为打款金额错误。
 */
class SettlementRateConvergenceTest {

    // ---------------- 活动侧 ----------------

    private CmsActivityServiceImpl activitySvc;
    private CmsActivityMapper cmsActivityMapper;
    private CmsRegistrationMapper cmsRegistrationMapper;
    private CmsTopicMapper cmsTopicMapper;
    private CmsSettingMapper cmsSettingMapper; // 只为拿 platform_member_id
    private SettlementDetailMapper settlementDetailMapper;
    private IUmsMemberService umsMemberService;
    private IUmsMemberBalanceDetailService balanceDetailService;
    private ISettlementRuleService settlementRuleService;
    private IPointsAwardService pointsAwardService;
    private PlatformTransactionManager activityTransactionManager;

    @BeforeEach
    void setUp() {
        cmsActivityMapper = mock(CmsActivityMapper.class);
        cmsRegistrationMapper = mock(CmsRegistrationMapper.class);
        cmsTopicMapper = mock(CmsTopicMapper.class);
        cmsSettingMapper = mock(CmsSettingMapper.class);
        settlementDetailMapper = mock(SettlementDetailMapper.class);
        umsMemberService = mock(IUmsMemberService.class);
        settlementRuleService = mock(ISettlementRuleService.class);
        pointsAwardService = mock(IPointsAwardService.class);
        balanceDetailService = mock(IUmsMemberBalanceDetailService.class);
        activityTransactionManager = mock(PlatformTransactionManager.class);
        when(activityTransactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        activitySvc = new CmsActivityServiceImpl();
        ReflectionTestUtils.setField(activitySvc, "cmsActivityMapper", cmsActivityMapper);
        ReflectionTestUtils.setField(activitySvc, "cmsRegistrationMapper", cmsRegistrationMapper);
        ReflectionTestUtils.setField(activitySvc, "cmsTopicMapper", cmsTopicMapper);
        ReflectionTestUtils.setField(activitySvc, "cmsSettingMapper", cmsSettingMapper);
        ReflectionTestUtils.setField(activitySvc, "settlementDetailMapper", settlementDetailMapper);
        ReflectionTestUtils.setField(activitySvc, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(activitySvc, "umsMemberBalanceDetailService", balanceDetailService);
        ReflectionTestUtils.setField(activitySvc, "settlementRuleService", settlementRuleService);
        ReflectionTestUtils.setField(activitySvc, "pointsAwardService", pointsAwardService);
        ReflectionTestUtils.setField(activitySvc, "transactionManager", activityTransactionManager);
        ReflectionTestUtils.setField(activitySvc, "activitySettlementHoldMapper", org.mockito.Mockito.mock(com.chengyinhub.business.mapper.ActivitySettlementHoldMapper.class));
        ReflectionTestUtils.setField(activitySvc, "settlementDebtLedger", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.SettlementDebtLedger.class));

        // 任何会员都存在,余额从 0 起
        when(umsMemberService.selectUmsMemberById(anyLong())).thenAnswer(inv -> {
            UmsMember m = new UmsMember();
            m.setId(inv.getArgument(0));
            m.setBalance(BigDecimal.ZERO);
            m.setStatus(1L);
            m.setDelFlag(0);
            return m;
        });
        when(umsMemberService.lockMemberById(anyLong())).thenAnswer(inv -> inv.getArgument(0));
        when(umsMemberService.addBalance(anyLong(), any())).thenReturn(true);
        when(balanceDetailService.insertUmsMemberBalanceDetail(any())).thenReturn(1);
    }

    private SettlementRule rule(String platform, String merchant, String creator, String inviter) {
        SettlementRule r = new SettlementRule();
        r.setBizType("activity");
        r.setPlatformCommissionRate(new BigDecimal(platform));
        r.setMerchantRate(new BigDecimal(merchant));
        r.setCreatorRate(new BigDecimal(creator));
        r.setInviterRate(new BigDecimal(inviter));
        r.setStatus(1);
        r.setDelFlag(0);
        return r;
    }

    private CmsActivity primeActivity(BigDecimal totalSales, Long designerMemberId, Long platformMemberId) {
        CmsActivity a = new CmsActivity();
        a.setId(100L);
        a.setName("测试活动");
        a.setMemberId(9L);        // 商户
        a.setTopicId(50L);        // 有主题来源 → 设计者
        when(cmsActivityMapper.selectActivitiesForSettlement())
                .thenReturn(Collections.singletonList(a));
        when(cmsActivityMapper.claimActivityForSettlement(100L)).thenReturn(1);
        when(cmsRegistrationMapper.selectTotalSalesByActivityId(100L)).thenReturn(totalSales);

        CmsTopic srcTopic = new CmsTopic();
        srcTopic.setId(50L);
        srcTopic.setMemberId(designerMemberId);
        srcTopic.setProductType(ProductType.ORIENTEERING);
        when(cmsTopicMapper.selectCmsTopicById(50L)).thenReturn(srcTopic);

        CmsSetting s = new CmsSetting();
        s.setPlatformMemberId(platformMemberId);
        // ⚠️ 故意与 rule 不同:若代码读 cms_setting 就会算错
        s.setActivityPlatformCommission(new BigDecimal("10"));
        s.setActivityMerchantCommission(new BigDecimal("90"));
        s.setActivityDesignerCommission(new BigDecimal("0"));
        when(cmsSettingMapper.selectCmsSettingById(1L)).thenReturn(s);
        return a;
    }

    private Map<Long, BigDecimal> capturePayouts() {
        ArgumentCaptor<Long> idCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<BigDecimal> amtCap = ArgumentCaptor.forClass(BigDecimal.class);
        verify(umsMemberService, org.mockito.Mockito.atLeast(0)).addBalance(idCap.capture(), amtCap.capture());
        Map<Long, BigDecimal> byMember = new HashMap<>();
        List<Long> ids = idCap.getAllValues();
        List<BigDecimal> amts = amtCap.getAllValues();
        for (int i = 0; i < ids.size(); i++) {
            byMember.merge(ids.get(i), amts.get(i), BigDecimal::add);
        }
        return byMember;
    }

    @Test
    void activity_h8PaysMerchantAndPlatformThenAwardsPublisherWithoutDesignerCash() {
        when(settlementRuleService.getActiveRule(0L, "activity")).thenReturn(rule("0.10", "0.90", "0.00", "0.00"));
        primeActivity(new BigDecimal("1000"), 77L, 7L);

        int settled = activitySvc.executeActivitySettlement();

        assertEquals(1, settled);
        Map<Long, BigDecimal> pay = capturePayouts();
        assertEquals(0, new BigDecimal("900.00").compareTo(pay.getOrDefault(9L, BigDecimal.ZERO)),
                "creator 份额并入 merchant 后商户应拿 900");
        assertEquals(0, BigDecimal.ZERO.compareTo(pay.getOrDefault(77L, BigDecimal.ZERO)),
                "设计者不得再有现金入账");
        assertEquals(0, new BigDecimal("100.00").compareTo(pay.getOrDefault(7L, BigDecimal.ZERO)),
                "平台应按 rule 的 0.10 拿 100,打给 platform_member_id=7");
        InOrder order = inOrder(umsMemberService, settlementDetailMapper, pointsAwardService);
        order.verify(umsMemberService).addBalance(9L, new BigDecimal("900.00"));
        order.verify(umsMemberService).addBalance(7L, new BigDecimal("100.00"));
        order.verify(settlementDetailMapper).markActivityRegistrationDetailsSettled(100L);
        order.verify(pointsAwardService).awardPublisherSettlement(77L, 50L);
    }

    @Test
    void activity_h8SameMemberMayReceiveMerchantCashAndPublisherPointsButNoCreatorCash() {
        when(settlementRuleService.getActiveRule(0L, "activity"))
                .thenReturn(rule("0.10", "0.90", "0.00", "0.00"));
        primeActivity(new BigDecimal("1000"), 9L, 7L);

        assertEquals(1, activitySvc.executeActivitySettlement());

        verify(umsMemberService).addBalance(9L, new BigDecimal("900.00"));
        verify(pointsAwardService).awardPublisherSettlement(9L, 50L);
    }

    @Test
    void activity_nullProductTypeWithNoSalesClosesOnceWithoutCashOrPoints() {
        when(settlementRuleService.getActiveRule(0L, "activity"))
                .thenReturn(rule("0.10", "0.90", "0.00", "0.00"));
        primeActivity(BigDecimal.ZERO, 77L, 7L);
        CmsTopic legacy = new CmsTopic();
        legacy.setId(50L);
        legacy.setMemberId(77L);
        legacy.setProductType(null);
        when(cmsTopicMapper.selectCmsTopicById(50L)).thenReturn(legacy);

        assertEquals(1, activitySvc.executeActivitySettlement());

        verify(cmsActivityMapper).claimActivityForSettlement(100L);
        verify(umsMemberService, never()).addBalance(anyLong(), any());
        verify(pointsAwardService, never()).awardPublisherSettlement(anyLong(), anyLong());
    }

    @Test
    void activity_nullProductTypeWithSalesClaimsThenRollsBackWithoutCash() {
        when(settlementRuleService.getActiveRule(0L, "activity"))
                .thenReturn(rule("0.10", "0.90", "0.00", "0.00"));
        primeActivity(new BigDecimal("100"), 77L, 7L);
        CmsTopic legacy = new CmsTopic();
        legacy.setId(50L);
        legacy.setMemberId(77L);
        legacy.setProductType(null);
        when(cmsTopicMapper.selectCmsTopicById(50L)).thenReturn(legacy);

        assertThrows(IllegalStateException.class, activitySvc::executeActivitySettlement);

        verify(cmsActivityMapper).claimActivityForSettlement(100L);
        verify(activityTransactionManager).rollback(any());
        verify(umsMemberService, never()).addBalance(anyLong(), any());
    }

    /**
     * H8「每主题统一资格 100 积分、全链最多一次」在活动腿必须 fail-closed:现金已完成却不发积分,
     * 活动会被永久标记已结算,事后无法补发,唯一信号是一条日志。宁可整活动回滚重试。
     */
    @Test
    void activity_nullPublisherFailsClosedAndStaysRetryableWithoutPoints() {
        when(settlementRuleService.getActiveRule(0L, "activity"))
                .thenReturn(rule("0.10", "0.90", "0.00", "0.00"));
        CmsActivity activity = primeActivity(new BigDecimal("100"), null, 7L);

        ServiceException guard = assertThrows(ServiceException.class,
                () -> ReflectionTestUtils.invokeMethod(activitySvc, "settleActivity",
                        activity, cmsSettingMapper.selectCmsSettingById(1L),
                        rule("0.10", "0.90", "0.00", "0.00")));
        assertEquals("ACTIVITY_SOURCE_TOPIC_PUBLISHER_MISSING: activityId=100, topicId=50",
                guard.getMessage(), "必须由发布者缺失守卫抛错,不能由后续 NPE 假绿");

        assertThrows(IllegalStateException.class, activitySvc::executeActivitySettlement,
                "空发布者不得让活动完成结算");
        verify(activityTransactionManager).rollback(any());
        verify(pointsAwardService, never()).awardPublisherSettlement(anyLong(), anyLong());
    }

    @Test
    void activity_h8PointsFailureRollsBackClaimAndCashInsteadOfBeingSwallowed() {
        when(settlementRuleService.getActiveRule(0L, "activity"))
                .thenReturn(rule("0.10", "0.90", "0.00", "0.00"));
        primeActivity(new BigDecimal("1000"), 77L, 7L);
        when(pointsAwardService.awardPublisherSettlement(77L, 50L))
                .thenThrow(new ServiceException("points failed"));

        assertThrows(IllegalStateException.class, activitySvc::executeActivitySettlement);

        verify(activityTransactionManager).rollback(any());
        verify(pointsAwardService).awardPublisherSettlement(77L, 50L);
    }

    @Test
    void activity_missingMerchantCashReceiverRollsBackBeforeSettlementMarkAndPoints() {
        when(settlementRuleService.getActiveRule(0L, "activity"))
                .thenReturn(rule("0.10", "0.90", "0.00", "0.00"));
        primeActivity(new BigDecimal("1000"), 77L, 7L);
        when(umsMemberService.selectUmsMemberById(9L)).thenReturn(null);

        assertThrows(IllegalStateException.class, activitySvc::executeActivitySettlement);

        verify(activityTransactionManager).rollback(any());
        verify(settlementDetailMapper, never()).markActivityRegistrationDetailsSettled(anyLong());
        verify(pointsAwardService, never()).awardPublisherSettlement(anyLong(), anyLong());
    }

    @Test
    void activity_cashLedgerInsertFailureRollsBackBeforeSettlementMarkAndPoints() {
        when(settlementRuleService.getActiveRule(0L, "activity"))
                .thenReturn(rule("0.10", "0.90", "0.00", "0.00"));
        primeActivity(new BigDecimal("1000"), 77L, 7L);
        when(balanceDetailService.insertUmsMemberBalanceDetail(any())).thenReturn(0);

        assertThrows(IllegalStateException.class, activitySvc::executeActivitySettlement);

        verify(activityTransactionManager).rollback(any());
        verify(settlementDetailMapper, never()).markActivityRegistrationDetailsSettled(anyLong());
        verify(pointsAwardService, never()).awardPublisherSettlement(anyLong(), anyLong());
    }

    @Test
    void activity_reloadsPlatformAccountForEachActivityTransaction() {
        when(settlementRuleService.getActiveRule(0L, "activity")).thenReturn(rule("0.10", "0.90", "0.00", "0.00"));

        CmsActivity first = new CmsActivity();
        first.setId(100L);
        first.setName("第一场");
        first.setMemberId(9L);
        first.setTopicId(50L);
        CmsActivity second = new CmsActivity();
        second.setId(101L);
        second.setName("第二场");
        second.setMemberId(10L);
        second.setTopicId(51L);
        when(cmsActivityMapper.selectActivitiesForSettlement()).thenReturn(Arrays.asList(first, second));
        when(cmsActivityMapper.claimActivityForSettlement(anyLong())).thenReturn(1);
        when(cmsRegistrationMapper.selectTotalSalesByActivityId(100L)).thenReturn(new BigDecimal("100"));
        when(cmsRegistrationMapper.selectTotalSalesByActivityId(101L)).thenReturn(new BigDecimal("100"));

        CmsTopic firstTopic = new CmsTopic();
        firstTopic.setMemberId(77L);
        firstTopic.setProductType(ProductType.ORIENTEERING);
        CmsTopic secondTopic = new CmsTopic();
        secondTopic.setMemberId(78L);
        secondTopic.setProductType(ProductType.ORIENTEERING);
        when(cmsTopicMapper.selectCmsTopicById(50L)).thenReturn(firstTopic);
        when(cmsTopicMapper.selectCmsTopicById(51L)).thenReturn(secondTopic);

        CmsSetting firstSetting = new CmsSetting();
        firstSetting.setPlatformMemberId(7L);
        CmsSetting secondSetting = new CmsSetting();
        secondSetting.setPlatformMemberId(8L);
        when(cmsSettingMapper.selectCmsSettingById(1L)).thenReturn(firstSetting, secondSetting);

        assertEquals(2, activitySvc.executeActivitySettlement());
        Map<Long, BigDecimal> payouts = capturePayouts();
        assertEquals(0, new BigDecimal("10.00").compareTo(payouts.get(7L)), "第一场应使用首次读取的账户");
        assertEquals(0, new BigDecimal("10.00").compareTo(payouts.get(8L)), "第二场应重读并使用换绑后的账户");
        verify(cmsSettingMapper, times(2)).selectCmsSettingById(1L);
    }

    @Test
    void activity_ruleNull_refusesWholeBatch_noPayoutNoClaim() {
        // getActiveRule 返回 null(默认行被删/停用)→ 拒结整批,绝不「商家拿全额」
        when(settlementRuleService.getActiveRule(0L, "activity")).thenReturn(null);
        primeActivity(new BigDecimal("1000"), 77L, 7L);

        assertThrows(ServiceException.class, activitySvc::executeActivitySettlement, "rule 缺失必须拒结整批");

        verify(umsMemberService, never()).addBalance(anyLong(), any());
        verify(cmsActivityMapper, never()).claimActivityForSettlement(anyLong());
    }

    @Test
    void activity_ruleSumNotOne_refusesWholeBatch() {
        // 平台0.10 + 商户0.85 + creator 0 = 0.95 ≠ 1.0 → 拒结(防少分)
        when(settlementRuleService.getActiveRule(0L, "activity")).thenReturn(rule("0.10", "0.85", "0.00", "0.00"));
        primeActivity(new BigDecimal("1000"), 77L, 7L);

        assertThrows(ServiceException.class, activitySvc::executeActivitySettlement, "三方和≠100% 必须拒结整批");

        verify(umsMemberService, never()).addBalance(anyLong(), any());
    }

    @Test
    void activity_creatorRateNonZeroRefusesEvenWhenThreeRatesStillSumToOne() {
        when(settlementRuleService.getActiveRule(0L, "activity"))
                .thenReturn(rule("0.10", "0.80", "0.10", "0.00"));
        primeActivity(new BigDecimal("1000"), 77L, 7L);

        assertThrows(ServiceException.class, activitySvc::executeActivitySettlement);

        verify(cmsActivityMapper, never()).claimActivityForSettlement(anyLong());
        verify(umsMemberService, never()).addBalance(anyLong(), any());
        verify(pointsAwardService, never()).awardPublisherSettlement(anyLong(), anyLong());
    }

    @Test
    void h8AwardsOnlyPositiveSalesAndDoesNotUseActivityIdAsIdempotencyObject() {
        when(settlementRuleService.getActiveRule(0L, "activity"))
                .thenReturn(rule("0.10", "0.90", "0.00", "0.00"));
        primeActivity(new BigDecimal("1000"), 77L, 7L);
        when(cmsActivityMapper.claimActivityForSettlement(100L)).thenReturn(1, 0);

        assertEquals(1, activitySvc.executeActivitySettlement());
        BigDecimal activityPayout = capturePayouts().values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("1000.00").compareTo(activityPayout),
                "经典 product_type=1 活动壳必须按既有三档恰好派发票款 S");

        setUpTopic();
        when(topicRuleService.getActiveRule(0L, "topic"))
                .thenReturn(topicRule("0.03", "0.90", "0.00", "0.00"));
        primeTopic(BigDecimal.ZERO, 77L);
        when(topicMapper.claimTopicForSettlement(200L)).thenReturn(1, 0);

        assertEquals(1, topicSvc.executeTopicSettlement());
        verify(topicRegMapper).selectTotalSalesByTopicId(200L);
        verify(topicRegMapper, never())
                .selectTotalSalesEvidenceByTopicIdIncludingActivityShell(200L);
        verify(topicMember, never()).addBalance(anyLong(), any());
        verify(pointsAwardService).awardPublisherSettlement(77L, 50L);
        verify(pointsAwardService, never()).awardPublisherSettlement(77L, 200L);
        verify(pointsAwardService, never()).awardPublisherSettlement(77L, 100L);
        verify(cmsRegistrationMapper).selectTotalSalesByActivityId(100L);
        verify(topicEvidenceMapper).selectTopicCashRowsForUpdate(200L);
        assertEquals(0, activitySvc.executeActivitySettlement(), "活动重复 CAS 未领取，票款和积分均零新增");
        assertEquals(0, topicSvc.executeTopicSettlement(), "零销售主题重复 CAS 未领取，不能补发结算积分");
        BigDecimal afterReplay = capturePayouts().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, activityPayout.compareTo(afterReplay), "重跑后全部实际出票款仍恰好 ¥1000");
        verify(pointsAwardService, times(1)).awardPublisherSettlement(77L, 50L);
        verify(topicRegMapper, times(1)).selectTotalSalesByTopicId(200L);
        verify(topicEvidenceMapper, times(1)).selectTopicCashRowsForUpdate(200L);
        verify(topicMember, never()).addBalance(anyLong(), any());
    }

    @Test
    void activity_unusablePlatformAccountRefusesBeforeAnyPayoutOrSettlementMark() {
        when(settlementRuleService.getActiveRule(0L, "activity")).thenReturn(rule("0.10", "0.90", "0.00", "0.00"));
        primeActivity(new BigDecimal("1000"), 77L, 7L);
        UmsMember disabled = new UmsMember();
        disabled.setId(7L);
        disabled.setStatus(2L);
        disabled.setDelFlag(0);
        when(umsMemberService.selectUmsMemberById(7L)).thenReturn(disabled);

        assertThrows(IllegalStateException.class, activitySvc::executeActivitySettlement, "平台收款账户失效时不得把活动标记为已结算");

        verify(umsMemberService, never()).addBalance(anyLong(), any());
        verify(settlementDetailMapper, never()).markActivityRegistrationDetailsSettled(anyLong());
    }

    @Test
    void activity_platformAccountLockMissRefusesBeforeAnyPayoutOrSettlementMark() {
        when(settlementRuleService.getActiveRule(0L, "activity")).thenReturn(rule("0.10", "0.90", "0.00", "0.00"));
        primeActivity(new BigDecimal("1000"), 77L, 7L);
        when(umsMemberService.lockMemberById(7L)).thenReturn(null);

        assertThrows(IllegalStateException.class, activitySvc::executeActivitySettlement, "平台收款账户锁定失败时不得进入入账");

        verify(umsMemberService, never()).addBalance(anyLong(), any());
        verify(settlementDetailMapper, never()).markActivityRegistrationDetailsSettled(anyLong());
    }

    // ---------------- 主题侧 ----------------

    @Test
    void futureTopicCreationRejectsMissingPublisherBeforeWrites() {
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createTopic(new TopicCreateDTO(), null, "operator"));

        assertTrue(error.getMessage().contains("发布者会员ID"));
    }

    @Test
    void platformAdminTopicCreationUsesConfiguredPublisherMember() {
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        CmsTopicMapper mapper = mock(CmsTopicMapper.class);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", mapper);
        ReflectionTestUtils.setField(service, "productRoleService", mock(IProductRoleService.class));
        ReflectionTestUtils.setField(service, "platformMemberId", 100000L);
        CmsTopic topic = new CmsTopic();

        service.insertCmsTopic(topic);

        assertEquals(100000L, topic.getMemberId());
    }

    private CmsTopicServiceImpl topicSvc;
    private CmsTopicMapper topicMapper;
    private CmsRegistrationMapper topicRegMapper;
    private CmsRegistrationMerchantMapper merchantMapper;
    private CoopOrderMapper coopOrderMapper;
    private CoopSettlementEvidenceMapper topicEvidenceMapper;
    private IUmsMemberService topicMember;
    private ISettlementRuleService topicRuleService;
    private PlatformTransactionManager topicTransactionManager;

    private SettlementRule topicRule(String platform, String merchant, String creator, String inviter) {
        SettlementRule r = new SettlementRule();
        r.setBizType("topic");
        r.setPlatformCommissionRate(new BigDecimal(platform));
        r.setMerchantRate(new BigDecimal(merchant));
        r.setCreatorRate(new BigDecimal(creator));
        r.setInviterRate(new BigDecimal(inviter));
        r.setStatus(1);
        r.setDelFlag(0);
        return r;
    }

    private void setUpTopic() {
        topicMapper = mock(CmsTopicMapper.class);
        topicRegMapper = mock(CmsRegistrationMapper.class);
        merchantMapper = mock(CmsRegistrationMerchantMapper.class);
        coopOrderMapper = mock(CoopOrderMapper.class);
        topicEvidenceMapper = mock(CoopSettlementEvidenceMapper.class);
        // 主题票原合计为零，且没有该主题的已付活动壳资金；不能依赖 null/默认服务跳过证据闸。
        when(topicEvidenceMapper.countPaidActivityRegistrations(200L)).thenReturn(0);
        topicMember = mock(IUmsMemberService.class);
        topicRuleService = mock(ISettlementRuleService.class);

        topicSvc = new CmsTopicServiceImpl();

        org.springframework.test.util.ReflectionTestUtils.setField(topicSvc, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(topicSvc, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(topicSvc, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(topicSvc, "cmsRegistrationMapper", topicRegMapper);
        // cmsRegistrationMerchantMapper 已随 distributeMerchantCommissions 拆除(2026-07-17),
        // CmsTopicServiceImpl 上不再有这个字段。merchantMapper 仍留着是因为下面的用例要 stub 它
        // 来证明**没有人再去读商家表**。
        ReflectionTestUtils.setField(topicSvc, "coopOrderMapper", coopOrderMapper);
        ReflectionTestUtils.setField(topicSvc, "coopSettlementEvidenceMapper", topicEvidenceMapper);
        com.chengyinhub.business.service.impl.OriginalCloseRefundHistoryFixture.install(topicSvc, 200L, Collections.emptyList());
        ReflectionTestUtils.setField(topicSvc, "umsMemberService", topicMember);
        ReflectionTestUtils.setField(topicSvc, "umsMemberBalanceDetailService",
                mock(IUmsMemberBalanceDetailService.class));
        ReflectionTestUtils.setField(topicSvc, "settlementRuleService", topicRuleService);
        ReflectionTestUtils.setField(topicSvc, "pointsAwardService", pointsAwardService);
        // 无销售收口也要登记该主题的保证金退款意图(d7593ff37),本类不测保证金
        ReflectionTestUtils.setField(topicSvc, "coopInviteMapper", mock(com.chengyinhub.business.mapper.CoopInviteMapper.class));
        ReflectionTestUtils.setField(topicSvc, "coopInviteService", mock(ICoopInviteService.class));
        // A3-7:主题结算改「每主题独立事务 + CAS 原子领取」——注入 txm,并让领取默认成功(1),
        // 否则 settleTopic 入口即因抢不到而跳过、settledCount 恒 0。
        topicTransactionManager = mock(PlatformTransactionManager.class);
        when(topicTransactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        ReflectionTestUtils.setField(topicSvc, "transactionManager", topicTransactionManager);
        when(topicMapper.claimTopicForSettlement(anyLong())).thenReturn(1);

        when(topicMember.selectUmsMemberById(anyLong())).thenAnswer(inv -> {
            UmsMember m = new UmsMember();
            m.setId(inv.getArgument(0));
            m.setBalance(BigDecimal.ZERO);
            return m;
        });
    }

    private CmsTopic primeTopic(BigDecimal totalSales, Long publisherMemberId) {
        CmsTopic t = new CmsTopic();
        t.setId(200L);
        t.setName("测试主题");
        t.setMemberId(publisherMemberId); // 发布者
        t.setProductType(ProductType.ORIENTEERING);
        when(topicMapper.selectTopicsForSettlement()).thenReturn(Collections.singletonList(t));
        when(topicMapper.selectCmsTopicByIdForUpdate(t.getId())).thenReturn(t);
        when(topicRegMapper.selectTotalSalesByTopicId(200L)).thenReturn(totalSales);
        // 无 coop 单 → 走存量 else 分支
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(200L)).thenReturn(Collections.emptyList());
        return t;
    }

    private CmsRegistrationMerchant approvedMerchant(Long memberId, String sharingRate) {
        CmsRegistrationMerchant m = new CmsRegistrationMerchant();
        m.setId(1L);
        m.setMemberId(memberId);
        m.setTopicId(200L);
        m.setAuditStatus(1L);              // 中标
        m.setSharingRate(new BigDecimal(sharingRate));
        return m;
    }

    @Test
    void topic_nonCoopWithoutCashOrPayableFailsClosedBeforePoints() {
        setUpTopic();
        when(topicRuleService.getActiveRule(0L, "topic")).thenReturn(topicRule("0.03", "0.90", "0.00", "0.02"));
        CmsTopic topic = primeTopic(new BigDecimal("1000"), 88L);
        // 一个中标商家、无邀请人(selectUmsMemberById 返回的成员 inviteUserId 为 null)
        when(merchantMapper.selectCmsRegistrationMerchantList(any()))
                .thenReturn(Collections.singletonList(approvedMerchant(99L, "100")));

        ServiceException guard = assertThrows(ServiceException.class,
                () -> ReflectionTestUtils.invokeMethod(topicSvc, "settleTopic", topic,
                        topicRule("0.03", "0.90", "0.00", "0.02")));
        assertEquals("NON_COOP_TOPIC_CASH_SETTLEMENT_UNPROVEN: topicId=200", guard.getMessage());

        assertThrows(IllegalStateException.class, topicSvc::executeTopicSettlement);

        verify(topicMember, never()).addBalance(eq(88L), any());
        verify(pointsAwardService, never()).awardPublisherSettlement(anyLong(), anyLong());
        verify(topicTransactionManager).rollback(any());
    }

    /**
     * ★ 拆除后的守门断言(2026-07-17):存量路**不许再碰商家表**。
     *
     * <p>原先这里有两条用例:一条断言「商家池 1000×0.70=700 写进 sharing_amount」,一条断言
     * 「邀请人拿商家佣金的 2% = 14」。两条都随 distributeMerchantCommissions 一起删了 ——
     * 那个方法写的 sharing_amount 全仓零消费者(假账),却从这笔假金额里抽 2% 给邀请人打**真钱**。
     * 拓展人激励改由 MerchantReferralBounty 承接(只认核销,与结算解耦,见 MerchantReferralBountyTest)。
     *
     * <p>本用例接替它们把门:即使商家表里躺着一个中标商家、且他有邀请人,存量结算也
     * <b>一不回写商家表、二不给邀请人或发布者打现金</b>，三不在无现金/应付凭据时发积分。
     */
    @Test
    void topic_legacyBranch_neverTouchesMerchantTableNorPaysInviter() {
        setUpTopic();
        when(topicRuleService.getActiveRule(0L, "topic")).thenReturn(topicRule("0.03", "0.90", "0.00", "0.02"));
        primeTopic(new BigDecimal("1000"), 88L);
        // 商家 99 中标,且有邀请人 66 —— 拆除前这会让 66 拿到 700×0.02=14
        when(merchantMapper.selectCmsRegistrationMerchantList(any()))
                .thenReturn(Collections.singletonList(approvedMerchant(99L, "100")));
        when(topicMember.selectUmsMemberById(99L)).thenAnswer(inv -> {
            UmsMember mm = new UmsMember();
            mm.setId(99L);
            mm.setBalance(BigDecimal.ZERO);
            mm.setInviteUserId("66");
            return mm;
        });

        assertThrows(IllegalStateException.class, topicSvc::executeTopicSettlement);

        verify(merchantMapper, never()).updateCmsRegistrationMerchant(any());
        verify(topicMember, never()).addBalance(eq(66L), any());
        verify(topicMember, never()).addBalance(eq(88L), any());
        verify(pointsAwardService, never()).awardPublisherSettlement(anyLong(), anyLong());
    }

    @Test
    void topic_realGate_merchantPlusPublisherOverOne_refusesWholeBatch() {
        setUpTopic();
        // 商户0.80 + 发布者0.30 = 1.10 > 1.0 → 真闸拒结(平台率不参与,因平台在存量路是幻影)
        when(topicRuleService.getActiveRule(0L, "topic")).thenReturn(topicRule("0.10", "0.80", "0.30", "0.00"));
        primeTopic(new BigDecimal("1000"), 88L);
        when(merchantMapper.selectCmsRegistrationMerchantList(any()))
                .thenReturn(Collections.singletonList(approvedMerchant(99L, "100")));

        assertThrows(ServiceException.class, topicSvc::executeTopicSettlement, "merchant+publisher>100% 必须拒结整批(拆掉空闸)");

        verify(topicMember, never()).addBalance(anyLong(), any());
    }

    @Test
    void topic_creatorRateNonZeroRefusesEvenWhenLegacyPayoutSumIsBelowOne() {
        setUpTopic();
        when(topicRuleService.getActiveRule(0L, "topic"))
                .thenReturn(topicRule("0.03", "0.70", "0.20", "0.02"));
        primeTopic(new BigDecimal("1000"), 88L);

        assertThrows(ServiceException.class, topicSvc::executeTopicSettlement);

        verify(topicMapper, never()).claimTopicForSettlement(anyLong());
        verify(topicMember, never()).addBalance(anyLong(), any());
        verify(pointsAwardService, never()).awardPublisherSettlement(anyLong(), anyLong());
    }

    @Test
    void topic_ruleNull_refusesWholeBatch() {
        setUpTopic();
        when(topicRuleService.getActiveRule(0L, "topic")).thenReturn(null);
        primeTopic(new BigDecimal("1000"), 88L);

        assertThrows(ServiceException.class, topicSvc::executeTopicSettlement, "rule 缺失必须拒结整批");

        verify(topicMember, never()).addBalance(anyLong(), any());
    }
}
