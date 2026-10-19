package com.chengyinhub.business.service.ai.scope;

import java.util.Objects;

/** Server-owned active conversation identity; independent of persistent NPC memory and grant refresh. */
public final class AiConversationScope
{
    private final AiScope scope;
    private final String conversationId;
    private final long generation;
    private final String fingerprint;

    public AiConversationScope(AiScope scope, String conversationId, long generation)
    {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.conversationId = AiContractSupport.text(conversationId, "conversationId");
        if (generation < 0) throw new IllegalArgumentException("conversation generation must not be negative");
        this.generation = generation;
        this.fingerprint = AiContractSupport.fingerprint("ai-conversation-v1", scope.getPersistentFingerprint(),
                conversationId, generation);
    }

    public AiScope getScope() { return scope; }
    public String getConversationId() { return conversationId; }
    public long getGeneration() { return generation; }
    public String getFingerprint() { return fingerprint; }

    public boolean sameConversation(AiConversationScope other)
    {
        return other != null && fingerprint.equals(other.fingerprint);
    }
}
