package com.chengyinhub.business.service.rendezvous;

/**
 * Narrow port kept by Group C. Synthetic doubles implement {@link #verifyCurrent}.
 * The same-transaction overload is what {@link RendezvousHandoffParticipantAdapter} overrides
 * to call Group A's frozen {@code verifyParticipants(Transaction, ticketId)} without opening
 * another transaction. A missing or non-safe verdict stays fail-closed until that type is merged.
 */
public interface RendezvousHandoffSafety {
    boolean verifyCurrent(String matchId, String ticketId, long actor, long peer, long now);

    /** Default ignores the open transaction. The A adapter must override this. */
    default boolean verifyCurrent(RendezvousConsentService.Transaction tx, String matchId, String ticketId,
                                  long actor, long peer, long now) {
        return verifyCurrent(matchId, ticketId, actor, peer, now);
    }

    /** Production stand-in until A's operation is merged. Never returns true. */
    final class Closed implements RendezvousHandoffSafety {
        public boolean verifyCurrent(String matchId, String ticketId, long actor, long peer, long now) {
            return false;
        }
    }
}
