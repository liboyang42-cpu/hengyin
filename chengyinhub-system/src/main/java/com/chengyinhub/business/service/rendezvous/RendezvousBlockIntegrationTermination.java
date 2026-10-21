package com.chengyinhub.business.service.rendezvous;

/**
 * Narrow port for stopping the current shared rendezvous inside the caller's
 * already-open consent guard transaction. Not a Spring bean.
 *
 * <p>{@link RendezvousBlockIntegration#consentStop} wires this method to
 * {@code RendezvousConsentService.terminateCurrentMatch(tx, actor, other)}.
 * That instance method writes {@code SAFETY_STOPPED}, releases both reservations,
 * and is idempotent. The three-argument {@code RendezvousImBlockMutation} does
 * not use this wiring; it passes {@link RendezvousBlockIntegration#guardStop}.
 * {@code ImServiceImpl.block} is not wired. Do not open a nested transaction,
 * dissolve a team, delete a chat, or revive a terminal match.
 */
public interface RendezvousBlockIntegrationTermination {
    void terminateCurrentMatch(RendezvousConsentService.Transaction tx, long actor, long other);
}
