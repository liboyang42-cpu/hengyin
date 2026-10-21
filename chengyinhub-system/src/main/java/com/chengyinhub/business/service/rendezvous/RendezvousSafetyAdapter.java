package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.mapper.ImBlockMapper;
import java.util.Objects;

/** Reuses the same bidirectional block authority as PlayTeamServiceImpl.
 * Requires separately reviewed eligibility and public-place policy; never infers eligibility
 * from nearby presence, a ticket purchase, client age or device proximity.
 * Not registered automatically: no runtime capability is enabled by adding this source.
 */
public final class RendezvousSafetyAdapter implements RendezvousConsentService.Safety {
    public interface ReviewedPolicy {
        boolean eligible(long actor, String pool, String eligibilityVersion, long now);
        boolean publicMeetingAllowed(String pool, String place, long now);
        boolean compatible(long firstActor, long secondActor, String pool, long now);
    }
    private final ImBlockMapper blocks;
    private final ReviewedPolicy policy;
    public RendezvousSafetyAdapter(ImBlockMapper blocks, ReviewedPolicy policy) {
        this.blocks = Objects.requireNonNull(blocks); this.policy = Objects.requireNonNull(policy);
    }
    public boolean eligible(long actor, String pool, String version, long now) {
        return policy.eligible(actor, pool, version, now);
    }
    public boolean blocked(long firstActor, long secondActor) { return blocks.existsBetween(firstActor, secondActor) > 0; }
    public boolean compatible(long a, long b, String pool, long now) { return policy.compatible(a, b, pool, now); }
    public boolean publicMeetingAllowed(String pool, String place, long now) {
        return policy.publicMeetingAllowed(pool, place, now);
    }
}
