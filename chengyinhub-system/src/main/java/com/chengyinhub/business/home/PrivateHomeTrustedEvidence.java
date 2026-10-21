package com.chengyinhub.business.home;

/**
 * Internal boundary for a private-home return. There is no dwell, coordinate, or device-integrity input:
 * a format-valid handle alone is not a verdict. No production verifier is registered.
 */
public interface PrivateHomeTrustedEvidence {
    /** Stored verdict, or null when unknown, mismatched, stale, or unverified. Do not copy the query over a stored result. */
    Verdict resolve(Query query);

    /** SHA-256 hex of the evidence identity. The raw handle is not what the proof stores. */
    static String fingerprint(String evidenceId) {
        if (evidenceId == null) return null;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(evidenceId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }

    final class Query extends PrivateHomeContracts.Redacted {
        public final long ownerId, homeVersion, now;
        public final String resourceScope, evidenceId, ruleVersion;
        public Query(long ownerId, long homeVersion, String resourceScope, String evidenceId, String ruleVersion, long now) {
            this.ownerId = ownerId;
            this.homeVersion = homeVersion;
            this.resourceScope = resourceScope;
            this.evidenceId = evidenceId;
            this.ruleVersion = ruleVersion;
            this.now = now;
        }
    }

    final class Verdict extends PrivateHomeContracts.Redacted {
        public final long ownerId, homeVersion, freshUntil;
        public final String evidenceId, resourceScope, ruleVersion;
        public Verdict(String evidenceId, long ownerId, long homeVersion, String resourceScope, String ruleVersion, long freshUntil) {
            this.evidenceId = evidenceId;
            this.ownerId = ownerId;
            this.homeVersion = homeVersion;
            this.resourceScope = resourceScope;
            this.ruleVersion = ruleVersion;
            this.freshUntil = freshUntil;
        }
    }
}
