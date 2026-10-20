package com.chengyinhub.business.service.ai.scope;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Required product/privacy configuration: deliberately no default duration or consent policy. */
public final class AiRetentionPolicy
{
    private final String version;
    private final Duration memoryTtl;
    private final Duration affectTtl;
    private final Duration cacheTtl;
    private final Duration conversationTtl;

    public AiRetentionPolicy(String version, Duration memoryTtl, Duration affectTtl, Duration cacheTtl, Duration conversationTtl)
    {
        this.version = AiContractSupport.text(version, "retention policy version");
        this.memoryTtl = positive(memoryTtl, "memoryTtl");
        this.affectTtl = positive(affectTtl, "affectTtl");
        this.cacheTtl = positive(cacheTtl, "cacheTtl");
        this.conversationTtl = positive(conversationTtl, "conversationTtl");
    }

    private static Duration positive(Duration value, String name)
    {
        Objects.requireNonNull(value, name + " must be explicitly configured");
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    boolean permitsMemory(Instant createdAt, Instant expiresAt, Instant now)
    {
        return permits(createdAt, expiresAt, now, memoryTtl);
    }

    boolean permitsAffect(Instant createdAt, Instant expiresAt, Instant now)
    {
        return permits(createdAt, expiresAt, now, affectTtl);
    }

    boolean permitsConversation(Instant createdAt, Instant expiresAt, Instant now)
    {
        return permits(createdAt, expiresAt, now, conversationTtl);
    }

    private boolean permits(Instant createdAt, Instant expiresAt, Instant now, Duration ttl)
    {
        return !createdAt.isAfter(now) && now.isBefore(expiresAt) && expiresAt.isAfter(createdAt)
                && Duration.between(createdAt, expiresAt).compareTo(ttl) <= 0;
    }

    public Instant cacheExpiresAt(Instant now) { return now.plus(cacheTtl); }
    public String getVersion() { return version; }
    String fingerprint() { return AiContractSupport.fingerprint(version, memoryTtl, affectTtl, cacheTtl, conversationTtl); }
}
