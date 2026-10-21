package com.chengyinhub.business.home;

/**
 * Production assembly while no real verifier exists. NOT_CONNECTED: resolve never returns a verdict.
 * A test double must not be registered in its place.
 */
public final class PrivateHomeFailClosedEvidence implements PrivateHomeTrustedEvidence {
    @Override public Verdict resolve(Query query) { return null; }
}
