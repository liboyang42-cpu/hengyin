package com.chengyinhub.business.externalgame;

/**
 * Credits creator traffic rewards or paid sales earnings (TEST-only for real money balances).
 * Implementations run inside the caller's database transaction.
 */
public interface ExternalGameWalletBridge {
    void creditPoints(long memberId, int points, String businessKey, String reason);

    void creditBalanceFen(long memberId, long fen, String businessKey, String reason);

    /** TEST-only buyer side of a paired TEST payment. Must fail (throw) when the balance is insufficient. */
    void debitBalanceFen(long memberId, long fen, String businessKey, String reason);
}
