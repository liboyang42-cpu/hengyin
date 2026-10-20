package com.chengyinhub.business.service.ai.scope;

import java.time.Instant;
import java.util.Objects;

/** Scope-local memory. No cross-run carryover, persistence, deletion worker or event trust is implied. */
public final class AiMemory
{
    public enum Kind { RUNTIME_FACT, CONSENTED_PREFERENCE, UNVERIFIED_MODEL_SUMMARY }
    public enum Provenance { SERVER_EVENT, EXPLICIT_USER_CONSENT, MODEL_OUTPUT }

    private final AiScope scope;
    private final Kind kind;
    private final String sourceEventId;
    private final String untrustedText;
    private final Instant createdAt;
    private final Instant expiresAt;

    public AiMemory(AiScope scope, Kind kind, Provenance provenance, String sourceEventId,
                    String untrustedText, Instant createdAt, Instant expiresAt)
    {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.kind = Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(provenance, "provenance");
        if ((kind == Kind.RUNTIME_FACT && provenance != Provenance.SERVER_EVENT)
                || (kind == Kind.CONSENTED_PREFERENCE && provenance != Provenance.EXPLICIT_USER_CONSENT)
                || (kind == Kind.UNVERIFIED_MODEL_SUMMARY && provenance != Provenance.MODEL_OUTPUT))
        {
            throw new IllegalArgumentException("memory kind/provenance mismatch");
        }
        this.sourceEventId = AiContractSupport.text(sourceEventId, "sourceEventId");
        this.untrustedText = AiContractSupport.text(untrustedText, "untrustedText");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("invalid memory expiry");
    }

    public AiScope getScope() { return scope; }
    public Kind getKind() { return kind; }
    public String getSourceEventId() { return sourceEventId; }
    public String getUntrustedText() { return untrustedText; }
    public Instant getExpiresAt() { return expiresAt; }

    boolean isRetrievable(AiScope requested, AiRetentionPolicy policy, Instant now)
    {
        return scope.samePersistentScope(requested) && kind != Kind.UNVERIFIED_MODEL_SUMMARY
                && policy.permitsMemory(createdAt, expiresAt, now);
    }

    String fingerprint()
    {
        return AiContractSupport.fingerprint(scope.getPersistentFingerprint(), kind, sourceEventId,
                AiContractSupport.sha256(untrustedText), createdAt, expiresAt);
    }
}
