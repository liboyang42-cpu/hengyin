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
 * Group A's frozen call is {@code OngoingVerdict verifyParticipants(Transaction, long, String, String, Long, boolean, long)}.
 * The adapter maps that to {@code verifyParticipants(tx, actor, ticketId, null, null, true, now)}
 * and allows only {@code safe && owned && mutual}. It is not wired;
 * {@link Closed} remains the service default. Publication authority is a separate
 * NOT_CONNECTED dependency. {@code now} is epoch millis from the caller's clock.
 */
public interface RendezvousRoleContentGuard {
    /**
     * Re-check both sides on the already-open transaction. Pass only an explicit
     * {@code OngoingVerdict} with {@code safe}, {@code owned} and {@code mutual} all true.
     * Null, an unknown result, and every partial combination throw {@link Rejection}
     * {@code SECRET_UNAVAILABLE}. A's missing or stranger verdict is {@code safe=false}
     * and {@code owned=false}; do not rewrite it as {@code NOT_FOUND}.
     * Eligibility, block, place, expiry and cancellation are A's {@code safe} flag.
     * Any unknown or failed check must not be treated as success.
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
