package com.chengyinhub.business.util;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.RegistrationEntitlement;
import com.chengyinhub.business.domain.vo.RegistrationSettlementSummaryVO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U07-04/U07-05 只读投影：受理 ≠ 到账、部分退不误报整单、未核销与已失效分开计、
 * 未知绝不折成 0 元。金额与状态全部来自既有事实，不做推断。
 */
class RegistrationSettlementProjectionTest {

    private static final Long MEMBER = 99L;
    private static final Long REG = 500L;
    private static final Long SIBLING = 501L;

    private static CmsRegistration row(Long id, int paymentStatus, String parentOrderNo, long seats) {
        CmsRegistration row = new CmsRegistration();
        row.setId(id);
        row.setMemberId(MEMBER);
        row.setOwnerType(2L);
        row.setOwnerId(200L);
        row.setParentOrderNo(parentOrderNo);
        row.setPaymentStatus(paymentStatus);
        row.setRegistrationStatus(paymentStatus == 2 ? 2 : 3);
        row.setOrderNum(seats);
        row.setPayableAmount(new BigDecimal("100.00"));
        return row;
    }

    private static RefundApplication refund(Long id, Long sourceId, int status, Integer payoutStatus,
                                            String amount, Date updatedAt) {
        RefundApplication refund = new RefundApplication();
        refund.setId(id);
        refund.setSourceType("registration");
        refund.setSourceId(sourceId);
        refund.setMemberId(MEMBER);
        refund.setStatus(status);
        refund.setPayoutStatus(payoutStatus);
        refund.setRefundAmount(amount == null ? null : new BigDecimal(amount));
        refund.setUpdateTime(updatedAt);
        return refund;
    }

    private static RegistrationEntitlement entitlement(Long registrationId, int status) {
        RegistrationEntitlement entitlement = new RegistrationEntitlement();
        entitlement.setRegistrationId(registrationId);
        entitlement.setChapterId(1L);
        entitlement.setStatus(status);
        return entitlement;
    }

    @Test
    void acceptedRefundIsNotReportedAsPaidOut() {
        RefundApplication accepted = refund(1L, REG, 1, RefundApplication.PAYOUT_DISPATCH_PENDING,
                "100.00", new Date(1000L));
        RegistrationSettlementSummaryVO summary = RegistrationSettlementProjection.build(
                row(REG, 2, null, 1L), MEMBER, null, List.of(accepted), null, null);

        assertEquals(0, new BigDecimal("100.00").compareTo(summary.getRefundProcessingAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(summary.getRefundCompletedAmount()));
        assertEquals(0, new BigDecimal("100.00").compareTo(summary.getRefundRequestedAmount()));
        assertEquals("PAYOUT_PENDING", summary.getRefundStage());
        assertEquals(Boolean.TRUE, summary.getAmountsComplete());
    }

    @Test
    void pendingReviewAndCompletedRefundsAreSeparateBuckets() {
        List<RefundApplication> refunds = Arrays.asList(
                refund(1L, REG, 0, null, "30.00", new Date(1000L)),
                refund(2L, REG, 1, RefundApplication.PAYOUT_SUCCESS, "70.00", new Date(2000L)));

        RegistrationSettlementSummaryVO summary = RegistrationSettlementProjection.build(
                row(REG, 4, null, 1L), MEMBER, null, refunds, null, null);

        assertEquals(0, new BigDecimal("30.00").compareTo(summary.getRefundPendingAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(summary.getRefundProcessingAmount()));
        assertEquals(0, new BigDecimal("70.00").compareTo(summary.getRefundCompletedAmount()));
        assertEquals(0, new BigDecimal("100.00").compareTo(summary.getRefundRequestedAmount()));
        assertEquals("COMPLETED", summary.getRefundStage());
    }

    /** 部分退一席：同父单仍在使用的票不被算成已退，也不是整队撤销。 */
    @Test
    void partialRefundDoesNotWithdrawTheWholeOrder() {
        CmsRegistration paid = row(REG, 2, "PARENT", 1L);
        CmsRegistration refunded = row(SIBLING, 4, "PARENT", 1L);
        RefundApplication one = refund(1L, SIBLING, 1, RefundApplication.PAYOUT_SUCCESS,
                "100.00", new Date(1000L));

        RegistrationSettlementSummaryVO summary = RegistrationSettlementProjection.build(
                paid, MEMBER, List.of(paid, refunded), List.of(one), null, null);

        assertEquals(1, summary.getActiveTicketCount().intValue());
        assertEquals(1, summary.getRefundedTicketCount().intValue());
        assertEquals(2, summary.getTicketCount().intValue());
        assertEquals(0, new BigDecimal("200.00").compareTo(summary.getOrderPaidAmount()));
        assertEquals(0, new BigDecimal("100.00").compareTo(summary.getRefundCompletedAmount()));
        assertEquals(2, paid.getPaymentStatus().intValue(), "读取不得改写支付状态");
    }

    /** 父单聚合只算发起账号的行：他人报名即使出现在结果里也不进摘要。 */
    @Test
    void foreignAccountsRowsNeverEnterTheSummary() {
        CmsRegistration mine = row(REG, 2, "PARENT", 1L);
        CmsRegistration foreign = row(SIBLING, 2, "PARENT", 3L);
        foreign.setMemberId(12345L);

        RegistrationSettlementSummaryVO summary = RegistrationSettlementProjection.build(
                mine, MEMBER, List.of(mine, foreign), Collections.emptyList(), null, null);

        assertEquals(1, summary.getTicketCount().intValue());
        assertEquals(0, new BigDecimal("100.00").compareTo(summary.getOrderPaidAmount()));
    }

    /** 权益：未核销占容量、已核销是事实、已失效释放；三种分开计，缺行不折成 0。 */
    @Test
    void entitlementStatesStayDistinctAndAbsentMeansNotApplicable() {
        List<RegistrationEntitlement> rows = Arrays.asList(
                entitlement(REG, 0), entitlement(REG, 1), entitlement(REG, 2), entitlement(REG, 0));

        RegistrationSettlementSummaryVO withRows = RegistrationSettlementProjection.build(
                row(REG, 2, null, 1L), MEMBER, null, Collections.emptyList(), rows, null);
        assertEquals(4, withRows.getEntitlementTotal().intValue());
        assertEquals(2, withRows.getEntitlementPending().intValue());
        assertEquals(1, withRows.getEntitlementRedeemed().intValue());
        assertEquals(1, withRows.getEntitlementInvalid().intValue());

        RegistrationSettlementSummaryVO withoutConcept = RegistrationSettlementProjection.build(
                row(REG, 2, null, 1L), MEMBER, null, Collections.emptyList(), null, null);
        assertNull(withoutConcept.getEntitlementTotal(), "非 ③ 票没有权益概念，不是 0");
    }

    /** 查询没有结果集 = 未知；不得显示 0 元、不得显示「无退款」。 */
    @Test
    void unknownRefundQueryIsNeverReportedAsZero() {
        RegistrationSettlementSummaryVO summary = RegistrationSettlementProjection.build(
                row(REG, 2, null, 1L), MEMBER, null, null, null, null);

        assertNull(summary.getRefundRequestedAmount());
        assertNull(summary.getRefundPendingAmount());
        assertNull(summary.getRefundProcessingAmount());
        assertNull(summary.getRefundCompletedAmount());
        assertFalse(summary.getAmountsComplete());
        assertEquals("UNKNOWN", summary.getRefundStage());
    }

    /** 有退款单但金额缺失：合计置空并标记不完整，不能按已知部分假装全额。 */
    @Test
    void missingRefundAmountMarksTheSummaryIncomplete() {
        RefundApplication missing = refund(1L, REG, 1, RefundApplication.PAYOUT_SUCCESS, null, new Date(1000L));

        RegistrationSettlementSummaryVO summary = RegistrationSettlementProjection.build(
                row(REG, 4, null, 1L), MEMBER, null, List.of(missing), null, null);

        assertNull(summary.getRefundCompletedAmount());
        assertNull(summary.getRefundRequestedAmount());
        assertEquals(Boolean.FALSE, summary.getAmountsComplete());
    }

    @Test
    void manualRefundCaseOutranksTheOrdinaryRefundStage() {
        RefundApplication approved = refund(1L, REG, 1, RefundApplication.PAYOUT_SUCCESS,
                "100.00", new Date(1000L));

        RegistrationSettlementSummaryVO summary = RegistrationSettlementProjection.build(
                row(REG, 4, null, 1L), MEMBER, null, List.of(approved), null, "PENDING_ASSESSMENT");

        assertEquals("MANUAL_REVIEW", summary.getRefundStage());
        assertEquals("PENDING_ASSESSMENT", summary.getManualRefundStatus());
        assertTrue(new BigDecimal("100.00").compareTo(summary.getRefundCompletedAmount()) == 0,
                "人工工单不抹掉已发生的渠道退款事实");
    }

    @Test
    void refundsOfOtherRegistrationsDoNotLeakIntoThisOrder() {
        RefundApplication other = refund(1L, 999L, 1, RefundApplication.PAYOUT_SUCCESS,
                "500.00", new Date(1000L));

        RegistrationSettlementSummaryVO summary = RegistrationSettlementProjection.build(
                row(REG, 2, null, 1L), MEMBER, null, List.of(other), null, null);

        assertEquals(0, BigDecimal.ZERO.compareTo(summary.getRefundRequestedAmount()));
        assertEquals("NONE", summary.getRefundStage());
    }

    @Test
    void rejectedRefundNeitherReducesNorClaimsMoney() {
        RefundApplication rejected = refund(1L, REG, 2, null, "100.00", new Date(1000L));

        RegistrationSettlementSummaryVO summary = RegistrationSettlementProjection.build(
                row(REG, 2, null, 1L), MEMBER, null, List.of(rejected), null, null);

        assertEquals(0, BigDecimal.ZERO.compareTo(summary.getRefundRequestedAmount()));
        assertEquals("REJECTED", summary.getRefundStage());
    }
}
