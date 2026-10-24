package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.exception.ServiceException;
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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A3-7(P0)主题结算 CAS 原子领取 / 幂等单测(照抄活动线 ActivitySettlementIdempotencyTest)。
 * 核心断言:claimTopicForSettlement 返回 0(已结算/并发)时,绝不进入分润流程 —— 不查销售额、不分账,
 * 杜绝并发/重入重复入账。
 */
class TopicSettlementIdempotencyTest {

    private CmsTopicMapper cmsTopicMapper;
    private CmsRegistrationMapper cmsRegistrationMapper;
    private CoopOrderMapper coopOrderMapper;
    private IUmsMemberService umsMemberService;
    private ISettlementRuleService settlementRuleService;
    private ICoopInviteService coopInviteService;
    private PlatformTransactionManager transactionManager;
    private SettlementRule topicRule;
    private CmsTopicServiceImpl svc;

    @BeforeEach
    void setUp() {
        cmsTopicMapper = mock(CmsTopicMapper.class);
        cmsRegistrationMapper = mock(CmsRegistrationMapper.class);
        coopOrderMapper = mock(CoopOrderMapper.class);
        umsMemberService = mock(IUmsMemberService.class);
        settlementRuleService = mock(ISettlementRuleService.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        svc = new CmsTopicServiceImpl();

        org.springframework.test.util.ReflectionTestUtils.setField(svc, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(svc, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(svc, "cmsTopicMapper", cmsTopicMapper);
        ReflectionTestUtils.setField(svc, "cmsRegistrationMapper", cmsRegistrationMapper);
        ReflectionTestUtils.setField(svc, "coopOrderMapper", coopOrderMapper);
        ReflectionTestUtils.setField(svc, "coopSettlementEvidenceMapper", mock(com.chengyinhub.business.mapper.CoopSettlementEvidenceMapper.class));
        com.chengyinhub.business.service.impl.OriginalCloseRefundHistoryFixture.install(svc, 100L, Collections.emptyList());
        ReflectionTestUtils.setField(svc, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(svc, "settlementRuleService", settlementRuleService);
        ReflectionTestUtils.setField(svc, "transactionManager", transactionManager);
        // 无销售收口也要登记该主题的保证金退款意图(d7593ff37)
        ReflectionTestUtils.setField(svc, "coopInviteMapper", mock(com.chengyinhub.business.mapper.CoopInviteMapper.class));
        coopInviteService = mock(ICoopInviteService.class);
        ReflectionTestUtils.setField(svc, "coopInviteService", coopInviteService);

        CmsTopic t = new CmsTopic();
        t.setId(100L);
        t.setProductType(ProductType.ORIENTEERING);
        when(cmsTopicMapper.selectTopicsForSettlement()).thenReturn(Collections.singletonList(t));
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(t.getId())).thenReturn(t);

        // 费率真源 settlement_rule(小数);merchant+creator ≤ 1.0 让 validateCommissionSettings 通过
        topicRule = new SettlementRule();
        topicRule.setMerchantRate(new BigDecimal("0.90"));
        topicRule.setCreatorRate(BigDecimal.ZERO);
        when(settlementRuleService.getActiveRule(0L, "topic")).thenReturn(topicRule);
    }

    @Test
    void alreadyClaimed_noSettlementProcessing() {
        // CAS 领取失败(已被结算/并发)→ 0
        when(cmsTopicMapper.claimTopicForSettlement(anyLong())).thenReturn(0);

        int settled = svc.executeTopicSettlement();

        assertEquals(0, settled, "已被领取的主题不计入本轮结算");
        // ★负控核心:领取失败必须在查销售额之前就 return,绝不进入分润流程
        verify(cmsRegistrationMapper, never()).selectTotalSalesByTopicId(anyLong());
    }

    @Test
    void claimedButNoSales_settledOnce() {
        when(cmsTopicMapper.claimTopicForSettlement(anyLong())).thenReturn(1);
        when(cmsRegistrationMapper.selectTotalSalesByTopicId(100L)).thenReturn(BigDecimal.ZERO);

        int settled = svc.executeTopicSettlement();

        assertEquals(1, settled, "领取成功但无销售额,仍算结算完成");
        // 领取只发生一次(入口置位,不再有第二处标记)
        verify(cmsTopicMapper, times(1)).claimTopicForSettlement(100L);
        verify(coopInviteService, times(1)).requestTopicDepositRefunds(100L);
    }

    @Test
    void freeExplore_isRejectedBeforeClaimAndAnySettlementRead() {
        CmsTopic t = new CmsTopic();
        t.setId(100L);
        t.setProductType(ProductType.FREE_EXPLORE);
        when(cmsTopicMapper.selectTopicsForSettlement()).thenReturn(Collections.singletonList(t));
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(t.getId())).thenReturn(t);
        when(cmsTopicMapper.claimTopicForSettlement(100L)).thenReturn(1);

        assertThrows(IllegalStateException.class, svc::executeTopicSettlement,
                "FREE_EXPLORE 必须在旧主题结算入口 fail-closed,且任务层可见");

        verify(cmsTopicMapper, never()).claimTopicForSettlement(anyLong());
        verify(cmsRegistrationMapper, never()).selectTotalSalesByTopicId(anyLong());
        verify(umsMemberService, never()).addBalance(anyLong(), any());
    }

    @Test
    void nullProductTypeWithoutSales_isClaimedAndClosedExactlyOnce() {
        CmsTopic t = new CmsTopic();
        t.setId(100L);
        t.setProductType(null);
        when(cmsTopicMapper.selectTopicsForSettlement()).thenReturn(Collections.singletonList(t));
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(t.getId())).thenReturn(t);
        when(cmsTopicMapper.claimTopicForSettlement(100L)).thenReturn(1, 0);
        when(cmsRegistrationMapper.selectTotalSalesByTopicId(100L)).thenReturn(BigDecimal.ZERO);

        assertEquals(1, svc.executeTopicSettlement(),
                "无销售 product_type=NULL 主题应一次性收口");
        assertEquals(0, svc.executeTopicSettlement(),
                "第二轮应因 claim 失败跳过");

        verify(cmsTopicMapper, times(2)).claimTopicForSettlement(100L);
        verify(cmsRegistrationMapper, times(1)).selectTotalSalesByTopicId(100L);
        verify(umsMemberService, never()).addBalance(anyLong(), any());
    }

    @Test
    void nullProductTypeWithSales_rollsBackClaimAndRemainsRetryableWithoutPayout() {
        CmsTopic t = new CmsTopic();
        t.setId(100L);
        t.setProductType(null);
        when(cmsTopicMapper.selectTopicsForSettlement()).thenReturn(Collections.singletonList(t));
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(t.getId())).thenReturn(t);
        when(cmsTopicMapper.claimTopicForSettlement(100L)).thenReturn(1);
        when(cmsRegistrationMapper.selectTotalSalesByTopicId(100L)).thenReturn(new BigDecimal("69.00"));

        ServiceException guard = assertThrows(ServiceException.class,
                () -> ReflectionTestUtils.invokeMethod(svc, "settleTopic", t, topicRule));
        assertEquals("NULL_PRODUCT_TYPE_WITH_SALES_FORBIDDEN: topicId=100, totalSales=69.00",
                guard.getMessage(), "必须由指定产品类型守卫抛错，不能由后续 NPE 假绿");
        verify(coopOrderMapper, never()).selectEffectiveOrdersByTopicId(anyLong());
        verify(umsMemberService, never()).addBalance(anyLong(), any());
        clearInvocations(cmsTopicMapper, cmsRegistrationMapper, coopOrderMapper,
                umsMemberService, transactionManager);

        assertThrows(IllegalStateException.class, svc::executeTopicSettlement,
                "有销售 product_type=NULL 必须回滚本轮 claim");
        assertThrows(IllegalStateException.class, svc::executeTopicSettlement,
                "回滚后下轮仍可重试且继续响亮失败");

        verify(cmsTopicMapper, times(2)).claimTopicForSettlement(100L);
        verify(cmsRegistrationMapper, times(2)).selectTotalSalesByTopicId(100L);
        verify(transactionManager, times(2)).rollback(any());
        verify(umsMemberService, never()).addBalance(anyLong(), any());
    }
}
