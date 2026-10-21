package com.chengyinhub.business.service.rendezvous;

/**
 * Narrow port for stopping the current shared rendezvous inside the caller's
 * already-open consent guard transaction. Not a Spring bean.
 *
 * <p>Group A owns the consent transaction. This tree does not contain A's frozen
 * {@code verifyParticipants} or a terminate method, so production binding is
 * fail-closed until A publishes the signature below. A synthetic double may
 * implement this interface in tests. Do not open a nested transaction, dissolve
 * a team, delete a chat, or revive a terminal match.
 *
 * <p>Signature H needs A to add, same transaction, no nested {@code Store.transaction}:
 * <pre>
 * void terminateCurrentMatch(RendezvousConsentService.Transaction tx, long actor, long other);
 * </pre>
 * No shared active match: return without writing. Shared {@code PROPOSED} or
 * {@code MUTUAL_ACCEPTED} match: stop both placeholders in this transaction
 * ({@code SAFETY_STOPPED}, reservations released) and leave every other match
 * untouched. Already terminal: do not reactivate. Corrupt reservation or a
 * failed write: throw so the caller rolls the block insert back.
 */
public interface RendezvousBlockIntegrationTermination {
    void terminateCurrentMatch(RendezvousConsentService.Transaction tx, long actor, long other);
}
