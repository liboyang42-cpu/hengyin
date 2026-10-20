package com.chengyinhub.business.service.ai.scope;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;

final class AiScopeFixtures
{
    static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");
    static final String HASH = AiContractSupport.sha256("reviewed release");
    static final String PROMPT_PROJECTION_HASH = AiContractSupport.sha256("test-authorized-prompt-and-runtime-state");
    static final String INPUT_HASH = AiContractSupport.sha256("request");
    // Test-only values, not proposed retention defaults.
    static final AiRetentionPolicy RETENTION = new AiRetentionPolicy("test-policy",
            Duration.ofMinutes(20), Duration.ofMinutes(10), Duration.ofMinutes(5), Duration.ofMinutes(5));

    static AiScope.Authority authority()
    {
        return authority(1, AiScope.ActorType.PLAYER, 1, "access-1", "policy-1", "consent-1", 0);
    }

    static AiScope.Authority authority(long principal, AiScope.ActorType type, long actor,
                                        String access, String policy, String consent, long epoch)
    {
        return new AiScope.Authority(principal, type, actor, access, policy, consent, epoch, NOW.plusSeconds(3600), AiScope.AccessStatus.ACTIVE);
    }

    static AiReleaseReference release(long theme, long release)
    {
        return new AiReleaseReference(theme, release, HASH, 1, AiReleaseReference.Readiness.FROZEN_REVIEWED);
    }

    static AiScope scope(AiScenario scenario)
    {
        if (scenario.isTheme()) return AiScope.theme(scenario, authority(), release(10, 100), 1000, 2000, 20L);
        if (scenario == AiScenario.PLATFORM_ASSISTANT || scenario == AiScenario.CREATOR_ASSISTANT)
            return AiScope.assistant(scenario, authority(), 50);
        return AiScope.merchant(scenario, authority(), 20);
    }

    static AiKnowledge knowledge(AiScope scope, AiKnowledge.SourceKind kind, String id, String content)
    {
        return new AiKnowledge(scope, kind, AiKnowledge.Review.APPROVED, "review-" + id,
                id, "v1", content, true, NOW.plusSeconds(600));
    }

    static AiMemory memory(AiScope scope, String event, String content)
    {
        return new AiMemory(scope, AiMemory.Kind.RUNTIME_FACT, AiMemory.Provenance.SERVER_EVENT,
                event, content, NOW, NOW.plusSeconds(600));
    }

    static AiProjectionPolicy.KnowledgeProjection emptyKnowledge(AiScope scope)
    {
        return AiProjectionPolicy.knowledge(scope, Collections.emptyList(), Collections.emptyList(), NOW);
    }

    static AiProjectionPolicy.MemoryProjection emptyMemory(AiScope scope)
    {
        return AiProjectionPolicy.memory(scope, Collections.emptyList(), RETENTION, NOW);
    }

    static AiConversationScope conversation(AiScope scope)
    {
        return new AiConversationScope(scope, "conversation-1", 0);
    }

    static AiProjectionPolicy.ConversationProjection emptyConversation(AiScope scope)
    {
        return AiProjectionPolicy.conversation(conversation(scope), Collections.emptyList(), RETENTION, NOW);
    }

    static AiCacheIdentity cache(AiScope scope)
    {
        return AiCacheIdentity.create(scope, AiCacheIdentity.Purpose.RESPONSE, emptyKnowledge(scope),
                emptyMemory(scope), null, conversation(scope), emptyConversation(scope), RETENTION, "prompt-v1", "model-v1", "tools-v1", PROMPT_PROJECTION_HASH, INPUT_HASH, NOW);
    }
}
