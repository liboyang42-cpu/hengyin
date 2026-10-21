package com.chengyinhub.business.service.rendezvous;

import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Rejection;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Transaction;

/**
 * Port for the in-transaction safety recheck Group A is freezing.
 * An implementation runs on the caller's already-open consent guard transaction.
 * It must not call {@code RendezvousConsentService.read} and must not open or join
 * another transaction. A missing or closed port refuses; it never grants a secret.
 *
 * <p>This port stays {@code void recheck(Transaction tx, long actor, String ticketId, long now)}.
 * Group A's frozen call is {@code OngoingVerdict verifyParticipants(Transaction, String)}.
 * {@link RendezvousRoleContentGuardAdapter} is the mapping and is not wired;
 * {@link Closed} remains the service default. {@code now} is epoch millis from the
 * caller's clock. Checks read durable rows from {@code tx}, not an earlier snapshot.
 */
public interface RendezvousRoleContentGuard {
    /**
     * Re-read the current ticket and its match inside {@code tx} and require both
     * sides still safe. Reject unless all of the following hold:
     * the ticket exists and belongs to {@code actor} ({@code NOT_FOUND} otherwise);
     * the match is that ticket's current match and {@code MUTUAL_ACCEPTED}, with both
     * accept timestamps present; {@code now} is strictly before the match expiry even
     * when the stored state is still {@code MUTUAL_ACCEPTED}; both actors are still
     * eligible; they are not blocked; the public place is still allowed; cancellation,
     * safety stop, eligibility revocation and place revocation all refuse.
     * Any unknown or failed check throws {@link Rejection} and must not be treated as success.
     */
    void recheck(Transaction tx, long actor, String ticketId, long now);

    /** Production stand-in until Group A's implementation is wired. Always refuses. */
    final class Closed implements RendezvousRoleContentGuard {
        public static final Closed INSTANCE = new Closed();
        private Closed() {}
        public void recheck(Transaction tx, long actor, String ticketId, long now) {
            throw new Rejection("SECRET_UNAVAILABLE");
        }
    }
}
