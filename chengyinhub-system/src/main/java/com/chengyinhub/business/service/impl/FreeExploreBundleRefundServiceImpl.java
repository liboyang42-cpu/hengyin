package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.exception.FreeExploreBundleException;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.IFreeExploreBundleRefundService;
import com.chengyinhub.business.service.support.FreeExploreBundlePolicy;
import com.chengyinhub.business.service.support.FreeExploreRefundContext;
import com.chengyinhub.business.util.OrderRefundCash;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Ordinary unused full refunds only. Financial intent/status remain owned by RefundService. */
@Service
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
public class FreeExploreBundleRefundServiceImpl implements IFreeExploreBundleRefundService {
    private final CmsRegistrationMapper registrations;
    private final RefundApplicationMapper refunds;
    private final ExploreBundleMapper bundles;
    private final ExploreBundleRefundMapper holds;

    public FreeExploreBundleRefundServiceImpl(CmsRegistrationMapper registrations, RefundApplicationMapper refunds,
                                              ExploreBundleMapper bundles, ExploreBundleRefundMapper holds) {
        this.registrations = registrations; this.refunds = refunds; this.bundles = bundles; this.holds = holds;
    }

    @Override
    public FreeExploreRefundContext prepareFullRefund(Long sourceRegistrationId, BigDecimal refundCash) {
        transaction();
        BigDecimal cash = cents(refundCash);
        Scope scope = lockScope(sourceRegistrationId);
        CmsRegistration payer = scope.rows.stream().filter(r -> sourceRegistrationId.equals(r.getId())).findFirst().orElseThrow();
        if (!scope.orders.isEmpty() && (payer.getWechatPaymentAmount() == null || cents(payer.getWechatPaymentAmount()).compareTo(cash) < 0
                || cash.signum() == 0 && payer.getWechatPaymentAmount().signum() != 0)) {
            throw reject("BUNDLE_REFUND_CASH_SCOPE", "现金退款范围无法确认，零现金不得代替有现金的退款");
        }
        if (!scope.orders.isEmpty()) {
            // A zero-cash child must not cancel a cash-bearing parent group as a zero-cash order.
            for (CmsRegistration row : scope.rows) {
                if (!sourceRegistrationId.equals(row.getId()) && (row.getWechatPaymentAmount() == null
                        || row.getWechatPaymentAmount().signum() != 0)) {
                    throw reject("BUNDLE_REFUND_CASH_SCOPE", "须以唯一原付款报名裁定完整付款组，其他票现金快照必须明确为零");
                }
            }
            BigDecimal committedCash = BigDecimal.ZERO;
            for (RefundApplication prior : scope.history.values()) {
                if (Integer.valueOf(1).equals(prior.getStatus())) committedCash = committedCash.add(cents(prior.getRefundAmount()));
            }
            if (cash.compareTo(cents(payer.getWechatPaymentAmount()).subtract(committedCash)) != 0) {
                throw reject("BUNDLE_REFUND_PARTIAL_NOT_APPROVED", "本接口仅裁定原付款组剩余现金全退，不开放部分退款");
            }
        }
        // Assess every F3 ticket in the complete payment group before inventory/points/registration side effects.
        for (CmsRegistration row : scope.rows) {
            ExploreBundleOrder order = scope.orders.get(row.getId());
            if (order == null) continue;
            List<ExploreBundleUnit> units = scope.units.get(row.getId());
            if (!bookingsConsistent(units)) throw reject("BUNDLE_REFUND_RECONCILIATION", "预约与冻结权益归属不一致");
            ExploreBundleRefundHold prior = holds.selectByRegistrationForUpdate(row.getId());
            if (prior != null) {
                matchScope(prior, sourceRegistrationId, row.getMemberId(), cash);
                if (cash.signum() == 0 && "ZERO_CASH_COMPLETED".equals(prior.getState())) continue;
                if (!Set.of("PREPARED", "BOUND").contains(prior.getState())) {
                    throw reject("BUNDLE_REFUND_IN_PROGRESS", "退款已进入处理，须按原意图回读");
                }
                if (units.isEmpty() || units.stream().anyMatch(u -> !"REFUND_PENDING".equals(u.getStatus()))) {
                    throw reject("BUNDLE_REFUND_RECONCILIATION", "冻结权益与退款记录不一致");
                }
            } else {
                BigDecimal total = BigDecimal.ZERO;
                if (units.isEmpty()) throw reject("BUNDLE_REFUND_RECONCILIATION", "套票单位缺失");
                for (ExploreBundleUnit unit : units) {
                    if (!FreeExploreBundlePolicy.unfulfilled(unit.getStatus()) && !unfulfilledMerchantExit(unit)) {
                        throw reject("BUNDLE_REFUND_CONSUMED", "已履约、罚扣或退款处置中的权益不能按完全未用全退");
                    }
                    total = total.add(cents(unit.getUnitValue()));
                }
                if (total.compareTo(cents(order.getPaidAmount())) != 0) {
                    throw reject("BUNDLE_REFUND_RECONCILIATION", "冻结实付与权益价值不守恒");
                }
            }
        }
        List<Long> ids = new ArrayList<>();
        for (CmsRegistration row : scope.rows) {
            if (!scope.orders.containsKey(row.getId())) continue;
            ids.add(row.getId());
            ExploreBundleRefundHold hold = holds.selectByRegistrationForUpdate(row.getId());
            if (hold != null) continue;
            for (ExploreBundleUnit unit : scope.units.get(row.getId())) {
                if (!"REFUND_PENDING".equals(unit.getStatus())) move(unit, "REFUND_PENDING", "FULL_REFUND_PREPARED");
            }
            hold = new ExploreBundleRefundHold();
            hold.setRegistrationId(row.getId()); hold.setHolderMemberId(row.getMemberId());
            hold.setSourceRegistrationId(sourceRegistrationId); hold.setCashAmount(cash);
            hold.setState("PREPARED"); hold.setVersion(0);
            if (holds.insert(hold) != 1) throw reject("BUNDLE_CONCURRENT", "退款冻结写入失败");
        }
        Set<Long> replaceable = new HashSet<>();
        for (RefundApplication refund : scope.history.values()) {
            if (sourceRegistrationId.equals(refund.getSourceId()) && Integer.valueOf(0).equals(refund.getStatus())
                    && Integer.valueOf(RefundApplication.PAYOUT_DISPATCH_PENDING).equals(refund.getPayoutStatus())) {
                replaceable.add(refund.getId());
            }
        }
        FreeExploreRefundContext context = new FreeExploreRefundContext(sourceRegistrationId, cash, ids, replaceable);
        TransactionSynchronizationManager.bindResource(context, Boolean.TRUE);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {
                for (Long id : ids) {
                    ExploreBundleRefundHold hold = holds.selectByRegistration(id);
                    if (hold == null || "PREPARED".equals(hold.getState())
                            || cash.signum() > 0 && hold.getRefundId() == null
                            || cash.signum() == 0 && !"ZERO_CASH_COMPLETED".equals(hold.getState())) {
                        throw reject("BUNDLE_REFUND_UNBOUND", "冻结必须在同事务绑定原退款意图或完成零现金取消");
                    }
                }
            }
            @Override public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(context);
            }
        });
        return context;
    }

    @Override
    public List<ExploreBundleRefundHold> bindFullRefund(FreeExploreRefundContext context, Long refundId, Long replacesRefundId) {
        context(context);
        if (context.getCashAmount().signum() <= 0) throw reject("BUNDLE_REFUND_ZERO_CASH", "零现金不绑定资金退款意图");
        Scope scope = lockScope(context.getSourceRegistrationId());
        RefundApplication refund = intent(scope, refundId);
        if (!Integer.valueOf(0).equals(refund.getStatus())
                || !Integer.valueOf(RefundApplication.PAYOUT_DISPATCH_PENDING).equals(refund.getPayoutStatus())
                || cents(refund.getRefundAmount()).compareTo(context.getCashAmount()) != 0) {
            throw reject("BUNDLE_REFUND_INTENT_MISMATCH", "须绑定尚未派发的原金额待审退款意图");
        }
        if (replacesRefundId != null) {
            RefundApplication old = intent(scope, replacesRefundId);
            if (!context.getReplaceableRefundIds().contains(replacesRefundId)
                    || !Integer.valueOf(2).equals(old.getStatus())
                    || !Integer.valueOf(RefundApplication.PAYOUT_DISPATCH_PENDING).equals(old.getPayoutStatus())
                    || replacesRefundId.equals(refundId)) {
                throw reject("BUNDLE_REFUND_REPLACEMENT_REQUIRED", "旧意图须在本次准备后同事务取代，不能将历史驳回当解冻依据");
            }
        }
        List<ExploreBundleRefundHold> result = new ArrayList<>();
        for (Long id : context.getRegistrations()) {
            ExploreBundleRefundHold hold = holds.selectByRegistrationForUpdate(id);
            matchScope(hold, context.getSourceRegistrationId(), scope.orders.get(id).getHolderMemberId(), context.getCashAmount());
            if (!Set.of("PREPARED", "BOUND").contains(hold.getState())) throw reject("BUNDLE_REFUND_IN_PROGRESS", "冻结已进入处理");
            if (hold.getRefundId() != null && !refundId.equals(hold.getRefundId())
                    && !hold.getRefundId().equals(replacesRefundId)) {
                throw reject("BUNDLE_REFUND_REPLACEMENT_REQUIRED", "冻结属于另一原意图，须显式转接");
            }
            if (!refundId.equals(hold.getRefundId())) {
                hold.setRefundId(refundId); hold.setRefundNo(refund.getRefundNo()); hold.setState("BOUND"); update(hold);
            }
            result.add(hold);
        }
        return result;
    }

    @Override
    public List<ExploreBundleRefundHold> completeZeroCashRefund(FreeExploreRefundContext context) {
        context(context);
        if (context.getCashAmount().signum() != 0) throw reject("BUNDLE_REFUND_CASH_SCOPE", "有现金须等待原退款回执");
        Scope scope = lockScope(context.getSourceRegistrationId());
        List<ExploreBundleRefundHold> result = new ArrayList<>();
        for (Long id : context.getRegistrations()) {
            CmsRegistration row = scope.rows.stream().filter(r -> id.equals(r.getId())).findFirst().orElseThrow();
            if (!Integer.valueOf(3).equals(row.getRegistrationStatus())) throw reject("BUNDLE_REFUND_NOT_CANCELLED", "须先完成原报名条件取消");
            ExploreBundleRefundHold hold = holds.selectByRegistrationForUpdate(id);
            if (hold.getRefundId() != null || hold.getCashAmount().signum() != 0) throw reject("BUNDLE_REFUND_CASH_SCOPE", "非零现金冻结");
            if (!"ZERO_CASH_COMPLETED".equals(hold.getState())) {
                if (!"PREPARED".equals(hold.getState())) throw reject("BUNDLE_REFUND_IN_PROGRESS", "冻结状态不允许零现金完成");
                finish(scope.units.get(id)); hold.setState("ZERO_CASH_COMPLETED"); update(hold);
            }
            result.add(hold);
        }
        return result;
    }

    @Override
    public List<ExploreBundleRefundHold> syncRefundOutcome(Long refundId) {
        transaction();
        RefundApplication observed = refundId == null ? null : refunds.selectRefundApplicationById(refundId);
        if (observed == null || !"registration".equals(observed.getSourceType())) throw reject("BUNDLE_REFUND_NOT_FOUND", "原退款意图不存在");
        Scope scope = lockScope(observed.getSourceId());
        RefundApplication refund = intent(scope, refundId);
        List<ExploreBundleRefundHold> result = new ArrayList<>();
        for (CmsRegistration row : scope.rows) {
            if (!scope.orders.containsKey(row.getId())) continue;
            ExploreBundleRefundHold hold = holds.selectByRegistrationForUpdate(row.getId());
            if (hold == null) {
                // Record the external fact for investigation without rewriting consumed/penalized units.
                hold = new ExploreBundleRefundHold(); hold.setRegistrationId(row.getId()); hold.setHolderMemberId(row.getMemberId());
                hold.setSourceRegistrationId(refund.getSourceId()); hold.setRefundId(refundId); hold.setRefundNo(refund.getRefundNo());
                hold.setCashAmount(cents(refund.getRefundAmount())); hold.setState("RECONCILIATION_REQUIRED"); hold.setVersion(0);
                if (holds.insert(hold) != 1) throw reject("BUNDLE_CONCURRENT", "核对状态写入失败");
            } else if (!Objects.equals(hold.getRefundId(), refundId)) {
                // Late facts for a superseded intent must never complete the replacement intent's units.
                if (Integer.valueOf(RefundApplication.PAYOUT_SUCCESS).equals(refund.getPayoutStatus())
                        && !"RECONCILIATION_REQUIRED".equals(hold.getState())) {
                    hold.setState("RECONCILIATION_REQUIRED"); update(hold);
                }
            } else if (!Set.of("SUCCEEDED", "ZERO_CASH_COMPLETED", "RECONCILIATION_REQUIRED").contains(hold.getState())) {
                if (!Objects.equals(hold.getSourceRegistrationId(), refund.getSourceId())
                        || !Objects.equals(hold.getHolderMemberId(), row.getMemberId())
                        || !Objects.equals(hold.getRefundNo(), refund.getRefundNo())
                        || hold.getCashAmount().compareTo(cents(refund.getRefundAmount())) != 0) {
                    hold.setState("RECONCILIATION_REQUIRED"); update(hold);
                } else {
                    String state = outcome(refund);
                    if ("SUCCEEDED".equals(state)) {
                        List<ExploreBundleUnit> units = scope.units.get(row.getId());
                        if (units.isEmpty() || units.stream().anyMatch(u -> !Set.of("REFUND_PENDING", "REFUNDED").contains(u.getStatus()))
                                || !bookingsConsistent(units)) {
                            state = "RECONCILIATION_REQUIRED";
                        } else finish(units);
                    } else if (Integer.valueOf(1).equals(refund.getStatus())) {
                        if (bookingsConsistent(scope.units.get(row.getId()))) closeBookings(scope.units.get(row.getId()));
                        else state = "RECONCILIATION_REQUIRED";
                    }
                    if (!state.equals(hold.getState())) { hold.setState(state); update(hold); }
                }
            }
            result.add(hold);
        }
        return result;
    }

    @Override
    public void onRegistrationRefundApproved(Long registrationId) {
        transaction();
        if (bundles.selectOrderByRegistration(registrationId) == null) return;
        ExploreBundleRefundHold hold = holds.selectByRegistration(registrationId);
        if (hold == null) throw reject("BUNDLE_REFUND_NOT_PREPARED", "套票退款须先裁定并冻结，不可绕过罚扣");
        if ("ZERO_CASH_COMPLETED".equals(hold.getState())) return;
        if (hold.getRefundId() == null) {
            // Zero-cash cancellation is completed explicitly by completeZeroCashRefund in the outer transaction.
            if (hold.getCashAmount().signum() == 0 && "PREPARED".equals(hold.getState())) return;
            throw reject("BUNDLE_REFUND_UNBOUND", "退款冻结尚未绑定原意图");
        }
        syncRefundOutcome(hold.getRefundId());
    }

    private Scope lockScope(Long sourceId) {
        CmsRegistration observed = sourceId == null ? null : registrations.selectCmsRegistrationById(sourceId);
        if (observed == null) throw reject("BUNDLE_NOT_FOUND", "原付款报名不存在");
        List<CmsRegistration> rows;
        if (observed.getParentOrderNo() == null || observed.getParentOrderNo().isBlank()) {
            CmsRegistration locked = registrations.selectCmsRegistrationByIdForUpdate(sourceId);
            if (locked == null) throw reject("BUNDLE_NOT_FOUND", "原付款报名不存在");
            rows = new ArrayList<>(List.of(locked));
        } else {
            rows = new ArrayList<>(registrations.selectByParentOrderNoForUpdate(observed.getParentOrderNo(),
                    Long.valueOf(2L).equals(observed.getOwnerType()) ? observed.getOwnerId() : null));
            OrderRefundCash.assertOrderScope(rows, observed);
        }
        rows.sort(Comparator.comparing(CmsRegistration::getId));
        Scope scope = new Scope(rows, sourceId);
        for (CmsRegistration row : rows) {
            List<RefundApplication> history = refunds.selectRegistrationHistoryForUpdate(row.getId());
            for (RefundApplication refund : history == null ? List.<RefundApplication>of() : history) scope.history.put(refund.getId(), refund);
        }
        for (CmsRegistration row : rows) {
            ExploreBundleOrder order = bundles.selectOrderByRegistration(row.getId());
            if (order == null) continue;
            if (!Objects.equals(row.getMemberId(), order.getHolderMemberId())) throw reject("BUNDLE_REFUND_RECONCILIATION", "当前持有人与套票账本不一致");
            scope.orders.put(row.getId(), order);
            scope.units.put(row.getId(), bundles.selectAllUnitsForUpdate(row.getId(), row.getMemberId()));
        }
        return scope;
    }

    private RefundApplication intent(Scope scope, Long refundId) {
        RefundApplication refund = scope.history.get(refundId);
        if (refund == null || !"registration".equals(refund.getSourceType()) || !scope.sourceId.equals(refund.getSourceId())
                || !Objects.equals(refund.getMemberId(), scope.rows.stream().filter(r -> scope.sourceId.equals(r.getId())).findFirst().orElseThrow().getMemberId())
                || !Integer.valueOf(0).equals(refund.getDelFlag())
                || refund.getRefundNo() == null || refund.getRefundNo().isBlank() || cents(refund.getRefundAmount()).signum() <= 0) {
            throw reject("BUNDLE_REFUND_INTENT_MISMATCH", "原退款意图身份或金额不符");
        }
        return refund;
    }
    private static String outcome(RefundApplication refund) {
        if (Integer.valueOf(1).equals(refund.getPayoutStatus()) && Integer.valueOf(1).equals(refund.getStatus())) return "SUCCEEDED";
        if (Integer.valueOf(2).equals(refund.getStatus())) return "REJECTED_HELD";
        if (refund.getPayoutStatus() != null && Set.of(2, 3, 4).contains(refund.getPayoutStatus())) return "MANUAL_REVIEW_HELD";
        return Integer.valueOf(1).equals(refund.getStatus()) ? "APPROVED" : "BOUND";
    }
    private void finish(List<ExploreBundleUnit> units) {
        for (ExploreBundleUnit unit : units) {
            if (!"REFUND_PENDING".equals(unit.getStatus()) && !"REFUNDED".equals(unit.getStatus())) {
                throw reject("BUNDLE_REFUND_RECONCILIATION", "已到账退款的冻结单位不一致，须保留原资金事实并重试核对");
            }
        }
        for (ExploreBundleUnit unit : units) if (!"REFUNDED".equals(unit.getStatus())) move(unit, "REFUNDED", "REFUND_CONFIRMED");
        closeBookings(units);
    }
    private boolean unfulfilledMerchantExit(ExploreBundleUnit unit) {
        if (!"REFUND_PENDING".equals(unit.getStatus()) || !"MERCHANT_EXIT".equals(unit.getStatusReason())) return false;
        // Only the holder's persisted unused-exit decision qualifies, never an arbitrary pending status.
        return bundles.selectExitCasesByRegistrationForUpdate(unit.getRegistrationId()).stream().anyMatch(exit ->
                Objects.equals(exit.getUnitId(), unit.getId())
                        && Objects.equals(exit.getRegistrationId(), unit.getRegistrationId())
                        && Objects.equals(exit.getHolderMemberId(), unit.getHolderMemberId())
                        && (exit.getReservationId() == null || Objects.equals(exit.getReservationId(), unit.getReservationId()))
                        && "REFUND_UNFULFILLED".equals(exit.getDecision()));
    }
    private boolean bookingsConsistent(List<ExploreBundleUnit> units) {
        for (ExploreBundleUnit unit : units) {
            if (unit.getReservationId() == null) continue;
            ExploreBundleReservation booking = bundles.selectReservationByIdForUpdate(unit.getReservationId());
            if (booking == null || !Objects.equals(booking.getUnitId(), unit.getId())
                    || !Objects.equals(booking.getRegistrationId(), unit.getRegistrationId())
                    || !Objects.equals(booking.getHolderMemberId(), unit.getHolderMemberId())) return false;
        }
        return true;
    }
    private void closeBookings(List<ExploreBundleUnit> units) {
        for (ExploreBundleUnit unit : units) {
            if (unit.getReservationId() == null) continue;
            ExploreBundleReservation booking = bundles.selectReservationByIdForUpdate(unit.getReservationId());
            if (booking == null || !Objects.equals(booking.getUnitId(), unit.getId())
                    || !Objects.equals(booking.getRegistrationId(), unit.getRegistrationId())
                    || !Objects.equals(booking.getHolderMemberId(), unit.getHolderMemberId())) {
                throw reject("BUNDLE_REFUND_RECONCILIATION", "预约与冻结权益归属不一致");
            }
            if (booking != null && "BOOKED".equals(booking.getStatus())) {
                if (bundles.casReservationStatus(booking.getId(), booking.getVersion(), "BOOKED", "CANCELLED") != 1) {
                    throw reject("BUNDLE_CONCURRENT", "预约状态变化，退款事务须重试");
                }
            }
        }
    }
    private void move(ExploreBundleUnit unit, String target, String reason) {
        if (bundles.casUnitStatus(unit.getId(), unit.getVersion(), unit.getStatus(), target, null, null, reason) != 1) {
            throw reject("BUNDLE_CONCURRENT", "权益状态变化，退款事务须重试");
        }
    }
    private void update(ExploreBundleRefundHold hold) {
        if (holds.update(hold) != 1) throw reject("BUNDLE_CONCURRENT", "退款冻结版本变化");
        hold.setVersion(hold.getVersion() + 1);
    }
    private static BigDecimal cents(BigDecimal value) {
        if (value == null || value.signum() < 0) throw reject("BUNDLE_REFUND_AMOUNT", "退款金额事实缺失或非法");
        try { return value.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException e) { throw reject("BUNDLE_REFUND_AMOUNT", "金额必须精确到分"); }
    }
    private static void matchScope(ExploreBundleRefundHold hold, Long source, Long holder, BigDecimal cash) {
        if (hold == null || !Objects.equals(hold.getSourceRegistrationId(), source) || !Objects.equals(hold.getHolderMemberId(), holder)
                || hold.getCashAmount().compareTo(cash) != 0) throw reject("BUNDLE_REFUND_INTENT_MISMATCH", "冻结范围不符");
    }
    private static void transaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("F3 refund requires the caller's active refund transaction");
        }
    }
    private static void context(FreeExploreRefundContext context) {
        transaction();
        if (context == null || !TransactionSynchronizationManager.hasResource(context)) {
            throw reject("BUNDLE_REFUND_CONTEXT_EXPIRED", "准备上下文仅限原事务，重进须按原意图重读");
        }
    }
    private static FreeExploreBundleException reject(String code, String message) { return new FreeExploreBundleException(code, message); }
    private static final class Scope {
        final List<CmsRegistration> rows; final Long sourceId;
        final Map<Long, RefundApplication> history = new LinkedHashMap<>();
        final Map<Long, ExploreBundleOrder> orders = new LinkedHashMap<>();
        final Map<Long, List<ExploreBundleUnit>> units = new LinkedHashMap<>();
        Scope(List<CmsRegistration> rows, Long sourceId) { this.rows = rows; this.sourceId = sourceId; }
    }
}
