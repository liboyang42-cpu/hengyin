package com.chengyinhub.business.home;

import java.math.BigDecimal;

/** Private owner-only values. Never pass these types to public maps, NPCs, analytics or logs. */
public final class PrivateHomeContracts {
    private PrivateHomeContracts() { }
    public static class Redacted {
        @Override public final String toString() { return "PrivateHome[redacted]"; }
    }
    public static final class Actor extends Redacted {
        public final long ownerId, epoch;
        public Actor(long ownerId, long epoch) { this.ownerId = ownerId; this.epoch = epoch; }
    }
    public static final class Point extends Redacted {
        public final BigDecimal latitude, longitude;
        public final String datum;
        public Point(BigDecimal latitude, BigDecimal longitude, String datum) {
            this.latitude = latitude; this.longitude = longitude; this.datum = datum;
        }
    }
    public static class Home extends Redacted {
        public Long ownerId, version, changedAt, effectiveAt;
        public String label, datum, status;
        public BigDecimal latitude, longitude;
    }
    public static final class HomeView extends Redacted {
        public Long version, changedAt, effectiveAt;
        public String label, datum, status;
        public BigDecimal latitude, longitude;
        public HomeView(Home home) {
            version = home == null ? 0L : home.version;
            status = home == null ? "DELETED" : home.status;
            if (home != null) {
                changedAt = home.changedAt; effectiveAt = home.effectiveAt;
                label = home.label; datum = home.datum;
                latitude = home.latitude; longitude = home.longitude;
            }
        }
    }
    /** All timing values must be explicitly approved. No production numeric defaults. */
    public static final class Policy extends Redacted {
        public String version;
        public Long changeCooldownMillis, activationDelayMillis, proofTtlMillis;
    }
    public static final class ResourcePolicy extends Redacted {
        public String scope, resourceType, scopeRevision, ruleVersion;
        public Long restoreCooldownMillis, cap;
    }
    /** One manual fix. Not a background track. Coordinates stay off receipts and logs. */
    public static final class Observation extends Redacted {
        public BigDecimal latitude, longitude;
        public String datum, permission, network;
        public Long accuracyMeters, observedAtEpochMillis, dwellMillis;
    }
    public static final class Arrival extends Redacted {
        /** Opaque, non-location receipt from the authoritative provider; no raw GPS evidence. */
        public String grantId;
        public long ownerId, homeVersion, epoch, expiresAt;
        public String scope, scopeRevision, ruleVersion, action;
    }
    public static final class Resource extends Redacted {
        public long current, cap, version;
    }
    public static class Mutation extends Redacted {
        public Long ownerId, expectedVersion, resultVersion, createdAt;
        public String requestId, operation, decision;
    }
    public static class Proof extends Redacted {
        public Long ownerId, homeVersion, epoch, issuedAt, expiresAt, consumedAt;
        public String proofId, requestId, scope, scopeRevision, ruleVersion, policyVersion, grantId, grantFingerprint, resourceType, action, status;
    }
    /** Also the minimal resource-restore ledger/event. It never contains a home point or GPS evidence. */
    public static class Receipt extends Redacted {
        public Long ownerId, homeVersion, createdAt, beforeValue, afterValue, amount;
        public String receiptId, requestId, proofId, scope, scopeRevision, ruleVersion, decision;
    }
}
