package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MarketingAttribution;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.RevenueAccrualRequest;
import com.chengyinhub.business.domain.dto.RevenueAccrualResult;
import com.chengyinhub.business.domain.dto.RevenueReversalRequest;
import com.chengyinhub.business.domain.dto.RevenueReversalResult;
import com.chengyinhub.business.domain.marketing.MarketingRevenueAllocation;
import com.chengyinhub.business.domain.marketing.MarketingRevenueAllocationLine;
import com.chengyinhub.business.domain.marketing.MarketingRevenueRule;
import com.chengyinhub.business.exception.MarketingRevenueException;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.MarketingAttributionMapper;
import com.chengyinhub.business.mapper.MarketingRevenueAllocationLineMapper;
import com.chengyinhub.business.mapper.MarketingRevenueAllocationMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.service.impl.MarketingRevenueAllocationServiceImpl;
import com.chengyinhub.business.service.support.RevenueFactSource;
import com.chengyinhub.business.service.support.RevenueSnapshotHash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分配入账/冲销契约（root 第 1–6 条的反例集）。
 *
 * <p>重点：
 * <ul>
 *   <li>应计时先读既有快照，规则被改也不会让旧重试失败或被重新定价；</li>
 *   <li>实付来自已验证支付事实，不用 paymentStatus/total 猜；</li>
 *   <li>收款人来自受审合同，不等于内容 owner；</li>
 *   <li>冲销先按 refundId 命中幂等事实，再谈累计封顶；同 ID 异 payload 拒绝；</li>
 *   <li>缺通道退款事实时拒绝，不凭后台批准冲减。</li>
 * </ul>
 * 不证明事务回滚与并发唯一键竞争 —— 那是 MySQL IT 的事。
 */
class MarketingRevenueAllocationServiceTest {

    private static final long REGISTRATION_ID = 555L;
    private static final long CONTENT_OWNER = 9L;
    private static final long CONTRACT_PAYEE = 77L;
    private static final long OTHER_SHARER = 21L;
    private static final long TOPIC_ID = 42L;
    private static final String FACT_HASH = "a".repeat(64);

    private MarketingRevenueAllocationMapper allocations;
    private MarketingRevenueAllocationLineMapper lines;
    private MarketingAttributionMapper attributions;
    private CmsRegistrationMapper registrations;
    private RefundApplicationMapper refunds;
    private ICmsTopicService topics;
    private ICmsActivityService activities;
    private IMarketingRevenueRuleService rules;
    private IUmsMemberService members;
    private RevenueFactSource factSource;
    private MarketingRevenueAllocationServiceImpl service;

    @BeforeEach
    void setUp() {
        allocations = mock(MarketingRevenueAllocationMapper.class);
        lines = mock(MarketingRevenueAllocationLineMapper.class);
        attributions = mock(MarketingAttributionMapper.class);
        registrations = mock(CmsRegistrationMapper.class);
        refunds = mock(RefundApplicationMapper.class);
        topics = mock(ICmsTopicService.class);
        activities = mock(ICmsActivityService.class);
        rules = mock(IMarketingRevenueRuleService.class);
        members = mock(IUmsMemberService.class);
        factSource = mock(RevenueFactSource.class);
        UmsMember payee = new UmsMember();
        payee.setId(CONTRACT_PAYEE);
        payee.setStatus(1L);
        payee.setDelFlag(0);
        when(members.selectUmsMemberById(CONTRACT_PAYEE)).thenReturn(payee);
        service = new MarketingRevenueAllocationServiceImpl(allocations, lines, attributions,
                registrations, refunds, topics, activities, rules, members, factSource);
    }

    // ------------------------------------------------------------------ fixtures

    private CmsRegistration registration() {
        CmsRegistration registration = new CmsRegistration();
        registration.setId(REGISTRATION_ID);
        registration.setRegistrationNo("T20261108ABC");
        registration.setTransactionId("WXTX123");
        registration.setOwnerType(1L);
        registration.setOwnerId(TOPIC_ID);
        registration.setMemberId(777L);
        registration.setPaymentStatus(2);
        registration.setOriginalAmount(new BigDecimal("120.00"));
        registration.setDiscountAmount(new BigDecimal("20.00"));
        registration.setTotalAmount(new BigDecimal("100.00"));
        registration.setPointDeductAmount(BigDecimal.ZERO);
        registration.setPayableAmount(new BigDecimal("100.00"));
        registration.setWechatPaymentAmount(new BigDecimal("100.00"));
        return registration;
    }

    private MarketingRevenueRule rule(int scenario, String author, String platform,
                                      String referral, int fundingSide) {
        MarketingRevenueRule rule = new MarketingRevenueRule();
        rule.setId(3L);
        rule.setRuleKey("topic:" + TOPIC_ID + ":s" + scenario);
        rule.setVersion(2);
        rule.setScenario(scenario);
        rule.setCurrency("CNY");
        rule.setAuthorBaseRate(new BigDecimal(author));
        rule.setPlatformBaseRate(new BigDecimal(platform));
        rule.setReferralRate(new BigDecimal(referral));
        rule.setReferralFundingSide(fundingSide);
        rule.setHoldDays(14);
        rule.setContractRef("contract-2026-11");
        rule.setPayeeMemberId(CONTRACT_PAYEE);
        return rule;
    }

    private RevenueAccrualRequest accrualRequest() {
        RevenueAccrualRequest request = new RevenueAccrualRequest();
        request.setRegistrationId(REGISTRATION_ID);
        request.setTaxAmount(new BigDecimal("6.00"));
        request.setPaymentFeeAmount(new BigDecimal("0.60"));
        request.setChannelCommissionAmount(BigDecimal.ZERO);
        request.setFeeBasisRef("contract-2026-11:tax-fee-v1");
        request.setSourceFact("payment-callback");
        return request;
    }

    private void stubContentOwner() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setMemberId(CONTENT_OWNER);
        when(topics.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
    }

    private void stubPaymentFact() {
        when(factSource.verifiedPayment(any())).thenReturn(Optional.of(
                new RevenueFactSource.PaymentFact(10000L, "CNY", FACT_HASH, "WXTX123")));
    }

    private void stubInsertAllocation() {
        when(allocations.insertAllocation(any())).thenAnswer(invocation -> {
            MarketingRevenueAllocation row = invocation.getArgument(0);
            row.setId(88L);
            return 1;
        });
        when(lines.batchInsert(any())).thenAnswer(invocation -> {
            List<MarketingRevenueAllocationLine> batch = invocation.getArgument(0);
            long id = 900L;
            for (MarketingRevenueAllocationLine line : batch) {
                line.setId(id++);
            }
            return batch.size();
        });
    }

    private void stubReadyAccrual(int scenario, String author, String platform,
                                  String referral, int fundingSide) {
        when(registrations.selectCmsRegistrationById(REGISTRATION_ID)).thenReturn(registration());
        stubPaymentFact();
        stubContentOwner();
        when(attributions.selectByOrder(anyInt(), anyLong())).thenReturn(null);
        when(rules.resolveActive(1, TOPIC_ID, scenario, "CNY"))
                .thenReturn(rule(scenario, author, platform, referral, fundingSide));
        stubInsertAllocation();
    }

    // ------------------------------------------------------------------ 应计

    @Test
    void platformResaleSplitsNetBetweenContractPayeeAndPlatform() {
        stubReadyAccrual(1, "0.50", "0.50", "0", MarketingRevenueRule.FUNDING_NONE);

        RevenueAccrualResult result = service.accrue(accrualRequest());

        // 100.00 − 6.00 税 − 0.60 支付费 = 93.40 净额，各 46.70。
        assertEquals(0, new BigDecimal("93.40").compareTo(result.getNetAmount()));
        assertEquals(0, new BigDecimal("46.70").compareTo(result.getAuthorAmount()));
        assertEquals(0, new BigDecimal("46.70").compareTo(result.getPlatformAmount()));
        assertEquals(0, new BigDecimal("0.00").compareTo(result.getReferrerAmount()));
        assertEquals(result.getNetAmount(), result.getAuthorAmount()
                .add(result.getReferrerAmount()).add(result.getPlatformAmount()));
        assertEquals(Long.valueOf(CONTRACT_PAYEE), result.getAuthorMemberId(),
                "收款人必须是合同声明的权利人，不是内容 owner");
        assertEquals("contract-2026-11", result.getContractRef());
    }

    @Test
    void contentOwnerIsNeverUsedAsPayeeWhenContractPayeeDiffers() {
        stubReadyAccrual(1, "0.50", "0.50", "0", MarketingRevenueRule.FUNDING_NONE);

        RevenueAccrualResult result = service.accrue(accrualRequest());

        assertFalse(Long.valueOf(CONTENT_OWNER).equals(result.getAuthorMemberId()));
        assertEquals(Long.valueOf(CONTRACT_PAYEE), result.getAuthorMemberId());
    }

    @Test
    void ruleWithoutContractPayeeIsRejectedBeforeAnyWrite() {
        when(registrations.selectCmsRegistrationById(REGISTRATION_ID)).thenReturn(registration());
        stubPaymentFact();
        stubContentOwner();
        when(attributions.selectByOrder(anyInt(), anyLong())).thenReturn(null);
        MarketingRevenueRule noPayee = rule(1, "0.50", "0.50", "0", MarketingRevenueRule.FUNDING_NONE);
        noPayee.setPayeeMemberId(null);
        when(rules.resolveActive(anyInt(), anyLong(), anyInt(), anyString())).thenReturn(noPayee);

        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> service.accrue(accrualRequest()));

        assertEquals("AUTHOR_CONTRACT_MISSING", error.getReasonCode());
        verify(allocations, never()).insertAllocation(any());
    }

    @Test
    void unusableContractPayeeStopsAccrual() {
        when(registrations.selectCmsRegistrationById(REGISTRATION_ID)).thenReturn(registration());
        stubPaymentFact();
        stubContentOwner();
        when(attributions.selectByOrder(anyInt(), anyLong())).thenReturn(null);
        when(rules.resolveActive(anyInt(), anyLong(), anyInt(), anyString()))
                .thenReturn(rule(1, "0.50", "0.50", "0", MarketingRevenueRule.FUNDING_NONE));
        when(members.selectUmsMemberById(CONTRACT_PAYEE)).thenReturn(null);

        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> service.accrue(accrualRequest()));

        assertEquals("PAYEE_MEMBER_UNUSABLE", error.getReasonCode());
        verify(allocations, never()).insertAllocation(any());
    }

    @Test
    void disabledContractPayeeStopsAccrual() {
        when(registrations.selectCmsRegistrationById(REGISTRATION_ID)).thenReturn(registration());
        stubPaymentFact();
        stubContentOwner();
        when(attributions.selectByOrder(anyInt(), anyLong())).thenReturn(null);
        when(rules.resolveActive(anyInt(), anyLong(), anyInt(), anyString()))
                .thenReturn(rule(1, "0.50", "0.50", "0", MarketingRevenueRule.FUNDING_NONE));
        UmsMember disabled = new UmsMember();
        disabled.setId(CONTRACT_PAYEE);
        disabled.setStatus(2L); // 非 1 = 停用
        disabled.setDelFlag(0);
        when(members.selectUmsMemberById(CONTRACT_PAYEE)).thenReturn(disabled);

        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> service.accrue(accrualRequest()));
        assertEquals("PAYEE_MEMBER_UNUSABLE", error.getReasonCode());
    }

    @Test
    void missingPaymentFactStopsAccrualInsteadOfGuessingFromOrderAmount() {
        when(registrations.selectCmsRegistrationById(REGISTRATION_ID)).thenReturn(registration());
        when(factSource.verifiedPayment(any())).thenReturn(Optional.empty());

        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> service.accrue(accrualRequest()));

        assertEquals("PAYMENT_FACT_MISSING", error.getReasonCode());
        verify(allocations, never()).insertAllocation(any());
        // 即便订单上 payment_status=2、total=100.00，也不得拿来当实付。
        verify(rules, never()).resolveActive(anyInt(), anyLong(), anyInt(), anyString());
    }

    @Test
    void missingFeeBasisIsRejectedInsteadOfAssumingZero() {
        when(registrations.selectCmsRegistrationById(REGISTRATION_ID)).thenReturn(registration());
        stubPaymentFact();
        RevenueAccrualRequest request = accrualRequest();
        request.setFeeBasisRef("  ");

        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> service.accrue(request));

        assertEquals("FEE_BASIS_MISSING", error.getReasonCode());
        verify(allocations, never()).insertAllocation(any());
    }

    @Test
    void existingSnapshotIsReadBeforeAnyRuleResolution() {
        MarketingRevenueAllocation existing = storedAllocation();
        when(allocations.selectByOrder(MarketingRevenueAllocation.ORDER_REGISTRATION, REGISTRATION_ID))
                .thenReturn(existing);
        when(lines.selectByAllocationId(88L)).thenReturn(storedLines());

        RevenueAccrualResult result = service.accrue(accrualRequest());

        assertTrue(result.isReplayed());
        assertEquals(Long.valueOf(88L), result.getAllocationId());
        // 关键：完全没有解析当前规则 —— 所以规则被改/退役都不会影响旧重试。
        verify(rules, never()).resolveActive(anyInt(), anyLong(), anyInt(), anyString());
        verify(registrations, never()).selectCmsRegistrationById(anyLong());
    }

    @Test
    void retryAfterRuleRetiredStillReplaysOriginalSnapshot() {
        MarketingRevenueAllocation existing = storedAllocation();
        when(allocations.selectByOrder(anyInt(), anyLong())).thenReturn(existing);
        when(lines.selectByAllocationId(88L)).thenReturn(storedLines());
        // 规则已退役：resolveActive 会抛 RULE_NOT_FOUND。旧重试不应受影响。
        when(rules.resolveActive(anyInt(), anyLong(), anyInt(), anyString()))
                .thenThrow(MarketingRevenueException.notFound("RULE_NOT_FOUND", "已退役"));

        RevenueAccrualResult result = service.accrue(accrualRequest());

        assertTrue(result.isReplayed(), "规则退役后旧重试必须仍能重放原快照");
        assertEquals(Integer.valueOf(2), result.getRuleVersion());
    }

    @Test
    void sameOrderWithDifferentDeductionPayloadConflicts() {
        MarketingRevenueAllocation existing = storedAllocation();
        when(allocations.selectByOrder(anyInt(), anyLong())).thenReturn(existing);
        RevenueAccrualRequest changed = accrualRequest();
        changed.setTaxAmount(new BigDecimal("7.00"));

        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> service.accrue(changed));

        assertEquals("ACCRUAL_CONFLICT", error.getReasonCode());
    }

    @Test
    void sameOrderWithDifferentFeeEvidenceConflicts() {
        MarketingRevenueAllocation existing = storedAllocation();
        when(allocations.selectByOrder(anyInt(), anyLong())).thenReturn(existing);
        RevenueAccrualRequest changed = accrualRequest();
        changed.setFeeBasisRef("different-evidence");

        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> service.accrue(changed));

        assertEquals("ACCRUAL_CONFLICT", error.getReasonCode());
    }

    @Test
    void legacySnapshotWithoutFactIdentityIsNotSilentlyReplayed() {
        MarketingRevenueAllocation existing = storedAllocation();
        existing.setPaymentFactHash(null);
        when(allocations.selectByOrder(anyInt(), anyLong())).thenReturn(existing);

        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> service.accrue(accrualRequest()));

        assertEquals("ACCRUAL_LEGACY_SNAPSHOT", error.getReasonCode());
    }

    @Test
    void referrerScenarioPaysCommissionOutOfDeclaredSideOnly() {
        when(registrations.selectCmsRegistrationById(REGISTRATION_ID)).thenReturn(registration());
        stubPaymentFact();
        stubContentOwner();
        MarketingAttribution attribution = new MarketingAttribution();
        attribution.setId(13L);
        attribution.setSharerMemberId(OTHER_SHARER);
        when(attributions.selectByOrder(anyInt(), anyLong())).thenReturn(attribution);
        when(rules.resolveActive(1, TOPIC_ID, MarketingRevenueRule.SCENARIO_REFERRER_RECOMMENDS, "CNY"))
                .thenReturn(rule(3, "0.50", "0.50", "0.20", MarketingRevenueRule.FUNDING_PLATFORM));
        stubInsertAllocation();

        RevenueAccrualResult result = service.accrue(accrualRequest());

        assertEquals(Integer.valueOf(MarketingRevenueRule.SCENARIO_REFERRER_RECOMMENDS), result.getScenario());
        assertEquals(0, new BigDecimal("46.70").compareTo(result.getAuthorAmount()));
        assertEquals(0, new BigDecimal("18.68").compareTo(result.getReferrerAmount()));
        // 平台 46.70 − 推荐 18.68 = 28.02：剩余 80% 不是平台最终收入。
        assertEquals(0, new BigDecimal("28.02").compareTo(result.getPlatformAmount()));
        assertEquals(result.getNetAmount(), result.getAuthorAmount()
                .add(result.getReferrerAmount()).add(result.getPlatformAmount()));
    }

    @Test
    void platformLineIsNeverAttributedToAMember() {
        stubReadyAccrual(1, "0.50", "0.50", "0", MarketingRevenueRule.FUNDING_NONE);

        RevenueAccrualResult result = service.accrue(accrualRequest());

        for (RevenueAccrualResult.Line line : result.getLines()) {
            if (MarketingRevenueAllocationLine.ROLE_PLATFORM.equals(line.getRole())) {
                assertNull(line.getRecipientMemberId());
            }
        }
    }

    @Test
    void discountSnapshotIsRecordedButNeverDeductedAgain() {
        stubReadyAccrual(1, "0.50", "0.50", "0", MarketingRevenueRule.FUNDING_NONE);
        ArgumentCaptor<MarketingRevenueAllocation> captor =
                ArgumentCaptor.forClass(MarketingRevenueAllocation.class);

        service.accrue(accrualRequest());

        verify(allocations).insertAllocation(captor.capture());
        MarketingRevenueAllocation written = captor.getValue();
        assertEquals(Long.valueOf(2000L), written.getDiscountFen(), "折扣 20.00 只作快照");
        assertEquals(Long.valueOf(10000L), written.getPaidFen(), "实付取已验证事实 100.00");
        assertEquals(Long.valueOf(9340L), written.getNetFen(), "净额 93.40，不是 80 − 扣项");
    }

    @Test
    void missingContentStopsAccrual() {
        when(registrations.selectCmsRegistrationById(REGISTRATION_ID)).thenReturn(registration());
        stubPaymentFact();
        when(topics.selectCmsTopicById(TOPIC_ID)).thenReturn(null);

        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> service.accrue(accrualRequest()));
        assertEquals("CONTENT_NOT_FOUND", error.getReasonCode());
    }

    // ------------------------------------------------------------------ 冲销

    private MarketingRevenueAllocation storedAllocation() {
        MarketingRevenueAllocation allocation = new MarketingRevenueAllocation();
        allocation.setId(88L);
        allocation.setOrderType(MarketingRevenueAllocation.ORDER_REGISTRATION);
        allocation.setOrderId(REGISTRATION_ID);
        allocation.setAllocationKey("registration:" + REGISTRATION_ID);
        allocation.setRuleId(3L);
        allocation.setRuleKey("topic:" + TOPIC_ID + ":s1");
        allocation.setRuleVersion(2);
        allocation.setScenario(1);
        allocation.setAuthorMemberId(CONTRACT_PAYEE);
        allocation.setContractRef("contract-2026-11");
        allocation.setOwnerType(1);
        allocation.setOwnerId(TOPIC_ID);
        allocation.setCurrency("CNY");
        allocation.setPaidFen(10000L);
        allocation.setPaymentFactHash(FACT_HASH);
        allocation.setOriginalAmountFen(12000L);
        allocation.setDiscountFen(2000L);
        allocation.setPointDeductFen(0L);
        allocation.setTaxFen(600L);
        allocation.setPaymentFeeFen(60L);
        allocation.setChannelCommissionFen(0L);
        allocation.setFeeBasisRef("contract-2026-11:tax-fee-v1");
        allocation.setNetFen(9340L);
        allocation.setReferralFundingSide(MarketingRevenueRule.FUNDING_NONE);
        allocation.setStatus(MarketingRevenueAllocation.STATUS_ACCRUED);
        allocation.setReversedFen(0L);
        allocation.setSnapshotHash(RevenueSnapshotHash.of(allocation));
        return allocation;
    }

    private List<MarketingRevenueAllocationLine> storedLines() {
        List<MarketingRevenueAllocationLine> rows = new ArrayList<>();
        rows.add(accrualLine(901L, MarketingRevenueAllocationLine.ROLE_AUTHOR, CONTRACT_PAYEE, 4670L));
        rows.add(accrualLine(902L, MarketingRevenueAllocationLine.ROLE_PLATFORM, null, 4670L));
        return rows;
    }

    private MarketingRevenueAllocationLine accrualLine(long id, String role, Long recipient, long fen) {
        MarketingRevenueAllocationLine line = new MarketingRevenueAllocationLine();
        line.setId(id);
        line.setAllocationId(88L);
        line.setEntryType(MarketingRevenueAllocationLine.ENTRY_ACCRUE);
        line.setLineRole(role);
        line.setRecipientMemberId(recipient);
        line.setBasisFen(9340L);
        line.setAmountFen(fen);
        line.setCurrency("CNY");
        line.setEntryKey("accrue:" + role);
        return line;
    }

    private RefundApplication refund(long id, String amount) {
        RefundApplication refund = new RefundApplication();
        refund.setId(id);
        refund.setSourceType("registration");
        refund.setSourceId(REGISTRATION_ID);
        refund.setRefundNo("RF" + id);
        refund.setRefundAmount(new BigDecimal(amount));
        refund.setPayoutStatus(RefundApplication.PAYOUT_SUCCESS);
        return refund;
    }

    private RevenueReversalRequest reversalRequest(long refundId) {
        RevenueReversalRequest request = new RevenueReversalRequest();
        request.setRefundId(refundId);
        request.setEventType("refund");
        return request;
    }

    @Test
    void reversalUsesRefundRecordAsSourceOfTruthNotTheRequest() {
        when(refunds.selectRefundApplicationById(7001L)).thenReturn(refund(7001L, "50.00"));
        when(allocations.selectByOrderForUpdate(anyInt(), anyLong())).thenReturn(storedAllocation());
        when(registrations.selectCmsRegistrationById(REGISTRATION_ID)).thenReturn(registration());
        when(factSource.verifiedRefund(any(), any())).thenReturn(Optional.of(
                new RevenueFactSource.RefundFact(5000L, 10000L, "CNY", "b".repeat(64), "RF7001")));
        when(lines.selectByAllocationId(88L)).thenReturn(storedLines());
        when(lines.selectByEvent(88L, "refund:7001")).thenReturn(new ArrayList<>());
        when(lines.batchInsert(any())).thenAnswer(invocation -> {
            List<MarketingRevenueAllocationLine> batch = invocation.getArgument(0);
            return batch.size();
        });
        when(allocations.updateAfterReversal(anyLong(), any(), anyInt(), any())).thenReturn(1);
        MarketingRevenueAllocation updated = storedAllocation();
        updated.setReversedFen(4670L);
        updated.setStatus(MarketingRevenueAllocation.STATUS_PARTIALLY_REVERSED);
        when(allocations.selectById(88L)).thenReturn(updated);

        RevenueReversalResult result = service.reverse(reversalRequest(7001L));

        ArgumentCaptor<List<MarketingRevenueAllocationLine>> captor = ArgumentCaptor.forClass(List.class);
        verify(lines).batchInsert(captor.capture());
        List<MarketingRevenueAllocationLine> written = captor.getValue();
        assertEquals(2, written.size());
        MarketingRevenueAllocationLine authorReversal = written.get(0);
        assertEquals(MarketingRevenueAllocationLine.ROLE_AUTHOR, authorReversal.getLineRole());
        assertEquals(Long.valueOf(901L), authorReversal.getReversalOfLineId());
        // 50% 退款 × 原分录 46.70 = −23.35，负号即"反向"。
        assertEquals(Long.valueOf(-2335L), authorReversal.getAmountFen());
        assertEquals("refund:7001", authorReversal.getEventRef());
        assertEquals(Long.valueOf(5000L), authorReversal.getEventAmountFen());
        assertEquals("CNY", authorReversal.getEventCurrency());
        assertEquals(Integer.valueOf(MarketingRevenueAllocation.ORDER_REGISTRATION),
                authorReversal.getEventOrderType());
        assertEquals(Long.valueOf(REGISTRATION_ID), authorReversal.getEventOrderId());
        assertEquals(MarketingRevenueAllocation.STATUS_PARTIALLY_REVERSED, result.getStatus());
        assertFalse(result.isReplayed());
    }

    @Test
    void sameRefundEventReplayReturnsSameReceiptEvenAfterFullReversal() {
        // 全额冲销后重投同一退款：必须返回同回执，而不是先撞"累计上限"。
        MarketingRevenueAllocation fullyReversed = storedAllocation();
        fullyReversed.setReversedFen(9340L);
        fullyReversed.setStatus(MarketingRevenueAllocation.STATUS_REVERSED);
        // 全退 = 退满**实付** 100.00（不是净额 93.40）；冲销目标随后按净额折算为 93.40。
        when(refunds.selectRefundApplicationById(7001L)).thenReturn(refund(7001L, "100.00"));
        when(allocations.selectByOrderForUpdate(anyInt(), anyLong())).thenReturn(fullyReversed);
        List<MarketingRevenueAllocationLine> eventLines = new ArrayList<>();
        eventLines.add(reversalLine(950L, MarketingRevenueAllocationLine.ROLE_AUTHOR, CONTRACT_PAYEE, -4670L));
        eventLines.add(reversalLine(951L, MarketingRevenueAllocationLine.ROLE_PLATFORM, null, -4670L));
        when(lines.selectByEvent(88L, "refund:7001")).thenReturn(eventLines);

        RevenueReversalResult result = service.reverse(reversalRequest(7001L));

        assertTrue(result.isReplayed());
        assertEquals(2, result.getLines().size(), "回执只能含本事件分录");
        assertEquals(MarketingRevenueAllocation.STATUS_REVERSED, result.getStatus());
        verify(lines, never()).batchInsert(any());
        verify(allocations, never()).updateAfterReversal(anyLong(), any(), anyInt(), any());
        // 也不该再问通道事实：命中幂等即返回。
        verify(factSource, never()).verifiedRefund(any(), any());
    }

    @Test
    void receiptContainsOnlyThisEventNotAllHistory() {
        when(refunds.selectRefundApplicationById(7002L)).thenReturn(refund(7002L, "5.00"));
        when(allocations.selectByOrderForUpdate(anyInt(), anyLong())).thenReturn(storedAllocation());
        List<MarketingRevenueAllocationLine> eventLines = new ArrayList<>();
        MarketingRevenueAllocationLine eventLine = reversalLine(
                960L, MarketingRevenueAllocationLine.ROLE_AUTHOR, CONTRACT_PAYEE, -500L);
        eventLine.setEventRef("refund:7002");
        eventLine.setEventAmountFen(500L);
        eventLine.setEntryKey("reverse:refund:7002:" + MarketingRevenueAllocationLine.ROLE_AUTHOR);
        eventLines.add(eventLine);
        when(lines.selectByEvent(88L, "refund:7002")).thenReturn(eventLines);

        RevenueReversalResult result = service.reverse(reversalRequest(7002L));

        assertTrue(result.isReplayed());
        assertEquals(1, result.getLines().size(), "只回本事件的分录，不回历史退款");
        assertEquals(Long.valueOf(-500L), result.getLines().get(0).getAmountFen());
    }

    @Test
    void sameRefundIdWithChangedAmountIsRejected() {
        when(refunds.selectRefundApplicationById(7001L)).thenReturn(refund(7001L, "80.00"));
        when(allocations.selectByOrderForUpdate(anyInt(), anyLong())).thenReturn(storedAllocation());
        List<MarketingRevenueAllocationLine> eventLines = new ArrayList<>();
        eventLines.add(reversalLine(950L, MarketingRevenueAllocationLine.ROLE_AUTHOR, CONTRACT_PAYEE, -4670L));
        when(lines.selectByEvent(88L, "refund:7001")).thenReturn(eventLines);

        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> service.reverse(reversalRequest(7001L)));

        assertEquals("REVERSAL_EVENT_CONFLICT", error.getReasonCode());
    }

    @Test
    void sameRefundIdPointingAtAnotherOrderIsRejected() {
        RefundApplication otherOrder = refund(7003L, "10.00");
        otherOrder.setSourceId(999L);
        when(refunds.selectRefundApplicationById(7003L)).thenReturn(otherOrder);
        // 既有分配属于 555 单
        when(allocations.selectByOrderForUpdate(anyInt(), anyLong())).thenReturn(storedAllocation());
        List<MarketingRevenueAllocationLine> eventLines = new ArrayList<>();
        MarketingRevenueAllocationLine line =
                reversalLine(970L, MarketingRevenueAllocationLine.ROLE_AUTHOR, CONTRACT_PAYEE, -500L);
        line.setEventOrderId(REGISTRATION_ID);
        eventLines.add(line);
        when(lines.selectByEvent(88L, "refund:7003")).thenReturn(eventLines);

        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> service.reverse(reversalRequest(7003L)));

        // 事件里记录的原单(555)与退款单关联的原单(999)不一致，拒绝重放。
        assertEquals("REVERSAL_EVENT_CONFLICT", error.getReasonCode());
    }

    @Test
    void refundApprovalWithoutChannelFactIsRejected() {
        RefundApplication approvedOnly = refund(7004L, "10.00");
        approvedOnly.setPayoutStatus(RefundApplication.PAYOUT_DISPATCH_PENDING); // 只是批准/待派发
        when(refunds.selectRefundApplicationById(7004L)).thenReturn(approvedOnly);
        when(allocations.selectByOrderForUpdate(anyInt(), anyLong())).thenReturn(storedAllocation());
        when(registrations.selectCmsRegistrationById(REGISTRATION_ID)).thenReturn(registration());
        when(lines.selectByAllocationId(88L)).thenReturn(storedLines());
        when(lines.selectByEvent(88L, "refund:7004")).thenReturn(new ArrayList<>());
        when(factSource.verifiedRefund(any(), any())).thenReturn(Optional.empty());

        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> service.reverse(reversalRequest(7004L)));

        assertEquals("REFUND_FACT_MISSING", error.getReasonCode());
        verify(lines, never()).batchInsert(any());
    }

    @Test
    void requestRegistrationMismatchIsRejected() {
        when(refunds.selectRefundApplicationById(7005L)).thenReturn(refund(7005L, "10.00"));
        RevenueReversalRequest request = reversalRequest(7005L);
        request.setRegistrationId(999L);

        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> service.reverse(request));

        assertEquals("REFUND_ORDER_CONFLICT", error.getReasonCode());
        verify(allocations, never()).selectByOrderForUpdate(anyInt(), anyLong());
    }

    @Test
    void refundBeyondPaidIsRejected() {
        when(refunds.selectRefundApplicationById(7006L)).thenReturn(refund(7006L, "150.00"));
        when(allocations.selectByOrderForUpdate(anyInt(), anyLong())).thenReturn(storedAllocation());
        when(registrations.selectCmsRegistrationById(REGISTRATION_ID)).thenReturn(registration());
        when(factSource.verifiedRefund(any(), any())).thenReturn(Optional.of(
                new RevenueFactSource.RefundFact(15000L, 10000L, "CNY", "c".repeat(64), "RF7006")));
        when(lines.selectByAllocationId(88L)).thenReturn(storedLines());
        when(lines.selectByEvent(88L, "refund:7006")).thenReturn(new ArrayList<>());

        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> service.reverse(reversalRequest(7006L)));

        assertEquals("REFUND_EXCEEDS_PAID", error.getReasonCode());
    }

    @Test
    void threeOneCentRefundsFullyReverseThreeOneCentLines() {
        MarketingRevenueAllocation tiny = storedAllocation();
        tiny.setPaidFen(3L);
        tiny.setNetFen(3L);
        List<MarketingRevenueAllocationLine> threeLines = new ArrayList<>();
        threeLines.add(accrualLine(1001L, MarketingRevenueAllocationLine.ROLE_AUTHOR, CONTRACT_PAYEE, 1L));
        threeLines.add(accrualLine(1002L, MarketingRevenueAllocationLine.ROLE_REFERRER, OTHER_SHARER, 1L));
        threeLines.add(accrualLine(1003L, MarketingRevenueAllocationLine.ROLE_PLATFORM, null, 1L));

        List<MarketingRevenueAllocationLine> accumulated = new ArrayList<>(threeLines);
        for (long refundId = 8001L; refundId <= 8003L; refundId++) {
            RefundApplication oneCent = refund(refundId, "0.01");
            when(refunds.selectRefundApplicationById(refundId)).thenReturn(oneCent);
            when(allocations.selectByOrderForUpdate(anyInt(), anyLong())).thenReturn(tiny);
            when(registrations.selectCmsRegistrationById(REGISTRATION_ID)).thenReturn(registration());
            when(factSource.verifiedRefund(any(), any())).thenReturn(Optional.of(
                    new RevenueFactSource.RefundFact(1L, 3L, "CNY", "d".repeat(64), "RF" + refundId)));
            when(lines.selectByAllocationId(88L)).thenReturn(new ArrayList<>(accumulated));
            final long eventId = refundId;
            when(lines.selectByEvent(88L, "refund:" + eventId)).thenReturn(new ArrayList<>());
            doAnswer(invocation -> {
                List<MarketingRevenueAllocationLine> batch = invocation.getArgument(0);
                long id = 2000L + eventId * 10;
                for (MarketingRevenueAllocationLine line : batch) {
                    line.setId(id++);
                    accumulated.add(line);
                }
                return batch.size();
            }).when(lines).batchInsert(any());
            MarketingRevenueAllocation progressed = storedAllocation();
            progressed.setPaidFen(3L);
            progressed.setNetFen(3L);
            long reversedSoFar = 1L * (refundId - 8000L);
            progressed.setReversedFen(reversedSoFar);
            progressed.setStatus(reversedSoFar >= 3L
                    ? MarketingRevenueAllocation.STATUS_REVERSED
                    : MarketingRevenueAllocation.STATUS_PARTIALLY_REVERSED);
            when(allocations.updateAfterReversal(anyLong(), any(), anyInt(), any())).thenReturn(1);
            when(allocations.selectById(88L)).thenReturn(progressed);

            RevenueReversalResult result = service.reverse(reversalRequest(refundId));

            assertFalse(result.isReplayed());
            long reversedThisEvent = 0;
            for (RevenueReversalResult.Line line : result.getLines()) {
                reversedThisEvent += line.getAmountFen();
            }
            assertEquals(-1L, reversedThisEvent, "每笔 1 分退款恰好冲 1 分");
        }
        long totalReversed = 0;
        for (MarketingRevenueAllocationLine line : accumulated) {
            if (line.getEntryType() == MarketingRevenueAllocationLine.ENTRY_REVERSE) {
                totalReversed += line.getAmountFen();
            }
        }
        assertEquals(-3L, totalReversed, "三笔退完必须冲满 3 分，无残差");
    }

    private MarketingRevenueAllocationLine reversalLine(long id, String role, Long recipient, long fen) {
        MarketingRevenueAllocationLine line = new MarketingRevenueAllocationLine();
        line.setId(id);
        line.setAllocationId(88L);
        line.setEntryType(MarketingRevenueAllocationLine.ENTRY_REVERSE);
        line.setLineRole(role);
        line.setRecipientMemberId(recipient);
        line.setAmountFen(fen);
        line.setEventRef("refund:7001");
        line.setEventAmountFen(10000L);
        line.setEventCurrency("CNY");
        line.setEventOrderType(MarketingRevenueAllocation.ORDER_REGISTRATION);
        line.setEventOrderId(REGISTRATION_ID);
        line.setCurrency("CNY");
        line.setEntryKey("reverse:refund:7001:" + role);
        return line;
    }
}
