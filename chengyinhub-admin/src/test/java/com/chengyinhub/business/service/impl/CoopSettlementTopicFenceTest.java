package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopSettlement;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Behavioral scope/zero-effect controls; not a database concurrency result. */
class CoopSettlementTopicFenceTest {
    @Test
    void staleClassicScanCannotSettleCurrentlyExploreTopic() {
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsTopic stale = topic(11L); stale.setProductType(1);
        CmsTopic current = topic(11L); current.setProductType(ProductType.FREE_EXPLORE);
        when(topics.selectCmsTopicByIdForUpdate(11L)).thenReturn(current);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "settleTopic", stale, new SettlementRule()));
        assertTrue(error.getMessage().contains("FREE_EXPLORE_LEGACY_SETTLEMENT_FORBIDDEN"));
        verify(topics, never()).claimTopicForSettlement(anyLong());
    }

    @Test
    void ruleSelectionUsesCurrentLockedClubInsteadOfScanSnapshot() {
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        ISettlementRuleService rules = mock(ISettlementRuleService.class);
        CmsTopic stale = topic(11L); stale.setProductType(1);
        CmsTopic current = topic(11L); current.setProductType(1); current.setClubId(77L);
        when(topics.selectCmsTopicByIdForUpdate(11L)).thenReturn(current);
        SettlementRule valid = new SettlementRule(); valid.setMerchantRate(new BigDecimal("0.90")); valid.setCreatorRate(BigDecimal.ZERO);
        when(rules.getActiveRule(0L, "topic_club")).thenReturn(valid);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(service, "settlementRuleService", rules);
        assertEquals(Boolean.FALSE, ReflectionTestUtils.invokeMethod(service, "settleTopic", stale, valid));
        var order = inOrder(topics, rules);
        order.verify(topics).selectCmsTopicByIdForUpdate(11L);
        order.verify(rules).getActiveRule(0L, "topic_club");
        order.verify(topics).claimTopicForSettlement(11L);
    }

    @Test
    void changedPayoutHeadScopeRejectsBeforeAnyEconomicEffect() {
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CoopSettlementMapper heads = mock(CoopSettlementMapper.class);
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        CoopSettlement head = new CoopSettlement(); head.setId(5L); head.setTopicId(99L); head.setAmount(BigDecimal.TEN);
        Date now = new Date();
        when(topics.selectCmsTopicByIdForUpdate(11L)).thenReturn(topic(11L));
        when(heads.selectDuePayoutForUpdate(5L, now)).thenReturn(head);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(service, "coopSettlementMapper", heads);
        ReflectionTestUtils.setField(service, "umsMemberMapper", members);
        ReflectionTestUtils.setField(service, "coopSettlementOriginalMapper", mock(com.chengyinhub.business.mapper.CoopSettlementOriginalMapper.class));
        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "payoutOneDueCoopSettlement", 5L, 11L, now));
        var order = inOrder(topics, heads);
        order.verify(topics).selectCmsTopicByIdForUpdate(11L);
        order.verify(heads).selectDuePayoutForUpdate(5L, now);
        verifyNoInteractions(members);
        verify(heads, never()).markPaidIfPending(anyLong(), any());
    }

    @Test
    void missingPayoutTopicCannotConsumeEvenZeroHead() {
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        CoopSettlementMapper heads = mock(CoopSettlementMapper.class);
        ReflectionTestUtils.setField(service, "coopSettlementMapper", heads);
        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "payoutOneDueCoopSettlement", 5L, null, new Date()));
        verifyNoInteractions(heads);
    }

    @Test
    void adjustmentHeadChangedAfterDiscoveryCannotAppendToForeignTopic() {
        CoopSettlementMapper heads = mock(CoopSettlementMapper.class);
        CoopSettlementDetailMapper details = mock(CoopSettlementDetailMapper.class);
        CoopSettlementAdjustmentMapper adjustments = mock(CoopSettlementAdjustmentMapper.class);
        CoopSettlementClaimServiceImpl service = new CoopSettlementClaimServiceImpl(null, null, null, null, heads, details, adjustments);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        ReflectionTestUtils.setField(service, "topicMapper", topics);
        when(topics.selectCmsTopicByIdForUpdate(11L)).thenReturn(topic(11L));
        CoopSettlement discovered = new CoopSettlement(); discovered.setId(5L); discovered.setTopicId(11L);
        CoopSettlement changed = new CoopSettlement(); changed.setId(5L); changed.setTopicId(99L);
        when(heads.selectByClaimId(31L)).thenReturn(discovered);
        when(heads.selectByIdForUpdate(5L)).thenReturn(changed);
        assertThrows(IllegalStateException.class, () -> service.appendExecutedAdjustment(31L, "ADJUST:31:1", new BigDecimal("-2.00")));
        var order = inOrder(heads, topics);
        order.verify(heads).selectByClaimId(31L);
        order.verify(topics).selectCmsTopicByIdForUpdate(11L);
        order.verify(heads).selectByIdForUpdate(5L);
        verifyNoInteractions(details, adjustments);
    }

    @Test
    void prepareAndPersistCannotOpenSeparateTransactionsAroundEvidenceLocks() {
        DataSource dataSource = mock(DataSource.class);
        CoopSettlementMapper heads = mock(CoopSettlementMapper.class);
        CoopSettlementClaimServiceImpl service = new CoopSettlementClaimServiceImpl(null, null, null, null, heads, null, null);
        ProxyFactory proxy = new ProxyFactory(service);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        ICoopSettlementClaimService owner = (ICoopSettlementClaimService) proxy.getProxy();
        assertThrows(IllegalTransactionStateException.class, () -> owner.prepare(null, new Date()));
        assertThrows(IllegalTransactionStateException.class, () -> owner.persist(null, null));
        verifyNoInteractions(heads, dataSource);
    }

    private static CmsTopic topic(Long id) {
        CmsTopic row = new CmsTopic(); row.setId(id); return row;
    }
}
