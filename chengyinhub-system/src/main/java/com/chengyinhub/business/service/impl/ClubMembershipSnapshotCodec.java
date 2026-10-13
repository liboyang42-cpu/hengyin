package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ClubMembershipInvoice;
import com.chengyinhub.business.service.support.CanonicalDigest;

/** 会费账单不可变字段的 canonical 指纹；回调前必须重算并匹配。 */
public final class ClubMembershipSnapshotCodec {
    private ClubMembershipSnapshotCodec() {
    }

    public static String hash(ClubMembershipInvoice invoice) {
        if (invoice == null) return null;
        return CanonicalDigest.sha256Hex(CanonicalDigest.join(
                value(invoice.getClubId()), value(invoice.getOwnerMemberIdSnapshot()),
                value(invoice.getMemberId()), value(invoice.getPlanId()),
                value(invoice.getPlanVersionSnapshot()), value(invoice.getPlanNameSnapshot()),
                value(invoice.getDurationDaysSnapshot()), value(invoice.getAmountFen()),
                value(invoice.getBenefitsSnapshot()), value(invoice.getGraceDaysSnapshot()),
                value(invoice.getPastDueDaysSnapshot()), value(invoice.getExitPolicySnapshot()),
                value(invoice.getRefundPolicySnapshot())));
    }

    private static String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
