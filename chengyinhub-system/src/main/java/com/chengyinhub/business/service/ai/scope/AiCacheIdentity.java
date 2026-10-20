package com.chengyinhub.business.service.ai.scope;

import java.time.Instant;
import java.util.Objects;

/** Pure key/expiry contract. Does not touch Redis, store responses, or authorize a cache hit. */
public final class AiCacheIdentity
{
    public enum Purpose
    {
        KNOWLEDGE, MEMORY, AFFECT, RESPONSE, TRANSCRIPT, IN_FLIGHT_RESULT, REPLAY;

        boolean conversationBound()
        {
            return this == RESPONSE || this == TRANSCRIPT || this == IN_FLIGHT_RESULT || this == REPLAY;
        }
    }

    private final String key;
    private final Instant expiresAt;

    private AiCacheIdentity(String key, Instant expiresAt)
    {
        this.key = key;
        this.expiresAt = expiresAt;
    }

    /**
     * authorizedPromptProjectionHash is mandatory and distinct from the user's request hash.
     * A trusted adapter must hash ALL actually assembled authorized prompt inputs, including the
     * current run-state revision and allowed branch/environment snapshot when used. A scope ID,
     * request digest or fixed placeholder is not a valid substitute. No adapter is activated here.
     */
    public static AiCacheIdentity create(AiScope scope, Purpose purpose,
                                         AiProjectionPolicy.KnowledgeProjection knowledge,
                                         AiProjectionPolicy.MemoryProjection memory, AiAffectState affect,
                                         AiConversationScope conversation, AiProjectionPolicy.ConversationProjection transientContext,
                                         AiRetentionPolicy retention, String promptVersion, String modelVersion,
                                         String toolPolicyVersion, String authorizedPromptProjectionHash,
                                         String requestContentHash, Instant now)
    {
        Objects.requireNonNull(scope, "scope").requireActiveAt(now);
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(knowledge, "knowledge");
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(retention, "retention must be explicitly configured");
        if (!scope.sameScope(knowledge.scope()) || !scope.sameScope(memory.scope())
                || !memory.retentionFingerprint().equals(retention.fingerprint())
                || !now.isBefore(knowledge.getValidUntil()) || !now.isBefore(memory.getValidUntil()))
        {
            throw new IllegalArgumentException("cache inputs are stale or belong to another scope/policy");
        }
        if (purpose == Purpose.AFFECT && affect == null)
        {
            throw new IllegalArgumentException("affect state is required for affect cache identity");
        }
        if (affect != null && !affect.matches(scope, affect.getVersion(), retention, now))
        {
            throw new IllegalArgumentException("affect is stale or belongs to another scope");
        }
        if (purpose.conversationBound())
        {
            if (conversation == null || transientContext == null
                    || !scope.sameScope(conversation.getScope())
                    || !conversation.sameConversation(transientContext.conversation())
                    || !scope.sameScope(transientContext.conversation().getScope())
                    || !transientContext.retentionFingerprint().equals(retention.fingerprint())
                    || !now.isBefore(transientContext.getValidUntil()))
                throw new IllegalArgumentException("session cache requires current explicit conversation and context");
        }
        else if (conversation != null || transientContext != null)
            throw new IllegalArgumentException("persistent cache must not contain transient conversation data");
        String digest = AiContractSupport.fingerprint("ai-cache-contract-v1", scope.getFingerprint(), purpose,
                knowledge.fingerprint(), memory.fingerprint(), affect == null ? null : affect.fingerprint(),
                conversation == null ? null : conversation.getFingerprint(),
                transientContext == null ? null : transientContext.fingerprint(),
                retention.fingerprint(), AiContractSupport.text(promptVersion, "promptVersion"),
                AiContractSupport.text(modelVersion, "modelVersion"),
                AiContractSupport.text(toolPolicyVersion, "toolPolicyVersion"),
                AiContractSupport.hash(authorizedPromptProjectionHash, "authorizedPromptProjectionHash"),
                AiContractSupport.hash(requestContentHash, "requestContentHash"));
        Instant expires = AiProjectionPolicy.earlier(retention.cacheExpiresAt(now), scope.getValidUntil());
        expires = AiProjectionPolicy.earlier(expires, knowledge.getValidUntil());
        expires = AiProjectionPolicy.earlier(expires, memory.getValidUntil());
        if (transientContext != null) expires = AiProjectionPolicy.earlier(expires, transientContext.getValidUntil());
        if (affect != null) expires = AiProjectionPolicy.earlier(expires, affect.getExpiresAt());
        return new AiCacheIdentity("ai:contract:v1:" + purpose.name() + ":" + digest, expires);
    }

    public String getKey() { return key; }
    public Instant getExpiresAt() { return expiresAt; }
}
