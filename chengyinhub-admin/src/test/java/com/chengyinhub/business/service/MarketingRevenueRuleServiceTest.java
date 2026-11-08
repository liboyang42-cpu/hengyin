package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.RevenueRuleDraftRequest;
import com.chengyinhub.business.domain.dto.RevenueRuleView;
import com.chengyinhub.business.domain.dto.RevenueTrialRequest;
import com.chengyinhub.business.domain.dto.RevenueTrialResult;
import com.chengyinhub.business.domain.marketing.MarketingRevenueRule;
import com.chengyinhub.business.exception.MarketingRevenueException;
import com.chengyinhub.business.mapper.MarketingRevenueRuleMapper;
import com.chengyinhub.business.service.impl.MarketingRevenueRuleServiceImpl;
import com.chengyinhub.business.service.support.RevenueRuleKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 规则服务契约：草稿可存但不参与分配；发布需受审合同与合同收款人；
 * 无配置不套默许比例；改规则不影响历史。
 */
class MarketingRevenueRuleServiceTest {

    private static final String TOPIC_KEY = "topic:42:s1";

    private MarketingRevenueRuleMapper mapper;
    private MarketingRevenueRuleServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(MarketingRevenueRuleMapper.class);
        service = new MarketingRevenueRuleServiceImpl(mapper);
    }

    private RevenueRuleDraftRequest draft(int scenario, String author, String platform) {
        RevenueRuleDraftRequest request = new RevenueRuleDraftRequest();
        request.setScenario(scenario);
        request.setCurrency("CNY");
        request.setAuthorBaseRate(new BigDecimal(author));
        request.setPlatformBaseRate(new BigDecimal(platform));
        request.setTargetType(1);
        request.setTargetId(42L);
        return request;
    }

    private RevenueRuleDraftRequest publishableDraft(int scenario, String author, String platform) {
        RevenueRuleDraftRequest request = draft(scenario, author, platform);
        request.setContractRef("contract-2026-11");
        request.setPayeeMemberId(77L);
        return request;
    }

    private MarketingRevenueRule storedRule(int status) {
        MarketingRevenueRule rule = new MarketingRevenueRule();
        rule.setId(11L);
        rule.setRuleKey(TOPIC_KEY);
        rule.setVersion(2);
        rule.setScenario(1);
        rule.setCurrency("CNY");
        rule.setAuthorBaseRate(new BigDecimal("0.50"));
        rule.setPlatformBaseRate(new BigDecimal("0.50"));
        rule.setReferralRate(BigDecimal.ZERO);
        rule.setReferralFundingSide(MarketingRevenueRule.FUNDING_NONE);
        rule.setContractRef("contract-2026-11");
        rule.setPayeeMemberId(77L);
        rule.setStatus(status);
        return rule;
    }

    @Test
    void newDraftStartsAtVersionOneAsDraftAndIsNotActive() {
        when(mapper.selectDraftByRuleKey(TOPIC_KEY)).thenReturn(null);
        when(mapper.selectMaxVersion(TOPIC_KEY)).thenReturn(null);
        when(mapper.insertRule(any())).thenAnswer(invocation -> {
            MarketingRevenueRule rule = invocation.getArgument(0);
            rule.setId(9L);
            return 1;
        });
        when(mapper.selectById(9L)).thenAnswer(invocation -> storedRule(MarketingRevenueRule.STATUS_DRAFT));

        RevenueRuleView view = service.saveDraft(1L, draft(1, "0.50", "0.50"));

        assertEquals(Integer.valueOf(MarketingRevenueRule.STATUS_DRAFT), view.getStatus());
        assertEquals(TOPIC_KEY, view.getRuleKey());
    }

    @Test
    void draftMaySaveWithoutContractButCannotBePublished() {
        // 草稿允许先存费率，但没有受审合同就不能发布 —— 发布=开始给真实订单授钱。
        when(mapper.selectDraftByRuleKey(TOPIC_KEY)).thenReturn(null);
        when(mapper.insertRule(any())).thenReturn(1);
        when(mapper.selectById(any())).thenAnswer(invocation -> {
            MarketingRevenueRule rule = storedRule(MarketingRevenueRule.STATUS_DRAFT);
            rule.setContractRef(null);
            rule.setPayeeMemberId(null);
            return rule;
        });
        service.saveDraft(1L, draft(1, "0.50", "0.50"));

        when(mapper.selectDraftByRuleKey(TOPIC_KEY)).thenAnswer(invocation -> {
            MarketingRevenueRule rule = storedRule(MarketingRevenueRule.STATUS_DRAFT);
            rule.setContractRef(null);
            rule.setPayeeMemberId(null);
            return rule;
        });
        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> service.publish(1L, TOPIC_KEY));

        assertEquals("CONTRACT_REQUIRED", error.getReasonCode());
        verify(mapper, never()).activate(any(), any(), any());
    }

    @Test
    void publishWithoutPayeeIsRejected() {
        MarketingRevenueRule draftRow = storedRule(MarketingRevenueRule.STATUS_DRAFT);
        draftRow.setPayeeMemberId(null);
        when(mapper.selectDraftByRuleKey(TOPIC_KEY)).thenReturn(draftRow);

        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> service.publish(1L, TOPIC_KEY));

        assertEquals("PAYEE_REQUIRED", error.getReasonCode());
        verify(mapper, never()).activate(any(), any(), any());
    }

    @Test
    void publishWithContractAndPayeeActivatesAfterRetiringPrevious() {
        MarketingRevenueRule draftRow = storedRule(MarketingRevenueRule.STATUS_DRAFT);
        when(mapper.selectDraftByRuleKey(TOPIC_KEY)).thenReturn(draftRow);
        when(mapper.activate(eq(11L), any(), any())).thenReturn(1);
        when(mapper.selectById(11L)).thenAnswer(invocation -> storedRule(MarketingRevenueRule.STATUS_ACTIVE));

        RevenueRuleView view = service.publish(1L, TOPIC_KEY);

        assertEquals(Integer.valueOf(MarketingRevenueRule.STATUS_ACTIVE), view.getStatus());
        verify(mapper).retireActiveByRuleKey(eq(TOPIC_KEY), any());
        verify(mapper).activate(eq(11L), any(), any());
    }

    @Test
    void ratesThatDoNotSumToOneAreRejectedAtDraftTime() {
        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> service.saveDraft(1L, draft(1, "0.50", "0.40")));
        assertEquals("RATE_NOT_CONSERVED", error.getReasonCode());
        verify(mapper, never()).insertRule(any());
    }

    @Test
    void referralRateRequiresDeclaredFundingSideAtDraftTime() {
        RevenueRuleDraftRequest request = draft(3, "0.50", "0.50");
        request.setReferralRate(new BigDecimal("0.20"));
        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> service.saveDraft(1L, request));
        assertEquals("REFERRAL_WITHOUT_FUNDING", error.getReasonCode());
    }

    @Test
    void referralRateAboveFundingSideIsRejectedAtDraftTime() {
        RevenueRuleDraftRequest request = draft(3, "0.50", "0.50");
        request.setReferralRate(new BigDecimal("0.60"));
        request.setReferralFundingSide(MarketingRevenueRule.FUNDING_PLATFORM);
        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> service.saveDraft(1L, request));
        assertEquals("REFERRAL_EXCEEDS_FUNDING", error.getReasonCode());
    }

    @Test
    void ruleKeyMustMatchScopeAndScenario() {
        RevenueRuleDraftRequest request = draft(1, "0.50", "0.50");
        request.setRuleKey("topic:99:s1");
        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> service.saveDraft(1L, request));
        assertEquals("RULE_KEY_MISMATCH", error.getReasonCode());
    }

    @Test
    void publishWithoutDraftFailsInsteadOfActivatingAnything() {
        when(mapper.selectDraftByRuleKey(TOPIC_KEY)).thenReturn(null);
        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> service.publish(1L, TOPIC_KEY));
        assertEquals("DRAFT_NOT_FOUND", error.getReasonCode());
    }

    @Test
    void resolveFallsBackFromTopicScopeToGlobalScope() {
        MarketingRevenueRule global = storedRule(MarketingRevenueRule.STATUS_ACTIVE);
        global.setRuleKey("global:s1");
        when(mapper.selectActiveByRuleKey(TOPIC_KEY)).thenReturn(null);
        when(mapper.selectActiveByRuleKey("global:s1")).thenReturn(global);

        assertEquals(global, service.resolveActive(1, 42L, 1, "CNY"));
    }

    @Test
    void noPublishedRuleMeansNoAllocationRatherThanAnImplicitRate() {
        when(mapper.selectActiveByRuleKey(anyString())).thenReturn(null);
        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> service.resolveActive(1, 42L, 1, "CNY"));
        assertEquals("RULE_NOT_FOUND", error.getReasonCode());
    }

    @Test
    void currencyMismatchIsRejectedInsteadOfConvertingSilently() {
        MarketingRevenueRule usd = storedRule(MarketingRevenueRule.STATUS_ACTIVE);
        usd.setCurrency("USD");
        when(mapper.selectActiveByRuleKey(TOPIC_KEY)).thenReturn(usd);

        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> service.resolveActive(1, 42L, 1, "CNY"));
        assertEquals("RULE_CURRENCY_MISMATCH", error.getReasonCode());
    }

    @Test
    void unsupportedCurrencyIsRejectedBeforeRuleLookup() {
        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> service.resolveActive(1, 42L, 1, "USD"));
        assertEquals("CURRENCY_UNSUPPORTED", error.getReasonCode());
        verify(mapper, never()).selectActiveByRuleKey(anyString());
    }

    @Test
    void activeRuleWithoutContractPayeeIsNotUsable() {
        MarketingRevenueRule broken = storedRule(MarketingRevenueRule.STATUS_ACTIVE);
        broken.setContractRef(null);
        when(mapper.selectActiveByRuleKey(anyString())).thenReturn(broken);

        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> service.resolveActive(1, 42L, 1, "CNY"));
        assertEquals("AUTHOR_CONTRACT_MISSING", error.getReasonCode());
    }

    @Test
    void referralScenarioWithoutFundingSideIsNotUsable() {
        MarketingRevenueRule broken = storedRule(MarketingRevenueRule.STATUS_ACTIVE);
        broken.setScenario(MarketingRevenueRule.SCENARIO_REFERRER_RECOMMENDS);
        broken.setRuleKey("global:s3");
        when(mapper.selectActiveByRuleKey(anyString())).thenReturn(broken);

        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> service.resolveActive(1, 42L, 3, "CNY"));
        assertEquals("RULE_INCOMPLETE", error.getReasonCode());
    }

    @Test
    void trialReturnsConservedFenBreakdownFromThePublishedRule() {
        MarketingRevenueRule rule = storedRule(MarketingRevenueRule.STATUS_ACTIVE);
        when(mapper.selectActiveByRuleKey(TOPIC_KEY)).thenReturn(rule);

        RevenueTrialRequest request = new RevenueTrialRequest();
        request.setRuleKey(TOPIC_KEY);
        request.setCurrency("CNY");
        request.setPaidAmount(new BigDecimal("200.00"));
        request.setTaxAmount(new BigDecimal("12.00"));

        RevenueTrialResult result = service.trial(request);

        assertEquals(0, new BigDecimal("188.00").compareTo(result.getNetAmount()));
        assertEquals(0, new BigDecimal("94.00").compareTo(result.getAuthorAmount()));
        assertEquals(0, new BigDecimal("94.00").compareTo(result.getPlatformAmount()));
        assertTrue(result.isConserved());
        assertEquals(Integer.valueOf(2), result.getRuleVersion());
        assertEquals("contract-2026-11", result.getContractRef());
    }

    @Test
    void trialWithoutReferrerDoesNotPayReferralCommission() {
        MarketingRevenueRule rule = storedRule(MarketingRevenueRule.STATUS_ACTIVE);
        rule.setScenario(MarketingRevenueRule.SCENARIO_REFERRER_RECOMMENDS);
        rule.setRuleKey("global:s3");
        rule.setReferralRate(new BigDecimal("0.20"));
        rule.setReferralFundingSide(MarketingRevenueRule.FUNDING_PLATFORM);
        when(mapper.selectActiveByRuleKey("global:s3")).thenReturn(rule);

        RevenueTrialRequest request = new RevenueTrialRequest();
        request.setRuleKey("global:s3");
        request.setCurrency("CNY");
        request.setPaidAmount(new BigDecimal("100.00"));
        request.setHasReferrer(false);

        RevenueTrialResult result = service.trial(request);

        assertEquals(0, BigDecimal.ZERO.compareTo(result.getReferrerAmount()));
        assertEquals(0, new BigDecimal("50.00").compareTo(result.getPlatformAmount()));
        assertTrue(result.isConserved());
    }

    @Test
    void trialWithReferrerPaysCommissionOutOfDeclaredSide() {
        MarketingRevenueRule rule = storedRule(MarketingRevenueRule.STATUS_ACTIVE);
        rule.setScenario(MarketingRevenueRule.SCENARIO_REFERRER_RECOMMENDS);
        rule.setRuleKey("global:s3");
        rule.setReferralRate(new BigDecimal("0.20"));
        rule.setReferralFundingSide(MarketingRevenueRule.FUNDING_PLATFORM);
        when(mapper.selectActiveByRuleKey("global:s3")).thenReturn(rule);

        RevenueTrialRequest request = new RevenueTrialRequest();
        request.setRuleKey("global:s3");
        request.setCurrency("CNY");
        request.setPaidAmount(new BigDecimal("100.00"));
        request.setHasReferrer(true);

        RevenueTrialResult result = service.trial(request);

        assertEquals(0, new BigDecimal("20.00").compareTo(result.getReferrerAmount()));
        assertEquals(0, new BigDecimal("30.00").compareTo(result.getPlatformAmount()));
        assertEquals(0, new BigDecimal("50.00").compareTo(result.getAuthorAmount()));
        assertTrue(result.isConserved());
    }

    @Test
    void resolutionChainPutsSpecificTargetBeforeGlobal() {
        assertEquals(List.of("activity:77:s3", "global:s3"),
                RevenueRuleKeys.resolutionChain(2, 77L, 3));
    }

    @Test
    void scopeParsesBackFromRuleKey() {
        assertEquals("1", RevenueRuleKeys.parseScope("topic:42:s1")[0]);
        assertEquals("42", RevenueRuleKeys.parseScope("topic:42:s1")[1]);
        assertEquals("0", RevenueRuleKeys.parseScope("global:s2")[0]);
    }
}
