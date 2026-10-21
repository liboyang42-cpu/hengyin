package com.chengyinhub.business.service.rendezvous;

/** Reviewed actor facts. {@link #unwired()} is not an authority and must not be treated as connected.
 * {@link #closed()} is connected and admits nobody. Not a Spring bean. */
public interface RendezvousEligibilityFacts {
    boolean connected();
    /** Null when the server has not approved a current rule version. */
    String currentVersion();
    /** False when the version, rule parameters, or actor facts are missing or do not match. */
    boolean admits(long actor, String clientVersion, long now);

    static RendezvousEligibilityFacts unwired() { return Unwired.INSTANCE; }
    static RendezvousEligibilityFacts closed() { return Closed.INSTANCE; }

    final class Unwired implements RendezvousEligibilityFacts {
        static final Unwired INSTANCE = new Unwired();
        public boolean connected() { return false; }
        public String currentVersion() { return null; }
        public boolean admits(long actor, String clientVersion, long now) { return false; }
    }
    final class Closed implements RendezvousEligibilityFacts {
        static final Closed INSTANCE = new Closed();
        public boolean connected() { return true; }
        public String currentVersion() { return null; }
        public boolean admits(long actor, String clientVersion, long now) { return false; }
    }
}
