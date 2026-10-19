package com.chengyinhub.business.home;

import com.chengyinhub.business.home.PrivateHomeContracts.*;

/**
 * Deliberately has no production implementation in this slice. Missing authority fails closed.
 * Implementations must use current server-authoritative native-client eligibility and approved safety,
 * radius, freshness, accuracy, dwell, privacy retention and timing rules. No client arrival boolean,
 * user agent or device attestation alone is location evidence. No reverse geocoding is required.
 * Calls must be read-only/local transactional decisions, never irreversible external effects.
 */
public interface PrivateHomeAuthority {
    void requireNative(Actor actor);
    Policy policy(Actor actor);
    boolean placementAllowed(Actor actor, Point point);
    ResourcePolicy resourcePolicy(Actor actor, String scope);
    Arrival verifyArrival(Actor actor, Home home, ResourcePolicy resource, String evidenceHandle);
    /** Recheck location consent, grant revocation, safe place and current resource authorization. */
    boolean grantCurrent(Actor actor, Home home, Proof proof);
}
