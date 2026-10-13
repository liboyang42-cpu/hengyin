package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsSetting;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsSettingMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.SettlementDetailMapper;
import com.chengyinhub.business.service.impl.CmsActivityServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HF-02(A-29) 活动结算幂等/领取门禁单测。
 * 核心断言:只有原子领取(claimActivityForSettlement 返回 1)成功时才会入账;
 * 领取返回 0(已被结算/并发)时绝不调用 addBalance —— 杜绝重复入账资损。
 */
class ActivitySettlementIdempotencyTest {

    private CmsActivityMapper cmsActivityMapper;
    private CmsSettingMapper cmsSettingMapper;
    private CmsRegistrationMapper cmsRegistrationMapper;
    private CmsTopicMapper cmsTopicMapper;
    private SettlementDetailMapper settlementDetailMapper;
    private IUmsMemberService umsMemberService;
    private ISettlementRuleService settlementRuleService;
    private IPointsAwardService pointsAwardService;
    private PlatformTransactionManager transactionManager;
    private CmsActivityServiceImpl svc;

    @BeforeEach
    void setUp() {
        cmsActivityMapper = mock(CmsActivityMapper.class);
        cmsSettingMapper = mock(CmsSettingMapper.class);
        cmsRegistrationMapper = mock(CmsRegistrationMapper.class);
        cmsTopicMapper = mock(CmsTopicMapper.class);
        settlementDetailMapper = mock(SettlementDetailMapper.class);
        umsMemberService = mock(IUmsMemberService.class);
        settlementRuleService = mock(ISettlementRuleService.class);
        pointsAwardService = mock(IPointsAwardService.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        svc = new CmsActivityServiceImpl();
        ReflectionTestUtils.setField(svc, "cmsActivityMapper", cmsActivityMapper);
        ReflectionTestUtils.setField(svc, "cmsSettingMapper", cmsSettingMapper);
        ReflectionTestUtils.setField(svc, "cmsRegistrationMapper", cmsRegistrationMapper);
        ReflectionTestUtils.setField(svc, "cmsTopicMapper", cmsTopicMapper);
        ReflectionTestUtils.setField(svc, "settlementDetailMapper", settlementDetailMapper);
        ReflectionTestUtils.setField(svc, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(svc, "settlementRuleService", settlementRuleService);
        ReflectionTestUtils.setField(svc, "pointsAwardService", pointsAwardService);
        ReflectionTestUtils.setField(svc, "transactionManager", transactionManager);
        ReflectionTestUtils.setField(svc, "activitySettlementHoldMapper", org.mockito.Mockito.mock(com.chengyinhub.business.mapper.ActivitySettlementHoldMapper.class));
        ReflectionTestUtils.setField(svc, "settlementDebtLedger", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.SettlementDebtLedger.class));

        CmsActivity a = new CmsActivity();
        a.setId(100L);
        a.setName("测试活动");
        a.setMemberId(9L);
        a.setTopicId(200L);
        when(cmsActivityMapper.selectActivitiesForSettlement())
                .thenReturn(Collections.singletonList(a));

        com.chengyinhub.business.domain.CmsTopic topic = new com.chengyinhub.business.domain.CmsTopic();
        topic.setId(200L);
        topic.setProductType(ProductType.ORIENTEERING);
        when(cmsTopicMapper.selectCmsTopicById(200L)).thenReturn(topic);

        // ★ 收款方全部装成「可出款」:平台账户可用、商户会员存在、加余额成功。
        // 不这么装,fail-closed 用例会被相邻的「收款账户不可用 / 收款会员不存在」闸接住,
        // 拆掉本用例真正要锁的那道闸也不会红(2026-08-16 mutation 实测到过这个假绿)。
        CmsSetting setting = new CmsSetting();
        setting.setPlatformMemberId(8L);
        when(cmsSettingMapper.selectCmsSettingById(1L)).thenReturn(setting);
        when(umsMemberService.lockMemberById(8L)).thenReturn(8L);
        when(umsMemberService.selectUmsMemberById(8L)).thenReturn(usableMember());
        when(umsMemberService.selectUmsMemberById(9L)).thenReturn(usableMember());
        when(umsMemberService.addBalance(anyLong(), any())).thenReturn(true);

        // P0-28:费率真源为 settlement_rule(小数),和=1.0 让 validateActivityCommissionSettings 通过
        com.chengyinhub.business.domain.SettlementRule rule = new com.chengyinhub.business.domain.SettlementRule();
        rule.setPlatformCommissionRate(new BigDecimal("0.10"));
        rule.setMerchantRate(new BigDecimal("0.90"));
        rule.setCreatorRate(new BigDecimal("0.00"));
        rule.setInviterRate(new BigDecimal("0.00"));
        when(settlementRuleService.getActiveRule(0L, "activity")).thenReturn(rule);
    }

    private static com.chengyinhub.business.domain.UmsMember usableMember() {
        com.chengyinhub.business.domain.UmsMember m = new com.chengyinhub.business.domain.UmsMember();
        m.setStatus(1L);
        m.setDelFlag(0);
        m.setBalance(BigDecimal.ZERO);
        return m;
    }

    @Test
    void alreadyClaimed_noPayout() {
        // 领取失败(已被其它流程结算)→ 返回 0
        when(cmsActivityMapper.claimActivityForSettlement(anyLong())).thenReturn(0);

        int settled = svc.executeActivitySettlement();

        assertEquals(0, settled, "已被领取的活动不计入结算");
        verify(umsMemberService, never()).addBalance(anyLong(), any());
    }

    @Test
    void claimedButNoSales_markedSettled_noPayout() {
        when(cmsActivityMapper.claimActivityForSettlement(anyLong())).thenReturn(1);
        when(cmsRegistrationMapper.selectTotalSalesByActivityId(100L)).thenReturn(BigDecimal.ZERO);

        int settled = svc.executeActivitySettlement();

        assertEquals(1, settled, "领取成功但无销售额,仍算结算完成");
        // 无销售额不分润
        verify(umsMemberService, never()).addBalance(anyLong(), any());
        // 领取只发生一次
        verify(cmsActivityMapper, times(1)).claimActivityForSettlement(100L);
    }

    @Test
    void freeExplore_isRejectedBeforeClaimAndAnyPayout() {
        com.chengyinhub.business.domain.CmsTopic topic = new com.chengyinhub.business.domain.CmsTopic();
        topic.setId(200L);
        topic.setProductType(ProductType.FREE_EXPLORE);
        when(cmsTopicMapper.selectCmsTopicById(200L)).thenReturn(topic);
        when(cmsActivityMapper.claimActivityForSettlement(100L)).thenReturn(1);

        assertThrows(IllegalStateException.class, svc::executeActivitySettlement,
                "FREE_EXPLORE 必须在旧活动结算入口 fail-closed");

        verify(cmsActivityMapper, never()).claimActivityForSettlement(anyLong());
        verify(cmsRegistrationMapper, never()).selectTotalSalesByActivityId(anyLong());
        verify(umsMemberService, never()).addBalance(anyLong(), any());
        verify(settlementDetailMapper, never()).markActivityRegistrationDetailsSettled(anyLong());
    }

    /**
     * 0-5 裁决:关联主题 product_type=NULL 且**零销售**时,允许一次性收口
     * (领取把 is_settlement 置 1,不再每轮重扫),但绝不能借这条路出任何钱或积分。
     * ⚠️ 收口的判据是「没钱可分」,不是「NULL 也算合法」—— 有销售的对称用例见下。
     */
    @Test
    void linkedTopicWithNullProductType_zeroSales_closesOutWithoutAnyPayout() {
        com.chengyinhub.business.domain.CmsTopic topic = new com.chengyinhub.business.domain.CmsTopic();
        topic.setId(200L);
        topic.setProductType(null);
        when(cmsTopicMapper.selectCmsTopicById(200L)).thenReturn(topic);
        when(cmsActivityMapper.claimActivityForSettlement(100L)).thenReturn(1);
        when(cmsRegistrationMapper.selectTotalSalesByActivityId(100L)).thenReturn(BigDecimal.ZERO);

        assertEquals(1, svc.executeActivitySettlement(),
                "product_type=NULL 且零销售:一次性收口,不再每轮重扫");

        verify(cmsActivityMapper, times(1)).claimActivityForSettlement(100L);
        assertNoCashAndNoPoints();
    }

    /** 0-5 裁决:同一条 NULL product_type,只要**有正销售**就必须 fail-closed,一分钱一分积分都不许出。 */
    @Test
    void linkedTopicWithNullProductType_withSales_isRejectedBeforeAnyPayout() {
        com.chengyinhub.business.domain.CmsTopic topic = new com.chengyinhub.business.domain.CmsTopic();
        topic.setId(200L);
        topic.setProductType(null);
        when(cmsTopicMapper.selectCmsTopicById(200L)).thenReturn(topic);
        when(cmsActivityMapper.claimActivityForSettlement(100L)).thenReturn(1);
        when(cmsRegistrationMapper.selectTotalSalesByActivityId(100L)).thenReturn(new BigDecimal("69.00"));

        assertThrows(IllegalStateException.class, svc::executeActivitySettlement,
                "product_type=NULL 且有销售:必须拒结并回滚领取,活动下轮可重试");

        assertNoCashAndNoPoints();
    }

    /** topic 行整个缺失、零销售:同上一次性收口,不入账。 */
    @Test
    void missingLinkedTopic_zeroSales_closesOutWithoutAnyPayout() {
        when(cmsTopicMapper.selectCmsTopicById(200L)).thenReturn(null);
        when(cmsActivityMapper.claimActivityForSettlement(100L)).thenReturn(1);
        when(cmsRegistrationMapper.selectTotalSalesByActivityId(100L)).thenReturn(BigDecimal.ZERO);

        assertEquals(1, svc.executeActivitySettlement(),
                "关联 topic 缺失且零销售:一次性收口,不再被 SQL 每轮重扫");

        verify(cmsActivityMapper, times(1)).claimActivityForSettlement(100L);
        assertNoCashAndNoPoints();
    }

    /** topic 行整个缺失、有正销售:必须 fail-closed,不能猜收款人把钱发出去。 */
    @Test
    void missingLinkedTopic_withSales_isRejectedBeforeAnyPayout() {
        when(cmsTopicMapper.selectCmsTopicById(200L)).thenReturn(null);
        when(cmsActivityMapper.claimActivityForSettlement(100L)).thenReturn(1);
        when(cmsRegistrationMapper.selectTotalSalesByActivityId(100L)).thenReturn(new BigDecimal("69.00"));

        assertThrows(IllegalStateException.class, svc::executeActivitySettlement,
                "关联 topic 缺失且有销售:必须拒结并回滚领取");

        assertNoCashAndNoPoints();
    }

    /** 现金(余额)、纯账本置已结算、H8 event15 积分 —— 三条出款/发奖出口一条都不许被触到。 */
    private void assertNoCashAndNoPoints() {
        verify(umsMemberService, never()).addBalance(anyLong(), any());
        verify(settlementDetailMapper, never()).markActivityRegistrationDetailsSettled(anyLong());
        verify(pointsAwardService, never()).awardPublisherSettlement(anyLong(), anyLong());
    }
}
