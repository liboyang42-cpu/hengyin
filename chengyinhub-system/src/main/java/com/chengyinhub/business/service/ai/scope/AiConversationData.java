package com.chengyinhub.business.service.ai.scope;

import java.time.Instant;
import java.util.Objects;

/** Transient history/result data cannot be promoted to persistent memory or another conversation. */
public final class AiConversationData
{
    public enum Kind { TRANSCRIPT, IN_FLIGHT_RESULT, REPLAY }

    private final AiConversationScope conversation;
    private final Kind kind;
    private final String sourceKey;
    private final String untrustedText;
    private final Instant createdAt;
    private final Instant expiresAt;

    public AiConversationData(AiConversationScope conversation, Kind kind, String sourceKey,
                               String untrustedText, Instant createdAt, Instant expiresAt)
    {
        this.conversation = Objects.requireNonNull(conversation, "conversation");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.sourceKey = AiContractSupport.text(sourceKey, "sourceKey");
        this.untrustedText = AiContractSupport.text(untrustedText, "untrustedText");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("invalid conversation data expiry");
    }

    public Kind getKind() { return kind; }
    public String getSourceKey() { return sourceKey; }
    public String getUntrustedText() { return untrustedText; }
    public Instant getExpiresAt() { return expiresAt; }

    boolean isRetrievable(AiConversationScope requested, AiRetentionPolicy policy, Instant now)
    {
        return conversation.sameConversation(requested) && policy.permitsConversation(createdAt, expiresAt, now);
    }

    String identity() { return AiContractSupport.fingerprint(kind, sourceKey); }
    String fingerprint()
    {
        return AiContractSupport.fingerprint(conversation.getFingerprint(), kind, sourceKey,
                AiContractSupport.sha256(untrustedText), createdAt, expiresAt);
    }
}
