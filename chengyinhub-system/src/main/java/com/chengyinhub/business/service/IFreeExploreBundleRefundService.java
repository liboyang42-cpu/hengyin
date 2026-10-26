package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ExploreBundleRefundHold;
import com.chengyinhub.business.service.support.FreeExploreRefundContext;
import java.math.BigDecimal;
import java.util.List;

/** Internal same-transaction API for W8-01. No HTTP endpoint and no provider invocation. */
public interface IFreeExploreBundleRefundService {
    /** Call before inventory/points/registration effects. Locks full payment group then histories then units. */
    FreeExploreRefundContext prepareFullRefund(Long sourceRegistrationId, BigDecimal refundCash);
    /** saveRefund returns a row count: pass its generated app.id here, never that count. */
    List<ExploreBundleRefundHold> bindFullRefund(FreeExploreRefundContext context, Long refundId, Long replacesRefundId);
    /** Pure zero-cash branch, after the caller's conditional cancellation has succeeded; no synthetic refund ID. */
    List<ExploreBundleRefundHold> completeZeroCashRefund(FreeExploreRefundContext context);
    /**
     * Reads persisted financial status; never accepts caller-supplied outcome enums.
     * For channel outcomes invoke in a fresh caller transaction AFTER the financial fact commits;
     * persist/retry the original refund ID on failure so F3 reconciliation cannot roll back real cash facts.
     * Approval may call this inside its preparation transaction via the legacy hook.
     * Rejection, UNKNOWN and manual payout retain the freeze; this API never unfreezes units.
     */
    List<ExploreBundleRefundHold> syncRefundOutcome(Long refundId);
    /** Legacy releasePerks hook is approval-only, never evidence of cash arrival. */
    void onRegistrationRefundApproved(Long registrationId);
}
