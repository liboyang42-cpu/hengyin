package com.chengyinhub.business.service.ai.scope;

import java.time.Instant;
import java.util.Objects;

/** Immutable narrative state only; never a commerce/progress permission or an automatic mutation. */
public final class AiAffectState
{
    private final AiScope scope;
    private final long version;
    private final String approvedMoodKey;
    private final String reasonEventId;
    private final Instant createdAt;
    private final Instant expiresAt;

    public AiAffectState(AiScope scope, long version, String approvedMoodKey, String reasonEventId,
                         Instant createdAt, Instant expiresAt)
    {
        this.scope = Objects.requireNonNull(scope, "scope");
        if (scope.getScenario() != AiScenario.STORE_PUBLIC_NPC && !scope.getScenario().isTheme())
        {
            throw new IllegalArgumentException("affect requires a public NPC or theme scope");
        }
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
        this.version = version;
        this.approvedMoodKey = AiContractSupport.text(approvedMoodKey, "approvedMoodKey");
        this.reasonEventId = AiContractSupport.text(reasonEventId, "reasonEventId");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("invalid affect expiry");
    }

    public boolean matches(AiScope requested, long expectedVersion, AiRetentionPolicy policy, Instant now)
    {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(now, "now");
        return scope.samePersistentScope(requested) && version == expectedVersion
                && requested.isActiveAt(now) && policy.permitsAffect(createdAt, expiresAt, now);
    }

    public long getVersion() { return version; }
    public String getApprovedMoodKey() { return approvedMoodKey; }
    public Instant getExpiresAt() { return expiresAt; }
    String fingerprint()
    {
        return AiContractSupport.fingerprint(scope.getPersistentFingerprint(), version, approvedMoodKey,
                reasonEventId, createdAt, expiresAt);
    }
}
