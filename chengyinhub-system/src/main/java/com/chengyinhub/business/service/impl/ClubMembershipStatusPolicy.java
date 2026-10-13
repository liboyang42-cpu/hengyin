package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ClubMembershipTerm;

import java.time.Instant;

/** 付费会员期的确定性状态投影；只有 ACTIVE/GRACE 代表当前有权益。 */
public class ClubMembershipStatusPolicy {

    public String evaluate(ClubMembershipTerm term, Instant now) {
        if (term == null || now == null) throw new IllegalArgumentException("会员期或时钟不能为空");
        if (ClubMembershipTerm.STATUS_CANCELLED.equals(term.getStatus())
                || ClubMembershipTerm.STATUS_REFUNDED.equals(term.getStatus())) {
            return term.getStatus();
        }
        if (term.getStartsAt() == null || term.getEndsAt() == null
                || term.getGraceEndsAt() == null || term.getExpiresAt() == null) {
            throw new IllegalArgumentException("会员期时间快照不完整");
        }
        if (now.isBefore(term.getStartsAt().toInstant())) return ClubMembershipTerm.STATUS_PENDING;
        if (now.isBefore(term.getEndsAt().toInstant())) return ClubMembershipTerm.STATUS_ACTIVE;
        if (now.isBefore(term.getGraceEndsAt().toInstant())) return ClubMembershipTerm.STATUS_GRACE;
        if (now.isBefore(term.getExpiresAt().toInstant())) return ClubMembershipTerm.STATUS_PAST_DUE;
        return ClubMembershipTerm.STATUS_EXPIRED;
    }

    public boolean hasEntitlement(ClubMembershipTerm term, Instant now) {
        String status = evaluate(term, now);
        return ClubMembershipTerm.STATUS_ACTIVE.equals(status)
                || ClubMembershipTerm.STATUS_GRACE.equals(status);
    }
}
