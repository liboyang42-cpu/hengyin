package com.chengyinhub.business.reward;

/** Persistence rows, intentionally not exposed as API objects. */
public final class NonCashRewardRows {
    private NonCashRewardRows() { }
    public static final class PledgeRow {
        public String pledgeId, fingerprint, payload;
        public long total, available, reserved, awarded, consumed, retired;
    }
    public static final class ReservationRow {
        public String reservationId, pledgeId, state;
        public long memberId;
    }
    public static final class AwardRow {
        public String awardId, pledgeId, sourceEventId, reservationId, state;
        public long memberId, awardedAt;
    }
}
