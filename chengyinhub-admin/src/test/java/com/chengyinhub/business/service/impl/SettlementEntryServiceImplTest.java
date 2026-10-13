package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.SettlementDetail;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.domain.SettlementClaim;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.SettlementDetailMapper;
import com.chengyinhub.business.service.ISettlementRuleService;
import com.chengyinhub.business.service.ISettlementClaimService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementEntryServiceImplTest {

    /** 由 service(...) 装配时记下,给「拒绝入账时连 claim 都不许发生」这类断言用。 */
    private ISettlementClaimService claimService;

    /**
     * {@code settlement_rule.biz_type} 是 NOT NULL 列,fixture 必须照实带上 ——
     * H8 写侧闸按【真实命中的那条规则自己的 biz_type】判适用范围,
     * fixture 不带就等于让闸在测试里恒不触发(本仓「假绿测试的6种模式」之一)。
     */
    private SettlementRule rule(String bizType, BigDecimal platform, BigDecimal merchant, BigDecimal creator) {
        SettlementRule rule = new SettlementRule();
        rule.setBizType(bizType);
        rule.setPlatformCommissionRate(platform);
        rule.setMerchantRate(merchant);
        rule.setCreatorRate(creator);
        return rule;
    }

    private SettlementEntryServiceImpl service(SettlementDetailMapper detailMapper,
                                               ISettlementRuleService ruleService,
                                               CmsTopicMapper topicMapper) {
        return service(detailMapper, ruleService, topicMapper, mock(CmsActivityMapper.class));
    }

    private SettlementEntryServiceImpl service(SettlementDetailMapper detailMapper,
                                               ISettlementRuleService ruleService,
                                               CmsTopicMapper topicMapper,
                                               CmsActivityMapper activityMapper) {
        SettlementEntryServiceImpl service = new SettlementEntryServiceImpl();
        ReflectionTestUtils.setField(service, "settlementDetailMapper", detailMapper);
        ReflectionTestUtils.setField(service, "settlementRuleService", ruleService);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        // 双付闸用它判「这个主题走不走 coop 结算」。默认无合作单 ⇒ 走原来的整单入账路径,
        // 本类既有用例的语义因此不变。
        com.chengyinhub.business.mapper.CoopOrderMapper coopOrderMapper =
                org.mockito.Mockito.mock(com.chengyinhub.business.mapper.CoopOrderMapper.class);
        org.mockito.Mockito.when(coopOrderMapper.selectEffectiveOrdersByTopicId(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Collections.emptyList());
        ReflectionTestUtils.setField(service, "coopOrderMapper", coopOrderMapper);
        claimService = mock(ISettlementClaimService.class);
        final java.util.concurrent.atomic.AtomicLong claimIds = new java.util.concurrent.atomic.AtomicLong(1L);
        when(claimService.claim(any(SettlementClaim.class))).thenAnswer(invocation -> {
            SettlementClaim claim = invocation.getArgument(0);
            claim.setId(claimIds.getAndIncrement());
            return claim;
        });
        ReflectionTestUtils.setField(service, "settlementClaimService", claimService);
        return service;
    }

    @Test
    void recordPaidActivityRegistrationSplitsMerchantAndPlatform() {
        SettlementDetailMapper detailMapper = mock(SettlementDetailMapper.class);
        ISettlementRuleService ruleService = mock(ISettlementRuleService.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        SettlementEntryServiceImpl service = service(detailMapper, ruleService, topicMapper);

        CmsRegistration registration = new CmsRegistration();
        registration.setId(99L);
        registration.setOwnerType(2L); // activity
        registration.setOwnerId(20L);
        registration.setPayableAmount(new BigDecimal("120.00"));
        registration.setFeePlatformRate(new BigDecimal("0.1000"));
        when(detailMapper.existsAdjustment("registration", 99L)).thenReturn(0);
        when(ruleService.getActiveRule(0L, "activity"))
                .thenReturn(rule("activity", new BigDecimal("0.1000"), new BigDecimal("0.9000"), BigDecimal.ZERO));
        when(detailMapper.insertAdjustment(any(SettlementDetail.class))).thenReturn(1);

        int rows = service.recordPaidRegistration(registration, 3L);

        assertEquals(2, rows);
        ArgumentCaptor<SettlementDetail> captor = ArgumentCaptor.forClass(SettlementDetail.class);
        verify(detailMapper, org.mockito.Mockito.times(2)).insertAdjustment(captor.capture());
        List<SettlementDetail> captured = captor.getAllValues();
        SettlementDetail merchant = pick(captured, "merchant");
        SettlementDetail platform = pick(captured, "platform");
        // ★H8 正向面:合规规则(creator_rate=0)必须照常入账 —— 闸只拦非法配置,不许把正常单也拦下来。
        assertEquals(0, captured.stream().filter(d -> "creator".equals(d.getReceiverType())).count(),
                "creator_rate=0 的活动规则不产生任何 receiver_type=creator 明细");
        assertEquals(0, new BigDecimal("108.00").compareTo(merchant.getSettlementAmount()));
        assertEquals(Long.valueOf(3L), merchant.getReceiverId());
        assertEquals(0, new BigDecimal("12.00").compareTo(platform.getSettlementAmount()));
        assertEquals(Long.valueOf(0L), platform.getReceiverId());
        assertEquals(0, new BigDecimal("120.00").compareTo(
                merchant.getSettlementAmount().add(platform.getSettlementAmount())));
    }

    @Test
    void h8ActivityGlobalRuleWithNonZeroCreatorRateIsRejectedBeforeAnyWrite() {
        // H8:creator 份额全并 merchant。全局 activity 规则(merchant_id=0)若还留着 creator_rate=0.10
        // (迁移没跑 / 后台被改回),写侧必须【整笔拒绝入账】而不是静默并回商家继续跑 ——
        // 静默并回只防住了错付,配置漂移照样带着错规则长期运行、零信号。
        SettlementDetailMapper detailMapper = mock(SettlementDetailMapper.class);
        ISettlementRuleService ruleService = mock(ISettlementRuleService.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        SettlementEntryServiceImpl service = service(detailMapper, ruleService, topicMapper, activityMapper);

        CmsRegistration registration = new CmsRegistration();
        registration.setId(100L);
        registration.setOwnerType(2L); // activity
        registration.setOwnerId(20L);
        registration.setPayableAmount(new BigDecimal("100.00"));
        registration.setFeePlatformRate(new BigDecimal("0.1000"));

        CmsActivity activity = new CmsActivity();
        activity.setId(20L);
        activity.setTopicId(30L);
        when(activityMapper.selectCmsActivityById(20L)).thenReturn(activity);
        CmsTopic topic = new CmsTopic();
        topic.setId(30L);
        topic.setMemberId(50L);
        when(topicMapper.selectCmsTopicById(30L)).thenReturn(topic);
        when(detailMapper.existsAdjustment("registration", 100L)).thenReturn(0);
        when(ruleService.getActiveRule(0L, "activity"))
                .thenReturn(rule("activity", new BigDecimal("0.1000"), new BigDecimal("0.8000"),
                        new BigDecimal("0.1000")));
        when(detailMapper.insertAdjustment(any(SettlementDetail.class))).thenReturn(1);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.recordPaidRegistration(registration, 3L));
        assertTrue(ex.getMessage().contains("creatorRate"),
                "必须撞 creator_rate 这道闸本身,不能被相邻的闸接住:" + ex.getMessage());

        // 整笔 no-write:明细一条不写,claim 也一条不发(claim 先于 insert,只查 insert 会漏掉半截写)
        verify(detailMapper, never()).insertAdjustment(any(SettlementDetail.class));
        verify(claimService, never()).claim(any(SettlementClaim.class));
    }

    @Test
    void h8TopicPerMerchantRuleWithNonZeroCreatorRateIsRejectedBeforeAnyWrite() {
        // ★ 这条锁的是 H8 唯一够不到的那个面:per-merchant 覆盖规则。
        // 一次性迁移的 `WHERE merchant_id = 0` 只改三条全局规则;两条读侧结算闸读的也是
        // getActiveRule(0L, ...) —— 都够不到 merchant_id=8 这条。持续产出 creator 份额的写侧就是这里。
        SettlementDetailMapper detailMapper = mock(SettlementDetailMapper.class);
        ISettlementRuleService ruleService = mock(ISettlementRuleService.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        SettlementEntryServiceImpl service = service(detailMapper, ruleService, topicMapper);

        CmsRegistration registration = new CmsRegistration();
        registration.setId(77L);
        registration.setOwnerType(1L); // topic
        registration.setOwnerId(30L);
        registration.setPayableAmount(new BigDecimal("100.00"));
        registration.setFeePlatformRate(new BigDecimal("0.1000")); // 订单锁价快照
        CmsTopic topic = new CmsTopic();
        topic.setId(30L);
        topic.setMemberId(50L); // 主题设计者存在且可解析 —— 不是靠 creatorId 为空绕过
        when(topicMapper.selectCmsTopicById(30L)).thenReturn(topic);
        when(detailMapper.existsAdjustment("registration", 77L)).thenReturn(0);
        // 运营在后台建的 merchant_id=8 覆盖规则,creator_rate 仍是 0.20
        when(ruleService.getActiveRule(8L, "topic"))
                .thenReturn(rule("topic", new BigDecimal("0.1000"), new BigDecimal("0.7000"),
                        new BigDecimal("0.2000")));
        when(detailMapper.insertAdjustment(any(SettlementDetail.class))).thenReturn(1);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.recordPaidRegistration(registration, 8L));
        assertTrue(ex.getMessage().contains("creatorRate"),
                "必须撞 creator_rate 这道闸本身,不能被相邻的闸接住:" + ex.getMessage());

        verify(detailMapper, never()).insertAdjustment(any(SettlementDetail.class));
        verify(claimService, never()).claim(any(SettlementClaim.class));
    }

    @Test
    void h8TopicRuleWithNullCreatorRateIsRejectedBeforeAnyWrite() {
        // creator_rate 为 NULL 不是「没配 = 0」,是「不知道」:直连 SQL 写入 / 建表默认缺省都会留下 NULL。
        // 闸对 NULL 与非零一视同仁地拒,才是 fail-closed;放行 NULL 等于给绕过留一条零成本通道。
        SettlementDetailMapper detailMapper = mock(SettlementDetailMapper.class);
        ISettlementRuleService ruleService = mock(ISettlementRuleService.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        SettlementEntryServiceImpl service = service(detailMapper, ruleService, topicMapper);

        CmsRegistration registration = new CmsRegistration();
        registration.setId(79L);
        registration.setOwnerType(1L); // topic
        registration.setOwnerId(30L);
        registration.setPayableAmount(new BigDecimal("100.00"));
        registration.setFeePlatformRate(new BigDecimal("0.1000"));
        CmsTopic topic = new CmsTopic();
        topic.setId(30L);
        topic.setMemberId(50L);
        when(topicMapper.selectCmsTopicById(30L)).thenReturn(topic);
        when(ruleService.getActiveRule(8L, "topic"))
                .thenReturn(rule("topic", new BigDecimal("0.1000"), new BigDecimal("0.9000"), null));
        when(detailMapper.insertAdjustment(any(SettlementDetail.class))).thenReturn(1);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.recordPaidRegistration(registration, 8L));
        assertTrue(ex.getMessage().contains("creatorRate"),
                "必须撞 creator_rate 这道闸本身,不能被相邻的闸接住:" + ex.getMessage());

        verify(detailMapper, never()).insertAdjustment(any(SettlementDetail.class));
        verify(claimService, never()).claim(any(SettlementClaim.class));
    }

    @Test
    void couponRuleWithNonZeroCreatorRateStaysUnaffectedByH8Gate() {
        // H8 只裁决 topic / topic_club / activity 三类。闸按【命中规则自己的 biz_type】判范围,
        // coupon 配着非零 creator_rate 也必须照常入账 —— 扩大保护半径 = 越界改别的玩法的资金语义。
        SettlementDetailMapper detailMapper = mock(SettlementDetailMapper.class);
        ISettlementRuleService ruleService = mock(ISettlementRuleService.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        SettlementEntryServiceImpl service = service(detailMapper, ruleService, topicMapper);
        SmsCouponHistory history = new SmsCouponHistory();
        history.setId(89L);
        SmsCoupon coupon = new SmsCoupon();
        coupon.setMerchantId(6L);
        coupon.setMemberId(60L);
        coupon.setAmount(new BigDecimal("50.00"));
        when(ruleService.getActiveRule(60L, "coupon"))
                .thenReturn(rule("coupon", new BigDecimal("0.1000"), new BigDecimal("0.7000"),
                        new BigDecimal("0.2000")));
        when(detailMapper.insertAdjustment(any(SettlementDetail.class))).thenReturn(1);

        int rows = service.recordCouponVerification(history, coupon);

        assertEquals(2, rows);
        ArgumentCaptor<SettlementDetail> captor = ArgumentCaptor.forClass(SettlementDetail.class);
        verify(detailMapper, org.mockito.Mockito.times(2)).insertAdjustment(captor.capture());
        List<SettlementDetail> captured = captor.getAllValues();
        assertEquals(0, new BigDecimal("45.00").compareTo(pick(captured, "merchant").getSettlementAmount()));
        assertEquals(0, new BigDecimal("5.00").compareTo(pick(captured, "platform").getSettlementAmount()));
    }

    @Test
    void recordPaidTopicRegistrationPrefersLockedPlatformSnapshotOverLiveRule() {
        // 拍板4:主题锁价时冻结了 fee_platform_rate=0.10;之后 D7 把 live rule 平台率降到 0.03。
        // 分账必须用锁价快照 0.10,不被 D7 追溯改写(定价冻结、分账也冻结)。
        SettlementDetailMapper detailMapper = mock(SettlementDetailMapper.class);
        ISettlementRuleService ruleService = mock(ISettlementRuleService.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        SettlementEntryServiceImpl service = service(detailMapper, ruleService, topicMapper);

        CmsRegistration registration = new CmsRegistration();
        registration.setId(77L);
        registration.setOwnerType(1L); // topic
        registration.setOwnerId(30L);
        registration.setPayableAmount(new BigDecimal("100.00"));
        registration.setFeePlatformRate(new BigDecimal("0.1000")); // 订单锁价快照
        CmsTopic topic = new CmsTopic();
        topic.setId(30L);
        topic.setMemberId(50L);
        topic.setFeePlatformRate(new BigDecimal("0.0300")); // live 值已变化，不能追溯改写订单
        when(topicMapper.selectCmsTopicById(30L)).thenReturn(topic);
        when(detailMapper.existsAdjustment("registration", 77L)).thenReturn(0);
        // live rule 平台率已被 D7 降到 0.03(H8 之后 creator 恒为 0,合规规则才走得到分账)
        when(ruleService.getActiveRule(8L, "topic"))
                .thenReturn(rule("topic", new BigDecimal("0.0300"), new BigDecimal("0.9700"), BigDecimal.ZERO));
        when(detailMapper.insertAdjustment(any(SettlementDetail.class))).thenReturn(1);

        int rows = service.recordPaidRegistration(registration, 8L);

        assertEquals(2, rows);
        ArgumentCaptor<SettlementDetail> captor = ArgumentCaptor.forClass(SettlementDetail.class);
        verify(detailMapper, org.mockito.Mockito.times(2)).insertAdjustment(captor.capture());
        List<SettlementDetail> captured = captor.getAllValues();
        SettlementDetail platform = pick(captured, "platform");
        SettlementDetail merchant = pick(captured, "merchant");
        // 平台用快照 0.10 → 10(而非 live 0.03 → 3)
        assertEquals(0, new BigDecimal("10.00").compareTo(platform.getSettlementAmount()),
                "平台应按锁价快照 0.10 拿 10,不被 D7 的 live 0.03 改写");
        // ★H8 正向面(主题腿):creator_rate=0 的合规规则照常入账,商家 = gross − 平台 = 90,零 creator 明细
        assertEquals(0, captured.stream().filter(d -> "creator".equals(d.getReceiverType())).count(),
                "creator_rate=0 的主题规则不产生任何 receiver_type=creator 明细");
        assertEquals(0, new BigDecimal("90.00").compareTo(merchant.getSettlementAmount()));
        assertEquals(0, new BigDecimal("100.00").compareTo(merchant.getSettlementAmount()
                .add(platform.getSettlementAmount())));
    }

    @Test
    void payableRegistrationWithoutFeeSnapshotNeverFallsBackToLiveRule() {
        SettlementDetailMapper detailMapper = mock(SettlementDetailMapper.class);
        ISettlementRuleService ruleService = mock(ISettlementRuleService.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        SettlementEntryServiceImpl service = service(detailMapper, ruleService, topicMapper);
        CmsRegistration registration = new CmsRegistration();
        registration.setId(78L);
        registration.setOwnerType(2L);
        registration.setOwnerId(20L);
        registration.setPayableAmount(new BigDecimal("100.00"));
        when(detailMapper.existsAdjustment("registration", 78L)).thenReturn(0);
        when(ruleService.getActiveRule(0L, "activity"))
                .thenReturn(rule("activity", new BigDecimal("0.0300"), new BigDecimal("0.9700"), BigDecimal.ZERO));

        assertThrows(IllegalStateException.class,
                () -> service.recordPaidRegistration(registration, 3L));

        verify(detailMapper, never()).insertAdjustment(any(SettlementDetail.class));
    }

    @Test
    void zeroCashRegistrationOccupiesNoneWithoutIncomeDetail() {
        SettlementDetailMapper detailMapper = mock(SettlementDetailMapper.class);
        ISettlementRuleService ruleService = mock(ISettlementRuleService.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        SettlementEntryServiceImpl service = service(detailMapper, ruleService, topicMapper);
        CmsRegistration registration = new CmsRegistration();
        registration.setId(99L);
        int rows = service.recordPaidRegistration(registration, 3L);

        assertEquals(0, rows);
        verify(detailMapper, never()).insertAdjustment(any(SettlementDetail.class));
    }

    @Test
    void partialExistingSplitCannotMasqueradeAsCompleted() {
        SettlementDetailMapper detailMapper = mock(SettlementDetailMapper.class);
        ISettlementRuleService ruleService = mock(ISettlementRuleService.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        SettlementEntryServiceImpl service = service(detailMapper, ruleService, topicMapper);
        CmsRegistration registration = new CmsRegistration();
        registration.setId(99L);
        registration.setOwnerType(2L);
        registration.setPayableAmount(new BigDecimal("100.00"));
        registration.setFeePlatformRate(new BigDecimal("0.1000"));
        when(ruleService.getActiveRule(0L, "activity"))
                .thenReturn(rule("activity", new BigDecimal("0.1000"), new BigDecimal("0.9000"), BigDecimal.ZERO));
        when(detailMapper.insertAdjustment(any())).thenReturn(0);
        SettlementDetail merchant = new SettlementDetail();
        merchant.setSourceType("registration");
        merchant.setSourceId(99L);
        merchant.setMerchantId(3L);
        merchant.setReceiverType("merchant");
        merchant.setReceiverId(3L);
        merchant.setGrossAmount(new BigDecimal("90.00"));
        merchant.setCommissionAmount(BigDecimal.ZERO);
        merchant.setSettlementAmount(new BigDecimal("90.00"));
        merchant.setStatus(0);
        when(detailMapper.selectByIdempotencyKeyForUpdate("registration:99:merchant:3"))
                .thenReturn(merchant);

        assertThrows(IllegalStateException.class,
                () -> service.recordPaidRegistration(registration, 3L));
    }

    @Test
    void misconfiguredRatesExceedingGrossAreRejectedNotSilentlyClampedToZero() {
        // P1-5 M块真实资损缺口(任务书1.3节):费率配置错误(平台份额超过100%)时,
        // 旧实现用"merchantShare = gross - platformShare,负数夹0"——
        // 结果平台份额会静默突破 gross,守恒被打破却没有任何异常或日志能拦下来。
        // 参照系统1 CmsActivityServiceImpl.validateActivityCommissionSettings() 的做法:
        // 不满足守恒时必须直接拒绝整笔入账,而不是夹0兜底继续放行。
        // ⚠️ H8 之后 creator 份额恒为 0(全并 merchant),所以这道闸只能靠【平台率】撑破 gross 来触发;
        //    继续用 creator=0.60 的旧 fixture 会让本用例根本走不到闸上(而 assertThrows 仍可能被
        //    相邻的闸接住变成假绿),故 fixture 改挂平台率,并断言错误文案指明是哪道闸。
        SettlementDetailMapper detailMapper = mock(SettlementDetailMapper.class);
        ISettlementRuleService ruleService = mock(ISettlementRuleService.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        SettlementEntryServiceImpl service = service(detailMapper, ruleService, topicMapper, activityMapper);

        CmsRegistration registration = new CmsRegistration();
        registration.setId(101L);
        registration.setOwnerType(2L); // activity
        registration.setOwnerId(20L);
        registration.setPayableAmount(new BigDecimal("100.00"));
        // 锁价快照里的平台率被配错成 1.20,平台份额 120 > gross 100
        registration.setFeePlatformRate(new BigDecimal("1.2000"));

        CmsActivity activity = new CmsActivity();
        activity.setId(20L);
        activity.setTopicId(30L);
        when(activityMapper.selectCmsActivityById(20L)).thenReturn(activity);
        CmsTopic topic = new CmsTopic();
        topic.setId(30L);
        topic.setMemberId(50L);
        when(topicMapper.selectCmsTopicById(30L)).thenReturn(topic);
        when(detailMapper.existsAdjustment("registration", 101L)).thenReturn(0);
        when(ruleService.getActiveRule(0L, "activity"))
                .thenReturn(rule("activity", new BigDecimal("1.2000"), new BigDecimal("-0.2000"), BigDecimal.ZERO));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.recordPaidRegistration(registration, 3L));
        assertTrue(ex.getMessage().contains("platformShare 超过可分配总额"),
                "必须撞守恒闸本身,不能被相邻的闸接住:" + ex.getMessage());

        verify(detailMapper, never()).insertAdjustment(any(SettlementDetail.class));
    }

    @Test
    void recordCouponVerificationGivesFullAmountToMerchant() {
        SettlementDetailMapper detailMapper = mock(SettlementDetailMapper.class);
        ISettlementRuleService ruleService = mock(ISettlementRuleService.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        SettlementEntryServiceImpl service = service(detailMapper, ruleService, topicMapper);
        SmsCouponHistory history = new SmsCouponHistory();
        history.setId(88L);
        SmsCoupon coupon = new SmsCoupon();
        coupon.setMerchantId(6L);
        coupon.setMemberId(60L);
        coupon.setAmount(new BigDecimal("25.00"));
        when(detailMapper.existsAdjustment("coupon", 88L)).thenReturn(0);
        when(ruleService.getActiveRule(60L, "coupon"))
                .thenReturn(rule("coupon", BigDecimal.ZERO, new BigDecimal("1.0000"), BigDecimal.ZERO));
        when(detailMapper.insertAdjustment(any(SettlementDetail.class))).thenReturn(1);

        int rows = service.recordCouponVerification(history, coupon);

        assertEquals(1, rows);
        verify(detailMapper).insertAdjustment(org.mockito.ArgumentMatchers.argThat(detail ->
                "coupon".equals(detail.getSourceType())
                        && Long.valueOf(88L).equals(detail.getSourceId())
                        && "merchant".equals(detail.getReceiverType())
                        && Long.valueOf(60L).equals(detail.getMerchantId())
                        && Long.valueOf(60L).equals(detail.getReceiverId())
                        && new BigDecimal("25.00").compareTo(detail.getSettlementAmount()) == 0));
    }

    @Test
    void couponMerchantRowIdNeverBecomesSettlementPayee() {
        SettlementDetailMapper detailMapper = mock(SettlementDetailMapper.class);
        ISettlementRuleService ruleService = mock(ISettlementRuleService.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        SettlementEntryServiceImpl service = service(detailMapper, ruleService, topicMapper);
        SmsCouponHistory history = new SmsCouponHistory();
        history.setId(188L);
        SmsCoupon coupon = new SmsCoupon();
        coupon.setMerchantId(11L);
        coupon.setMemberId(9001L);
        coupon.setAmount(new BigDecimal("25.00"));
        when(ruleService.getActiveRule(9001L, "coupon"))
                .thenReturn(rule("coupon", BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO));
        when(detailMapper.insertAdjustment(any(SettlementDetail.class))).thenReturn(1);

        assertEquals(1, service.recordCouponVerification(history, coupon));

        ArgumentCaptor<SettlementDetail> details = ArgumentCaptor.forClass(SettlementDetail.class);
        verify(detailMapper).insertAdjustment(details.capture());
        assertEquals(9001L, details.getValue().getMerchantId());
        assertEquals(9001L, details.getValue().getReceiverId());
    }

    @Test
    void recordOnPaymentSkipsTopicBecauseMerchantUndeterminedUntilVerification() {
        SettlementDetailMapper detailMapper = mock(SettlementDetailMapper.class);
        ISettlementRuleService ruleService = mock(ISettlementRuleService.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        SettlementEntryServiceImpl service = service(detailMapper, ruleService, topicMapper, activityMapper);
        CmsRegistration registration = new CmsRegistration();
        registration.setId(77L);
        registration.setOwnerType(1L); // topic
        registration.setOwnerId(30L);

        int rows = service.recordOnPayment(registration);

        assertEquals(0, rows);
        verify(detailMapper, never()).insertAdjustment(any(SettlementDetail.class));
        verify(detailMapper, never()).existsAdjustment(any(), any());
    }

    @Test
    void recordOnPaymentRecordsActivityWithActivityOwnerAsMerchant() {
        SettlementDetailMapper detailMapper = mock(SettlementDetailMapper.class);
        ISettlementRuleService ruleService = mock(ISettlementRuleService.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        SettlementEntryServiceImpl service = service(detailMapper, ruleService, topicMapper, activityMapper);
        CmsRegistration registration = new CmsRegistration();
        registration.setId(99L);
        registration.setOwnerType(2L); // activity
        registration.setOwnerId(20L);
        registration.setPayableAmount(new BigDecimal("120.00"));
        registration.setFeePlatformRate(new BigDecimal("0.1000"));
        CmsActivity activity = new CmsActivity();
        activity.setId(20L);
        activity.setMemberId(3L);
        when(activityMapper.selectCmsActivityById(20L)).thenReturn(activity);
        when(detailMapper.existsAdjustment("registration", 99L)).thenReturn(0);
        when(ruleService.getActiveRule(0L, "activity"))
                .thenReturn(rule("activity", new BigDecimal("0.1000"), new BigDecimal("0.9000"), BigDecimal.ZERO));
        when(detailMapper.insertAdjustment(any(SettlementDetail.class))).thenReturn(1);

        int rows = service.recordOnPayment(registration);

        assertEquals(2, rows);
        verify(detailMapper).insertAdjustment(org.mockito.ArgumentMatchers.argThat(d ->
                "merchant".equals(d.getReceiverType()) && Long.valueOf(3L).equals(d.getReceiverId())
                        && new BigDecimal("108.00").compareTo(d.getSettlementAmount()) == 0));
    }

    private SettlementDetail pick(List<SettlementDetail> rows, String receiverType) {
        return rows.stream().filter(r -> receiverType.equals(r.getReceiverType())).findFirst()
                .orElseThrow(() -> new IllegalStateException("no settlement row for receiverType=" + receiverType));
    }

    /**
     * 券冲销必须**删除**待结算明细:uk_source_receiver 无条件唯一键会让「撤销→重核销」
     * 的第二次入账撞上被作废的旧行并 500 —— 保留旧行等于废掉「券恢复可用」本身。
     */
    @Test
    void couponReverseDeletesPendingSplitsSoReRedemptionCanReinsert() {
        SettlementDetailMapper detailMapper = mock(SettlementDetailMapper.class);
        SettlementEntryServiceImpl service = service(detailMapper, mock(ISettlementRuleService.class),
                mock(CmsTopicMapper.class));
        when(detailMapper.deletePendingCouponVerification(188L)).thenReturn(2);
        when(detailMapper.selectSettledDetail("coupon", 188L)).thenReturn(null);

        assertEquals(2, service.deletePendingCouponVerification(188L));

        verify(detailMapper).deletePendingCouponVerification(188L);
        verify(detailMapper).selectSettledDetail("coupon", 188L);
    }

    /** 已结算的明细不删(删不掉,由 SQL 的 status/batch 条件保证),但要读回点名,不许静默。 */
    @Test
    void couponReverseReadsBackSettledDetailInsteadOfSilentlyIgnoringIt() {
        SettlementDetailMapper detailMapper = mock(SettlementDetailMapper.class);
        SettlementEntryServiceImpl service = service(detailMapper, mock(ISettlementRuleService.class),
                mock(CmsTopicMapper.class));
        SettlementDetail settled = new SettlementDetail();
        settled.setId(9L);
        settled.setSettlementAmount(new BigDecimal("25.00"));
        when(detailMapper.deletePendingCouponVerification(188L)).thenReturn(0);
        when(detailMapper.selectSettledDetail("coupon", 188L)).thenReturn(settled);

        assertEquals(0, service.deletePendingCouponVerification(188L));

        verify(detailMapper).selectSettledDetail("coupon", 188L);
    }
}
