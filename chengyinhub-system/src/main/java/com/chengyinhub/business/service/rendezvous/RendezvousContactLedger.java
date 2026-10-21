package com.chengyinhub.business.service.rendezvous;

import java.util.Objects;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Rejection;

/** Charges both actors for one match. Must run inside the consent guard transaction.
 * A missing scope or window refuses. This class does not invent a lifetime window.
 * The same match and actor is charged once. Not a Spring bean. */
public final class RendezvousContactLedger {
    private final RendezvousContactMapper mapper;
    public RendezvousContactLedger(RendezvousContactMapper mapper) { this.mapper = Objects.requireNonNull(mapper); }

    public void charge(String matchId, long first, long second, String pool, long now) {
        if (matchId == null || matchId.trim().isEmpty() || pool == null || pool.trim().isEmpty()) throw new Rejection("INVALID_INPUT");
        if (first <= 0 || second <= 0 || first == second) throw new Rejection("PAIR_UNAVAILABLE");
        RendezvousContactMapper.Quota quota = mapper.quota(pool);
        if (!open(quota)) throw new Rejection("POLICY_CLOSED");
        apply(matchId, first, quota, pool, now);
        apply(matchId, second, quota, pool, now);
    }

    private void apply(String matchId, long actor, RendezvousContactMapper.Quota quota, String pool, long now) {
        if (mapper.eventExists(matchId, actor) != null) return;
        long since = Math.subtractExact(now, quota.windowMillis);
        if (mapper.countInWindow(actor, pool, quota.scope, since, now) >= quota.maxContacts) throw new Rejection("PAIR_UNAVAILABLE");
        if (mapper.insertEvent(matchId, actor, pool, quota.scope, now) != 1) throw new IllegalStateException("W16_CONTACT_NOT_CHARGED");
    }

    private static boolean open(RendezvousContactMapper.Quota quota) {
        return quota != null && "OPEN".equals(quota.decision)
                && quota.scope != null && !quota.scope.trim().isEmpty()
                && quota.windowMillis != null && quota.windowMillis > 0
                && quota.maxContacts != null && quota.maxContacts > 0;
    }
}
