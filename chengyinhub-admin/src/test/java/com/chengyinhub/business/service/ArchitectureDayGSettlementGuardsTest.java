package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.SettlementClaim;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.CoopSettlementAdjustmentMapper;
import com.chengyinhub.business.mapper.CoopSettlementDetailMapper;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.business.mapper.SettlementClaimMapper;
import com.chengyinhub.business.mapper.SettlementDetailMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.business.service.impl.CoopSettlementClaimServiceImpl;
import com.chengyinhub.business.service.impl.SettlementClaimServiceImpl;
import com.chengyinhub.business.service.impl.SettlementEntryServiceImpl;
import com.chengyinhub.business.service.impl.TopicSettlementEvidenceService;
import com.chengyinhub.business.service.impl.UnavailableCoopOrderSettlementSnapshotReader;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.PurchaseKind;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** G:真实业务方法的保护/经济身份行为；Mockito 边界断言不冒充 MySQL 回滚或结算通过。 */
class ArchitectureDayGSettlementGuardsTest {
    @Test
    void positiveNonCoopSalesRemainRetryableAndNeverReachCashOrPublisherAwards() {
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        CoopOrderMapper orders = mock(CoopOrderMapper.class);
        CoopSettlementMapper settlements = mock(CoopSettlementMapper.class);
        IPointsAwardService points = mock(IPointsAwardService.class);
        IUmsMemberService members = mock(IUmsMemberService.class);
        ICoopInviteService invites = mock(ICoopInviteService.class);
        ISettlementRuleService rules = mock(ISettlementRuleService.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        CmsTopic topic = new CmsTopic();
        topic.setId(100L);
        topic.setProductType(ProductType.ORIENTEERING);
        when(topics.selectTopicsForSettlement()).thenReturn(List.of(topic));
        when(topics.selectCmsTopicByIdForUpdate(topic.getId())).thenReturn(topic);
        when(topics.claimTopicForSettlement(100L)).thenReturn(1);
        when(registrations.selectTotalSalesByTopicId(100L)).thenReturn(new BigDecimal("100.00"));
        when(orders.selectEffectiveOrdersByTopicId(100L)).thenReturn(List.of());
        SettlementRule rule = new SettlementRule();
        rule.setMerchantRate(new BigDecimal("0.9"));
        rule.setCreatorRate(BigDecimal.ZERO);
        when(rules.getActiveRule(0L, "topic")).thenReturn(rule);
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(service, "coopOrderMapper", orders);
        ReflectionTestUtils.setField(service, "coopSettlementMapper", settlements);
        ReflectionTestUtils.setField(service, "pointsAwardService", points);
        ReflectionTestUtils.setField(service, "umsMemberService", members);
        ReflectionTestUtils.setField(service, "coopInviteMapper", mock(com.chengyinhub.business.mapper.CoopInviteMapper.class));
        ReflectionTestUtils.setField(service, "coopInviteService", invites);
        ReflectionTestUtils.setField(service, "settlementRuleService", rules);
        ReflectionTestUtils.setField(service, "transactionManager", tx);

        for (int attempt = 0; attempt < 2; attempt++) {
            IllegalStateException rejection = assertThrows(IllegalStateException.class, service::executeTopicSettlement);
            assertTrue(rejection.getMessage().contains("NON_COOP_TOPIC_CASH_SETTLEMENT_UNPROVEN"),
                    "必须是指定保护拒绝，不能让后续 NPE 伪装成 fail-closed");
        }

        verify(topics, times(2)).claimTopicForSettlement(100L);
        verify(tx, times(2)).rollback(any());
        verify(tx, never()).commit(any());
        verifyNoInteractions(settlements, points, members, invites);
    }

    @Test
    void unavailableSnapshotDoesNotLockEvidenceOrCreateAZeroCashTerminal() {
        FulfillmentWindowResolver window = mock(FulfillmentWindowResolver.class);
        TopicSettlementEvidenceService evidence = mock(TopicSettlementEvidenceService.class);
        ISettlementClaimService claims = mock(ISettlementClaimService.class);
        CoopSettlementMapper heads = mock(CoopSettlementMapper.class);
        CoopSettlementDetailMapper details = mock(CoopSettlementDetailMapper.class);
        CoopSettlementAdjustmentMapper adjustments = mock(CoopSettlementAdjustmentMapper.class);
        CoopSettlementClaimServiceImpl service = new CoopSettlementClaimServiceImpl(
                new UnavailableCoopOrderSettlementSnapshotReader(), window, evidence, claims, heads, details, adjustments);
        CoopOrder order = new CoopOrder();
        order.setId(11L);
        order.setStatus(0);
        order.setDelFlag(0);

        IllegalStateException rejection = assertThrows(IllegalStateException.class,
                () -> service.prepare(order, new Date(1_000_000L)));

        assertTrue(rejection.getMessage().startsWith("MANUAL_REPAIR:"));
        verifyNoInteractions(window, evidence, claims, heads, details, adjustments);
    }

    @Test
    void exploreOrderCannotAccrueAsWholeTicketEvenFromADirectServiceCaller() {
        SettlementEntryServiceImpl service = new SettlementEntryServiceImpl();
        SettlementDetailMapper details = mock(SettlementDetailMapper.class);
        ISettlementClaimService claims = mock(ISettlementClaimService.class);
        ISettlementRuleService rules = mock(ISettlementRuleService.class);
        ReflectionTestUtils.setField(service, "settlementDetailMapper", details);
        ReflectionTestUtils.setField(service, "settlementClaimService", claims);
        ReflectionTestUtils.setField(service, "settlementRuleService", rules);
        CmsRegistration registration = registration(91L, 2L, 20L);
        registration.setPurchaseKind(PurchaseKind.EXPLORE_PASS);

        IllegalStateException rejection = assertThrows(IllegalStateException.class,
                () -> service.recordPaidRegistration(registration, 9L));

        assertTrue(rejection.getMessage().contains("探索票不能整单入账"));
        verifyNoInteractions(details, claims, rules);
    }

    @Test
    void directActivityClaimUsesItsOwnerNotItsLinkedTopicAsEconomicContext() {
        SettlementEntryServiceImpl service = new SettlementEntryServiceImpl();
        SettlementDetailMapper details = mock(SettlementDetailMapper.class);
        ISettlementClaimService claims = mock(ISettlementClaimService.class);
        ISettlementRuleService rules = mock(ISettlementRuleService.class);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsActivityMapper activities = mock(CmsActivityMapper.class);
        ReflectionTestUtils.setField(service, "settlementDetailMapper", details);
        ReflectionTestUtils.setField(service, "settlementClaimService", claims);
        ReflectionTestUtils.setField(service, "settlementRuleService", rules);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(service, "coopOrderMapper", mock(CoopOrderMapper.class));
        SettlementRule rule = new SettlementRule();
        rule.setBizType("activity");
        rule.setCreatorRate(BigDecimal.ZERO);
        when(rules.getActiveRule(0L, "activity")).thenReturn(rule);
        AtomicLong nextId = new AtomicLong(1);
        when(claims.claim(any())).thenAnswer(invocation -> {
            SettlementClaim claim = invocation.getArgument(0);
            claim.setId(nextId.getAndIncrement());
            return claim;
        });
        when(details.insertAdjustment(any())).thenReturn(1);
        CmsRegistration registration = registration(91L, 2L, 20L);
        registration.setTopicId(999L);

        assertEquals(2, service.recordPaidRegistration(registration, 9L));

        ArgumentCaptor<SettlementClaim> captured = ArgumentCaptor.forClass(SettlementClaim.class);
        verify(claims, times(2)).claim(captured.capture());
        SettlementClaim merchant = captured.getAllValues().stream()
                .filter(claim -> "MERCHANT".equals(claim.getBeneficiaryRole())).findFirst().orElseThrow();
        assertEquals("ACTIVITY", merchant.getCommercialContextType());
        assertEquals(20L, merchant.getCommercialContextId().longValue());
        assertEquals("ACTIVITY|20|REGISTRATION|91|MERCHANT|9|MERCHANT_PARTICIPATION", merchant.economicKey());
        assertEquals("DIRECT_REGISTRATION", merchant.getSourceContractType());
        assertEquals(91L, merchant.getSourceContractId().longValue());
    }

    @Test
    void existingEconomicClaimSurvivesAResponseRetryButRejectsAnotherContract() {
        SettlementClaimMapper mapper = mock(SettlementClaimMapper.class);
        SettlementClaimServiceImpl service = new SettlementClaimServiceImpl(mapper);
        SettlementClaim original = claim("COOP_ORDER", "COOP_ORDER", 11L);
        original.setId(77L);
        when(mapper.insertClaim(any())).thenThrow(new DuplicateKeyException("uk_settlement_claim_key"));
        when(mapper.selectByEconomicKeyForUpdate(any())).thenReturn(original);

        assertSame(original, service.claim(claim("COOP_ORDER", "COOP_ORDER", 11L)));
        IllegalStateException conflict = assertThrows(IllegalStateException.class,
                () -> service.claim(claim("REGISTRATION_SPLIT", "DIRECT_REGISTRATION", 91L)));
        IllegalStateException otherContract = assertThrows(IllegalStateException.class,
                () -> service.claim(claim("COOP_ORDER", "COOP_ORDER", 12L)));

        assertTrue(conflict.getMessage().startsWith("LEDGER_CONFLICT:"));
        assertTrue(otherContract.getMessage().startsWith("LEDGER_CONFLICT:"),
                "同 route 也不能用另一个合同占同一经济身份");
        assertEquals(77L, original.getId().longValue());
        assertEquals("COOP_ORDER", original.getSettlementRoute());
        assertEquals(11L, original.getSourceContractId().longValue());
    }

    private CmsRegistration registration(long id, long ownerType, long ownerId) {
        CmsRegistration registration = new CmsRegistration();
        registration.setId(id);
        registration.setOwnerType(ownerType);
        registration.setOwnerId(ownerId);
        registration.setPurchaseKind(PurchaseKind.GUIDED_TICKET);
        registration.setPayableAmount(new BigDecimal("100.00"));
        registration.setFeePlatformRate(new BigDecimal("0.10"));
        return registration;
    }

    private SettlementClaim claim(String route, String contract, long contractId) {
        return SettlementClaim.request("ACTIVITY", 20L, "REGISTRATION", 91L,
                "MERCHANT", 9L, "MERCHANT_PARTICIPATION", route, contract, contractId);
    }
}
