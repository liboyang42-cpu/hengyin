package com.chengyinhub.business.externalgame;

/**
 * Credits creator traffic rewards or paid sales earnings (TEST-only for real money balances).
 * Implementations run inside the caller's database transaction.
 */
public interface ExternalGameWalletBridge {
    void creditPoints(long memberId, int points, String businessKey, String reason);

    /**
     * G3: credits creator traffic points inside the points-governance caps. Returns the points credited (0 when
     * governance refused, nothing written) and the governance outcome.
     */
    default PointsCredit creditPointsGoverned(long memberId, int points, String businessKey, String reason) {
        creditPoints(memberId, points, businessKey, reason);
        return new PointsCredit(points, "UNGOVERNED");
    }

    record PointsCredit(int credited, String outcome) {}

    void creditBalanceFen(long memberId, long fen, String businessKey, String reason);
}
