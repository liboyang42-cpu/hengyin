package com.chengyinhub.business.city;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.chengyinhub.business.city.CityChecks.*;
import static com.chengyinhub.business.city.CityContracts.*;

/**
 * Read-only proposed mutations of the EXISTING UmsMember.point and UmsMemberPointsDetail.
 * Does not award/deduct points, persist reservations, or establish database atomicity.
 * A transaction adapter must re-read/lock balances and consume the reservation atomically.
 */
public final class CityPointSettlementPlan {
    // Audited baseline ums_member.point is signed INT; this is a storage bound, not game economy.
    public static final long MAX_SUPPORTED_POINTS = Integer.MAX_VALUE;
    public final String effectKey;
    public final String rulesReleaseId;
    public final String offerFingerprint;
    public final String budgetReservationId;
    public final long grossBudgetConsumption;
    public final List<MemberEntry> entries;
    public final List<Long> memberLockOrder;

    private CityPointSettlementPlan(Offer offer, RewardReservation reservation, List<MemberEntry> entries) {
        this.effectKey = offer.settlementKey();
        this.rulesReleaseId = offer.rulesReleaseId;
        this.offerFingerprint = offer.fingerprint;
        this.budgetReservationId = reservation == null ? null : reservation.reservationId;
        this.grossBudgetConsumption = offer.grossReward();
        this.entries = Collections.unmodifiableList(new ArrayList<MemberEntry>(entries));
        List<Long> locks = new ArrayList<Long>();
        for (MemberEntry entry : entries) locks.add(entry.memberId);
        this.memberLockOrder = Collections.unmodifiableList(locks);
    }

    public static CityPointSettlementPlan prepare(Offer offer, RewardReservation reservation,
                                                   Map<Long, Long> lockedPointSnapshots) {
        require(offer != null && lockedPointSnapshots != null, "MISSING_SETTLEMENT_INPUT");
        RewardReservation.requireForOffer(offer, reservation);
        Set<Long> recipients = new TreeSet<Long>(offer.rewards.keySet());
        recipients.addAll(offer.feeCredits.keySet());
        recipients.add(offer.payerId);
        List<MemberEntry> entries = new ArrayList<MemberEntry>();
        for (Long memberId : recipients) {
            Long before = lockedPointSnapshots.get(memberId);
            require(before != null && before >= 0 && before <= MAX_SUPPORTED_POINTS, "AUTHORITATIVE_BALANCE_REQUIRED");
            long credit = offer.rewards.containsKey(memberId) ? offer.rewards.get(memberId) : 0L;
            long feeCredit = offer.feeCredits.containsKey(memberId) ? offer.feeCredits.get(memberId) : 0L;
            long debit = memberId == offer.payerId ? offer.fee : 0L;
            require(credit <= MAX_SUPPORTED_POINTS && feeCredit <= MAX_SUPPORTED_POINTS && debit <= MAX_SUPPORTED_POINTS,
                    "POINT_STORAGE_LIMIT_EXCEEDED");
            // Never pool another beneficiary's reward or existing balance to pay the payer's cost.
            require(Math.addExact(before, credit) >= debit, "PAYER_POINTS_INSUFFICIENT");
            long net = Math.subtractExact(Math.addExact(credit, feeCredit), debit);
            long after = Math.addExact(before, net);
            require(after >= 0, "PAYER_POINTS_INSUFFICIENT");
            require(after <= MAX_SUPPORTED_POINTS, "POINT_STORAGE_LIMIT_EXCEEDED");
            entries.add(new MemberEntry(memberId, before, credit, feeCredit, debit, net, after,
                    key("POINT_DETAIL", offer.settlementKey(), Long.toString(memberId)),
                    credit > 0 ? offer.rewardKey(memberId) : null));
        }
        return new CityPointSettlementPlan(offer, reservation, entries);
    }

    public static final class MemberEntry {
        public final long memberId;
        public final long expectedBefore;
        public final long rewardCredit;
        public final long feeCredit;
        public final long feeDebit;
        public final long netChange;
        public final long proposedAfter;
        public final String businessKey;
        public final String rewardBusinessKey;

        private MemberEntry(long memberId, long before, long credit, long feeCredit, long debit, long net, long after,
                            String businessKey, String rewardBusinessKey) {
            this.memberId = memberId;
            this.expectedBefore = before;
            this.rewardCredit = credit;
            this.feeCredit = feeCredit;
            this.feeDebit = debit;
            this.netChange = net;
            this.proposedAfter = after;
            this.businessKey = businessKey;
            this.rewardBusinessKey = rewardBusinessKey;
        }
    }
}
