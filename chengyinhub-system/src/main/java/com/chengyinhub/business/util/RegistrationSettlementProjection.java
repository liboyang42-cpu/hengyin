package com.chengyinhub.business.util;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.vo.RegistrationSettlementSummaryVO;
import com.chengyinhub.business.domain.RegistrationEntitlement;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单退款/权益只读投影（U07-04/U07-05）。
 *
 * <p>只有一个数据来源：既有 cms_registration 快照 + refund_application 事实 + registration_entitlement。
 * 不做支付推断、不写状态、不产生新账本。任何无法证明的金额保持 null，并用
 * {@code amountsComplete=false} 显式告知前端，不用 0 元或“已退款”冒充。
 */
public final class RegistrationSettlementProjection {

    private static final int PAYMENT_PAID = 2;
    private static final int PAYMENT_REFUNDED = 4;
    private static final int REFUND_PENDING_REVIEW = 0;
    private static final int REFUND_APPROVED = 1;
    private static final int PAYOUT_SUCCESS = 1;
    private static final int PAYOUT_MANUAL_VERIFIED = 4;

    private RegistrationSettlementProjection() {
    }

    /**
     * @param current 当前订单行（详情页主体）
     * @param payerMemberId 发起读取的登录账号；父单行必须同属该账号，跨账号行一律丢弃
     * @param orderRows 同一父订单的报名行；为空时只按 current 计算
     * @param refunds 已按账号范围过滤的退款历史；可为空
     * @param entitlements 本单权益行；null = 该购买类型没有权益概念（保持 null 而不是 0）
     * @param manualRefundCaseStatus 人工退款工单状态原文；null = 无工单
     */
    public static RegistrationSettlementSummaryVO build(CmsRegistration current,
                                                        Long payerMemberId,
                                                        List<CmsRegistration> orderRows,
                                                        List<RefundApplication> refunds,
                                                        List<RegistrationEntitlement> entitlements,
                                                        String manualRefundCaseStatus) {
        RegistrationSettlementSummaryVO summary = new RegistrationSettlementSummaryVO();
        if (current == null) {
            return summary;
        }
        List<CmsRegistration> rows = distinctRows(current, orderRows, payerMemberId);
        summary.setPaidAmount(current.getPayableAmount());

        BigDecimal orderPaid = null;
        int ticketCount = 0;
        int paidTickets = 0;
        int refundedTickets = 0;
        for (CmsRegistration row : rows) {
            int seats = row.getOrderNum() == null || row.getOrderNum() <= 0 ? 1 : row.getOrderNum().intValue();
            ticketCount += seats;
            Integer paymentStatus = row.getPaymentStatus();
            if (Integer.valueOf(PAYMENT_PAID).equals(paymentStatus)) {
                paidTickets += seats;
            } else if (Integer.valueOf(PAYMENT_REFUNDED).equals(paymentStatus)) {
                refundedTickets += seats;
            }
            if (paymentStatus != null
                    && (paymentStatus == PAYMENT_PAID || paymentStatus == PAYMENT_REFUNDED)
                    && row.getPayableAmount() != null) {
                orderPaid = orderPaid == null ? row.getPayableAmount() : orderPaid.add(row.getPayableAmount());
            }
        }
        summary.setOrderPaidAmount(orderPaid);
        summary.setTicketCount(ticketCount);
        summary.setPaidTicketCount(paidTickets);
        summary.setRefundedTicketCount(refundedTickets);
        summary.setActiveTicketCount(paidTickets);

        // 退款单先按本订单行收敛：查询侧按账号过滤，这里再按该账号的父单行过滤，
        // 保证后续金额与阶段都不会读到本单以外的退款。
        List<RefundApplication> orderRefunds = refundsOfOrder(refunds, rows);
        applyRefunds(summary, orderRefunds);
        applyEntitlements(summary, entitlements);
        summary.setManualRefundStatus(manualRefundCaseStatus);
        summary.setRefundStage(refundStage(orderRefunds, manualRefundCaseStatus));
        return summary;
    }

    /**
     * 父单聚合的行集合。查询侧已按 member_id + parent_order_no 过滤，这里再按发起账号复核，
     * 保证即使将来查询条件被放宽也不会把他人报名或退款带进摘要。
     */
    private static List<CmsRegistration> distinctRows(CmsRegistration current, List<CmsRegistration> orderRows,
                                                      Long payerMemberId) {
        Map<Long, CmsRegistration> byId = new LinkedHashMap<>();
        if (orderRows != null) {
            for (CmsRegistration row : orderRows) {
                if (row != null && row.getId() != null && sameMember(row, payerMemberId)) {
                    byId.putIfAbsent(row.getId(), row);
                }
            }
        }
        if (current.getId() != null && sameMember(current, payerMemberId)) {
            byId.putIfAbsent(current.getId(), current);
        }
        if (byId.isEmpty()) {
            List<CmsRegistration> single = new ArrayList<>();
            single.add(current);
            return single;
        }
        return new ArrayList<>(byId.values());
    }

    private static boolean sameMember(CmsRegistration row, Long payerMemberId) {
        return payerMemberId == null || payerMemberId.equals(row.getMemberId());
    }

    private static void applyRefunds(RegistrationSettlementSummaryVO summary,
                                     List<RefundApplication> refunds) {
        if (refunds == null) {
            // 查询本身没有结果集：未知。绝不折成 0 元「无退款」。
            summary.setRefundPendingAmount(null);
            summary.setRefundProcessingAmount(null);
            summary.setRefundCompletedAmount(null);
            summary.setRefundRequestedAmount(null);
            summary.setAmountsComplete(Boolean.FALSE);
            return;
        }
        BigDecimal pending = BigDecimal.ZERO;
        BigDecimal processing = BigDecimal.ZERO;
        BigDecimal completed = BigDecimal.ZERO;
        boolean pendingComplete = true;
        boolean processingComplete = true;
        boolean completedComplete = true;
        boolean anyAmountMissing = false;
        for (RefundApplication refund : refunds) {
            Integer status = refund == null ? null : refund.getStatus();
            if (status == null || status != REFUND_PENDING_REVIEW && status != REFUND_APPROVED) {
                continue; // 驳回/未知不进入金额，也不冒充已退
            }
            BigDecimal amount = refund.getRefundAmount();
            if (amount == null) {
                anyAmountMissing = true;
            }
            if (status == REFUND_PENDING_REVIEW) {
                if (amount == null) {
                    pendingComplete = false;
                } else {
                    pending = pending.add(amount);
                }
            } else if (isPayoutCompleted(refund.getPayoutStatus())) {
                if (amount == null) {
                    completedComplete = false;
                } else {
                    completed = completed.add(amount);
                }
            } else {
                if (amount == null) {
                    processingComplete = false;
                } else {
                    processing = processing.add(amount);
                }
            }
        }
        summary.setRefundPendingAmount(pendingComplete ? pending : null);
        summary.setRefundProcessingAmount(processingComplete ? processing : null);
        summary.setRefundCompletedAmount(completedComplete ? completed : null);
        summary.setRefundRequestedAmount(anyAmountMissing ? null
                : pending.add(processing).add(completed));
        summary.setAmountsComplete(!anyAmountMissing);
    }

    private static boolean isPayoutCompleted(Integer payoutStatus) {
        return Integer.valueOf(PAYOUT_SUCCESS).equals(payoutStatus)
                || Integer.valueOf(PAYOUT_MANUAL_VERIFIED).equals(payoutStatus);
    }

    /**
     * 只保留本订单行上的退款单。输入为 null（查询无结果集）时原样返回 null，
     * 让下游保持「未知」而不是「零退款」。
     */
    private static List<RefundApplication> refundsOfOrder(List<RefundApplication> refunds,
                                                          List<CmsRegistration> rows) {
        if (refunds == null) {
            return null;
        }
        Map<Long, Boolean> orderIds = new LinkedHashMap<>();
        for (CmsRegistration row : rows) {
            if (row.getId() != null) {
                orderIds.put(row.getId(), Boolean.TRUE);
            }
        }
        List<RefundApplication> scoped = new ArrayList<>();
        for (RefundApplication refund : refunds) {
            if (refund != null && refund.getSourceId() != null && orderIds.containsKey(refund.getSourceId())) {
                scoped.add(refund);
            }
        }
        return scoped;
    }

    private static void applyEntitlements(RegistrationSettlementSummaryVO summary,
                                          List<RegistrationEntitlement> entitlements) {
        if (entitlements == null) {
            return; // 非 ③ 票没有权益概念：null 表示不适用，不是 0/0
        }
        int pending = 0;
        int redeemed = 0;
        int invalid = 0;
        for (RegistrationEntitlement entitlement : entitlements) {
            if (entitlement == null) {
                continue;
            }
            Integer status = entitlement.getStatus();
            if (Integer.valueOf(0).equals(status)) {
                pending++;
            } else if (Integer.valueOf(1).equals(status)) {
                redeemed++;
            } else if (Integer.valueOf(2).equals(status)) {
                invalid++;
            }
        }
        summary.setEntitlementTotal(entitlements.size());
        summary.setEntitlementPending(pending);
        summary.setEntitlementRedeemed(redeemed);
        summary.setEntitlementInvalid(invalid);
    }

    private static String refundStage(List<RefundApplication> refunds, String manualRefundCaseStatus) {
        if (manualRefundCaseStatus != null && !manualRefundCaseStatus.trim().isEmpty()) {
            return "MANUAL_REVIEW";
        }
        RefundApplication latest = latest(refunds);
        if (latest == null) {
            // null 列表 = 查询没有结果集（未知）；空列表 = 确实没有退款单（NONE）。
            return refunds == null ? "UNKNOWN" : "NONE";
        }
        Integer status = latest.getStatus();
        if (Integer.valueOf(REFUND_PENDING_REVIEW).equals(status)) {
            return "UNDER_REVIEW";
        }
        if (status != null && status == 2) {
            return "REJECTED";
        }
        if (Integer.valueOf(REFUND_APPROVED).equals(status)) {
            Integer payout = latest.getPayoutStatus();
            if (isPayoutCompleted(payout)) {
                return "COMPLETED";
            }
            if (Integer.valueOf(RefundApplication.PAYOUT_PENDING_MANUAL).equals(payout)
                    || Integer.valueOf(RefundApplication.PAYOUT_MANUAL_HANDLED).equals(payout)) {
                return "MANUAL_PAYOUT";
            }
            return "PAYOUT_PENDING";
        }
        return "NONE";
    }

    private static RefundApplication latest(List<RefundApplication> refunds) {
        if (refunds == null || refunds.isEmpty()) {
            return null;
        }
        RefundApplication best = null;
        for (RefundApplication row : refunds) {
            if (row != null && (best == null || newer(row, best))) {
                best = row;
            }
        }
        return best;
    }

    private static boolean newer(RefundApplication candidate, RefundApplication current) {
        long candidateTime = Math.max(timeOf(candidate.getUpdateTime()), timeOf(candidate.getCreateTime()));
        long currentTime = Math.max(timeOf(current.getUpdateTime()), timeOf(current.getCreateTime()));
        if (candidateTime != currentTime) {
            return candidateTime > currentTime;
        }
        long candidateId = candidate.getId() == null ? Long.MIN_VALUE : candidate.getId();
        long currentId = current.getId() == null ? Long.MIN_VALUE : current.getId();
        return candidateId > currentId;
    }

    private static long timeOf(Date value) {
        return value == null ? Long.MIN_VALUE : value.getTime();
    }
}
