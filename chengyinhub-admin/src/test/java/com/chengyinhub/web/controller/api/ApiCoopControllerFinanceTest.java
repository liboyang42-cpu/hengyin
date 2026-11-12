package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MerchantClawbackApplication;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.business.service.IMerchantClawbackService;
import com.chengyinhub.business.service.ISettlementRuleService;
import com.chengyinhub.business.service.impl.CoopInviteServiceImpl;
import com.chengyinhub.business.service.impl.PricingServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApiCoopControllerFinanceTest {
    private static final Long MEMBER = 990047L;
    private static final Long TOPIC = 71L;
    private static final String RULE_MISSING = "平台费率规则缺失(settlement_rule merchant_id=0, biz_type=topic),拒绝按默认费率计价";

    private final CoopOrderMapper orders = mock(CoopOrderMapper.class);
    private final CmsTopicMapper topics = mock(CmsTopicMapper.class);
    private final CoopSettlementMapper settlements = mock(CoopSettlementMapper.class);
    private final CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
    private final ISettlementRuleService rules = mock(ISettlementRuleService.class);
    private final IMerchantClawbackService clawbacks = mock(IMerchantClawbackService.class);
    private final IMerchantAccessService access = mock(IMerchantAccessService.class);
    private ApiCoopController controller;
    private CmsTopic ownedTopic;

    @BeforeEach
    void setUp() {
        PricingServiceImpl pricing = new PricingServiceImpl();
        ReflectionTestUtils.setField(pricing, "settlementRuleService", rules);
        CoopInviteServiceImpl finance = new CoopInviteServiceImpl();
        ReflectionTestUtils.setField(finance, "coopOrderMapper", orders);
        ReflectionTestUtils.setField(finance, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(finance, "coopSettlementMapper", settlements);
        ReflectionTestUtils.setField(finance, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(finance, "pricingService", pricing);
        controller = spy(new ApiCoopController());
        doReturn(MEMBER).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "coopInviteService", finance);
        ReflectionTestUtils.setField(controller, "merchantClawbackService", clawbacks);
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        ownedTopic = new CmsTopic();
        ownedTopic.setId(TOPIC);
        ownedTopic.setMemberId(MEMBER);
        ownedTopic.setName("finance fixture");
        ownedTopic.setLifecycle(5);
        when(orders.selectTopicIdsByFromId(MEMBER)).thenReturn(List.of(TOPIC));
        when(topics.selectCmsTopicById(TOPIC)).thenReturn(ownedTopic);
        when(settlements.selectSummaryByTopicId(TOPIC)).thenReturn(Collections.emptyList());
        when(registrations.selectTotalSalesByTopicId(TOPIC)).thenReturn(new BigDecimal("100.00"));
        when(registrations.selectVerifiedSalesByTopicId(TOPIC)).thenReturn(new BigDecimal("60.00"));
        when(clawbacks.list(MEMBER, MerchantClawbackApplication.STATUS_PENDING_REVIEW)).thenReturn(Collections.emptyList());
        when(clawbacks.list(MEMBER, MerchantClawbackApplication.STATUS_EXECUTED)).thenReturn(Collections.emptyList());
    }

    @Test
    void missingRuleReturns503WithoutFallbackAmounts() {
        AjaxResult result = controller.finance(Collections.emptyMap());
        assertEquals(503, result.get("code"));
        assertEquals(RULE_MISSING, result.get("msg"));
        assertFalse(result.containsKey("data"));
        verify(rules).getActiveRule(0L, "topic");
        verifyNoInteractions(clawbacks, access);
    }

    @Test
    void incompleteRuleAlsoReturns503() {
        when(rules.getActiveRule(0L, "topic")).thenReturn(new SettlementRule());
        AjaxResult result = controller.finance(null);
        assertEquals(503, result.get("code"));
        assertEquals(RULE_MISSING, result.get("msg"));
        assertFalse(result.containsKey("data"));
    }

    @Test
    void unrelatedIllegalStateWithSameMessagePropagates() {
        IllegalStateException failure = new IllegalStateException(RULE_MISSING);
        when(settlements.selectSummaryByTopicId(TOPIC)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> controller.finance(null)));
        verifyNoInteractions(rules, clawbacks, access);
    }

    @Test
    void unrelatedRuntimePropagates() {
        NullPointerException failure = new NullPointerException("unexpected finance bug");
        when(registrations.selectTotalSalesByTopicId(TOPIC)).thenThrow(failure);
        assertSame(failure, assertThrows(NullPointerException.class, () -> controller.finance(null)));
        verifyNoInteractions(rules, clawbacks, access);
    }

    @Test
    void lookupBugIsNotReclassifiedAsMissingConfiguration() {
        NullPointerException failure = new NullPointerException("unexpected lookup bug");
        when(rules.getActiveRule(0L, "topic")).thenThrow(failure);
        IllegalStateException propagated = assertThrows(IllegalStateException.class, () -> controller.finance(null));
        assertSame(failure, propagated.getCause());
        verifyNoInteractions(clawbacks, access);
    }

    @Test
    void successfulFinanceKeepsOwnerFilterAndLiveRateAmounts() {
        SettlementRule rule = new SettlementRule();
        rule.setPlatformCommissionRate(new BigDecimal("0.03"));
        rule.setGuaranteeBaseRate(new BigDecimal("0.05"));
        rule.setGuaranteeStepRate(new BigDecimal("0.02"));
        rule.setGuaranteeMaxMerchant(5);
        when(rules.getActiveRule(0L, "topic")).thenReturn(rule);
        CmsTopic foreign = new CmsTopic();
        foreign.setMemberId(MEMBER + 1);
        CmsTopic ownerless = new CmsTopic();
        when(topics.selectCmsTopicById(72L)).thenReturn(foreign);
        when(topics.selectCmsTopicById(73L)).thenReturn(ownerless);
        when(orders.selectTopicIdsByFromId(MEMBER)).thenReturn(Arrays.asList(72L, 73L, TOPIC));

        AjaxResult result = controller.finance(Map.of("memberId", MEMBER + 1));
        assertEquals(200, result.get("code"));
        Map<?, ?> data = (Map<?, ?>) result.get("data");
        List<?> rows = (List<?>) data.get("topics");
        assertEquals(1, rows.size());
        Map<?, ?> row = (Map<?, ?>) rows.get(0);
        assertEquals(TOPIC, row.get("topicId"));
        assertEquals(new BigDecimal("100.00"), row.get("totalSales"));
        assertEquals(new BigDecimal("60.00"), row.get("verifiedSales"));
        assertEquals(new BigDecimal("3.00"), row.get("platformAmount"));
        assertEquals(new BigDecimal("97.00"), row.get("myIncome"));
        assertEquals(Boolean.TRUE, row.get("myIncomeArrived"));
        assertEquals(Boolean.TRUE, data.get("isOwner"));
        assertEquals(BigDecimal.ZERO, ((Map<?, ?>) data.get("adjustments")).get("pendingAmount"));
        verify(orders).selectTopicIdsByFromId(MEMBER);
        verify(settlements, org.mockito.Mockito.never()).selectSummaryByTopicId(72L);
        verify(settlements, org.mockito.Mockito.never()).selectSummaryByTopicId(73L);
    }

    @Test
    void frozenRateStillWorksWhenLiveConfigurationIsMissing() {
        ownedTopic.setFeePlatformRate(new BigDecimal("0.07"));
        AjaxResult result = controller.finance(null);
        assertEquals(200, result.get("code"));
        Map<?, ?> data = (Map<?, ?>) result.get("data");
        Map<?, ?> row = (Map<?, ?>) ((List<?>) data.get("topics")).get(0);
        assertEquals(new BigDecimal("7.00"), row.get("platformAmount"));
        assertEquals(new BigDecimal("93.00"), row.get("myIncome"));
        assertTrue((Boolean) data.get("isOwner"));
        verifyNoInteractions(rules);
    }

    @Test
    void foreignAndOwnerlessTopicsCannotTriggerPricingOrExposeFinance() {
        ownedTopic.setMemberId(MEMBER + 1);
        CmsTopic ownerless = new CmsTopic();
        when(topics.selectCmsTopicById(72L)).thenReturn(ownerless);
        when(orders.selectTopicIdsByFromId(MEMBER)).thenReturn(List.of(TOPIC, 72L));
        AjaxResult result = controller.finance(null);
        assertEquals(200, result.get("code"));
        Map<?, ?> data = (Map<?, ?>) result.get("data");
        assertEquals(Collections.emptyList(), data.get("topics"));
        verifyNoInteractions(rules, settlements, registrations);
    }
}
