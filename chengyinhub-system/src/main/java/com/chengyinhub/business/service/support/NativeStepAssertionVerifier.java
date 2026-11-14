package com.chengyinhub.business.service.support;

/**
 * Deliberately has no production implementation. Attestation is app/key provenance, NOT proof of walking.
 * A future reviewed adapter must validate Apple App Attest against an enrolled, non-revoked account key,
 * the expected app identifier/environment and iPhone-only enrollment policy. Enrollment is out of scope.
 * verifyAndConsume must atomically CAS the GLOBAL key assertion counter in this SAME database transaction;
 * failure, counter reuse, wrong binding, missing configuration or unavailable registry must throw.
 * It must verify the exact SHA-256 clientDataHash of Request.canonicalPayload; never trust an echoed hash.
 */
public interface NativeStepAssertionVerifier {
    boolean isReady();
    boolean isEnrolledIPhoneKey(long accountId, String deviceKeyId);
    long verifyAndConsume(Request request, String assertion);

    final class Request {
        public final long accountId;
        public final long sessionId;
        public final String deviceKeyId;
        public final String canonicalPayload;
        public Request(long accountId, long sessionId, String deviceKeyId, String canonicalPayload) {
            this.accountId = accountId;
            this.sessionId = sessionId;
            this.deviceKeyId = deviceKeyId;
            this.canonicalPayload = canonicalPayload;
        }
    }
}
