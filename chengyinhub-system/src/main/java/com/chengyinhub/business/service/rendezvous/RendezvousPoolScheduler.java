package com.chengyinhub.business.service.rendezvous;

import java.util.List;
import java.util.Objects;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Rejection;

/** Server-side pairing. The client does not name the other person or a place.
 * An unapproved place or frequency cap refuses the pair. Not a Spring bean and not an HTTP route. */
public final class RendezvousPoolScheduler {
    public interface Waiting {
        List<String> waitingIds(String pool, long now, int limit);
        long actorOf(String ticketId);
    }
    /** Runs inside the consent guard transaction. Throw to roll the pair back. */
    public interface Contacts { void charge(String matchId, long first, long second, String pool, long now); }
    private final RendezvousConsentService consent;
    private final RendezvousVersionedPolicy policy;
    private final Waiting waiting;
    private final Contacts contacts;
    private final java.time.Clock clock;
    public RendezvousPoolScheduler(RendezvousConsentService consent, RendezvousVersionedPolicy policy,
                                   Waiting waiting, Contacts contacts, java.time.Clock clock) {
        this.consent = Objects.requireNonNull(consent);
        this.policy = Objects.requireNonNull(policy);
        this.waiting = Objects.requireNonNull(waiting);
        this.contacts = Objects.requireNonNull(contacts);
        this.clock = Objects.requireNonNull(clock);
    }
    public String pairOnce(String pool) {
        if (pool == null || pool.trim().isEmpty()) throw new Rejection("INVALID_INPUT");
        String place = policy.approvedPlace(pool);
        if (place == null) throw new Rejection("POLICY_CLOSED");
        long now = clock.millis();
        List<String> ids = waiting.waitingIds(pool, now, 8);
        Rejection last = new Rejection("PAIR_UNAVAILABLE");
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                String first = ids.get(i), second = ids.get(j);
                try {
                    return consent.propose(first, second, place, matchId ->
                            contacts.charge(matchId, waiting.actorOf(first), waiting.actorOf(second), pool, now));
                } catch (Rejection rejected) {
                    last = rejected;
                    if (!"PAIR_UNAVAILABLE".equals(rejected.getCode())) throw rejected;
                }
            }
        }
        throw last;
    }
}
