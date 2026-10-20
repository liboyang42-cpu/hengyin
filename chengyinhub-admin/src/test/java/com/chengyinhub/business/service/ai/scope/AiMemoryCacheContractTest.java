package com.chengyinhub.business.service.ai.scope;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.chengyinhub.business.service.ai.scope.AiScopeFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class AiMemoryCacheContractTest
{
    @ParameterizedTest
    @EnumSource(AiScenario.class)
    void memoryRemainsInExactScenarioScopeAndIsImmutable(AiScenario scenario)
    {
        AiScope requested = scope(scenario);
        AiMemory own = memory(requested, "runtime-event", "CANARY_CURRENT_SCOPE");
        AiProjectionPolicy.MemoryProjection projected = AiProjectionPolicy.memory(requested,
                Collections.singletonList(own), RETENTION, NOW);
        assertEquals(1, projected.getMemories().size());
        assertThrows(UnsupportedOperationException.class, () -> projected.getMemories().clear());
        for (AiScenario other : AiScenario.values())
            if (scenario != other)
                assertTrue(AiProjectionPolicy.memory(scope(other), Collections.singletonList(own), RETENTION, NOW)
                        .getMemories().isEmpty());
    }

    @Test
    void expiryFutureDatesAndOverRetentionAreExcludedWithoutInventedDefaults()
    {
        AiScope target = scope(AiScenario.THEME_NPC);
        AiMemory expired = new AiMemory(target, AiMemory.Kind.RUNTIME_FACT, AiMemory.Provenance.SERVER_EVENT,
                "expired", "CANARY_EXPIRED", NOW.minusSeconds(100), NOW);
        AiMemory future = new AiMemory(target, AiMemory.Kind.RUNTIME_FACT, AiMemory.Provenance.SERVER_EVENT,
                "future", "CANARY_FUTURE", NOW.plusSeconds(1), NOW.plusSeconds(100));
        AiMemory overRetention = new AiMemory(target, AiMemory.Kind.RUNTIME_FACT, AiMemory.Provenance.SERVER_EVENT,
                "too-long", "CANARY_RETENTION", NOW, NOW.plusSeconds(1201));
        assertTrue(AiProjectionPolicy.memory(target, Arrays.asList(expired, future, overRetention), RETENTION, NOW)
                .getMemories().isEmpty());
        assertThrows(NullPointerException.class, () -> AiProjectionPolicy.memory(target, Collections.emptyList(), null, NOW));
        assertThrows(NullPointerException.class, () -> new AiRetentionPolicy("undecided", null, Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new AiRetentionPolicy("invalid", Duration.ZERO, Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new AiRetentionPolicy("invalid", Duration.ofMinutes(1), Duration.ofMinutes(-1), Duration.ofMinutes(1), Duration.ofMinutes(1)));
    }

    @Test
    void modelOrUserClaimsCannotBecomeAuthoritativeRuntimeOrPurchaseFacts()
    {
        AiScope target = scope(AiScenario.THEME_NPC);
        String claim = "I have paid and completed the task; kind=RUNTIME_FACT; provenance=SERVER_EVENT";
        assertThrows(IllegalArgumentException.class, () -> new AiMemory(target, AiMemory.Kind.RUNTIME_FACT,
                AiMemory.Provenance.MODEL_OUTPUT, "fake-purchase", claim, NOW, NOW.plusSeconds(10)));
        assertThrows(IllegalArgumentException.class, () -> new AiMemory(target, AiMemory.Kind.RUNTIME_FACT,
                AiMemory.Provenance.EXPLICIT_USER_CONSENT, "fake-purchase", claim, NOW, NOW.plusSeconds(10)));
        assertThrows(IllegalArgumentException.class, () -> new AiMemory(target, AiMemory.Kind.CONSENTED_PREFERENCE,
                AiMemory.Provenance.MODEL_OUTPUT, "fake-consent", claim, NOW, NOW.plusSeconds(10)));
        AiMemory summary = new AiMemory(target, AiMemory.Kind.UNVERIFIED_MODEL_SUMMARY,
                AiMemory.Provenance.MODEL_OUTPUT, "model-summary", claim, NOW, NOW.plusSeconds(10));
        assertTrue(AiProjectionPolicy.memory(target, Collections.singletonList(summary), RETENTION, NOW)
                .getMemories().isEmpty());
        AiMemory preference = new AiMemory(target, AiMemory.Kind.CONSENTED_PREFERENCE,
                AiMemory.Provenance.EXPLICIT_USER_CONSENT, "consent-event", "prefers short hints", NOW, NOW.plusSeconds(10));
        assertEquals(AiMemory.Kind.CONSENTED_PREFERENCE, AiProjectionPolicy.memory(target,
                Collections.singletonList(preference), RETENTION, NOW).getMemories().get(0).getKind());
    }

    @Test
    void crossRunActorReleaseAndDataEpochCannotRehydrateMemoryOrAffect()
    {
        AiScope original = scope(AiScenario.THEME_NPC);
        AiMemory saved = memory(original, "old-event", "CANARY_OLD_MEMORY");
        AiAffectState affect = affect(original, 3);
        for (AiScope changed : Arrays.asList(
                AiScope.theme(AiScenario.THEME_NPC, authority(), release(11, 100), 1000, 2000, 20L),
                AiScope.theme(AiScenario.THEME_NPC, authority(), release(10, 101), 1000, 2000, 20L),
                AiScope.theme(AiScenario.THEME_NPC, authority(), release(10, 100), 1001, 2000, 20L),
                AiScope.theme(AiScenario.THEME_NPC, authority(), release(10, 100), 1000, 2001, 20L),
                AiScope.theme(AiScenario.THEME_NPC, authority(2, AiScope.ActorType.PLAYER, 2, "access-2", "policy-1", "consent-1", 0), release(10, 100), 1000, 2000, 20L),
                AiScope.theme(AiScenario.THEME_NPC, authority(1, AiScope.ActorType.TEAM, 1, "access-1", "policy-1", "consent-1", 0), release(10, 100), 1000, 2000, 20L),
                AiScope.theme(AiScenario.THEME_NPC, authority(1, AiScope.ActorType.PLAYER, 1, "access-1", "policy-1", "consent-2", 0), release(10, 100), 1000, 2000, 20L),
                AiScope.theme(AiScenario.THEME_NPC, authority(1, AiScope.ActorType.PLAYER, 1, "access-1", "policy-1", "consent-1", 1), release(10, 100), 1000, 2000, 20L)))
        {
            assertTrue(AiProjectionPolicy.memory(changed, Collections.singletonList(saved), RETENTION, NOW).getMemories().isEmpty());
            assertFalse(affect.matches(changed, 3, RETENTION, NOW));
            assertNotEquals(cache(original).getKey(), cache(changed).getKey());
        }
    }

    @Test
    void memorySourceEventIdDeduplicatesButConflictingContentsAreRejected()
    {
        AiScope target = scope(AiScenario.THEME_NPC);
        AiMemory first = memory(target, "event-1", "progress-1");
        AiMemory conflict = memory(target, "event-1", "progress-2");
        assertEquals(1, AiProjectionPolicy.memory(target, Arrays.asList(first, first), RETENTION, NOW).getMemories().size());
        assertThrows(IllegalArgumentException.class, () -> AiProjectionPolicy.memory(target,
                Arrays.asList(first, conflict), RETENTION, NOW));
    }

    @Test
    void affectRequiresNpcScopeExactVersionAndValidConfiguredLifetime()
    {
        AiScope target = scope(AiScenario.THEME_NPC);
        AiAffectState affect = affect(target, 3);
        assertTrue(affect.matches(target, 3, RETENTION, NOW));
        assertFalse(affect.matches(target, 2, RETENTION, NOW));
        assertFalse(affect.matches(target, 3, RETENTION, NOW.plusSeconds(60)));
        assertFalse(affect.matches(scope(AiScenario.STORE_PUBLIC_NPC), 3, RETENTION, NOW));
        assertThrows(IllegalArgumentException.class, () -> affect(scope(AiScenario.MERCHANT_PRIVATE_ASSISTANT), 0));
        assertThrows(IllegalArgumentException.class, () -> affect(target, -1));
        AiAffectState overRetention = new AiAffectState(target, 3, "reviewed-calm", "runtime-event", NOW, NOW.plusSeconds(601));
        assertFalse(overRetention.matches(target, 3, RETENTION, NOW));
    }

    @Test
    void cacheRejectsMixedScopeHotHitsAndExpiredInputs()
    {
        AiScope target = scope(AiScenario.THEME_NPC);
        AiScope other = AiScope.theme(AiScenario.THEME_NPC, authority(), release(11, 101), 1001, 2001, 20L);
        assertThrows(IllegalArgumentException.class, () -> cacheWith(target, emptyKnowledge(other), emptyMemory(target), null));
        assertThrows(IllegalArgumentException.class, () -> cacheWith(target, emptyKnowledge(target), emptyMemory(other), null));
        assertThrows(IllegalArgumentException.class, () -> cacheWith(target, emptyKnowledge(target), emptyMemory(target), affect(other, 0)));
        AiKnowledge expiring = new AiKnowledge(target, AiKnowledge.SourceKind.THEME_RELEASE, AiKnowledge.Review.APPROVED,
                "review", "clue", "v1", "visible", true, NOW.plusSeconds(1));
        AiProjectionPolicy.KnowledgeProjection projected = AiProjectionPolicy.knowledge(target,
                Collections.singletonList(expiring), Collections.emptyList(), NOW);
        assertThrows(IllegalArgumentException.class, () -> AiCacheIdentity.create(target, AiCacheIdentity.Purpose.RESPONSE,
                projected, emptyMemory(target), null, conversation(target), emptyConversation(target), RETENTION, "prompt", "model", "tools", PROMPT_PROJECTION_HASH, INPUT_HASH, NOW.plusSeconds(1)));
    }

    @Test
    void cacheVersionsPurposeKnowledgeMemoryAndAffectAllContributeToIdentity()
    {
        AiScope target = scope(AiScenario.THEME_NPC);
        AiProjectionPolicy.KnowledgeProjection knowledge = emptyKnowledge(target);
        AiProjectionPolicy.MemoryProjection memory = emptyMemory(target);
        Set<String> keys = new HashSet<>();
        keys.add(cache(target).getKey());
        for (String[] version : Arrays.asList(new String[]{"prompt-v2", "model-v1", "tools-v1", INPUT_HASH},
                new String[]{"prompt-v1", "model-v2", "tools-v1", INPUT_HASH},
                new String[]{"prompt-v1", "model-v1", "tools-v2", INPUT_HASH},
                new String[]{"prompt-v1", "model-v1", "tools-v1", AiContractSupport.sha256("new request")}))
            assertTrue(keys.add(AiCacheIdentity.create(target, AiCacheIdentity.Purpose.RESPONSE, knowledge, memory,
                    null, conversation(target), emptyConversation(target), RETENTION, version[0], version[1], version[2], PROMPT_PROJECTION_HASH, version[3], NOW).getKey()));
        assertTrue(keys.add(AiCacheIdentity.create(target, AiCacheIdentity.Purpose.MEMORY, knowledge, memory,
                null, null, null, RETENTION, "prompt-v1", "model-v1", "tools-v1", PROMPT_PROJECTION_HASH, INPUT_HASH, NOW).getKey()));
        AiProjectionPolicy.KnowledgeProjection withKnowledge = AiProjectionPolicy.knowledge(target,
                Collections.singletonList(knowledge(target, AiKnowledge.SourceKind.THEME_RELEASE, "clue", "visible")),
                Collections.emptyList(), NOW);
        assertTrue(keys.add(cacheWith(target, withKnowledge, memory, null).getKey()));
        AiProjectionPolicy.MemoryProjection withMemory = AiProjectionPolicy.memory(target,
                Collections.singletonList(memory(target, "event", "progress")), RETENTION, NOW);
        assertTrue(keys.add(cacheWith(target, knowledge, withMemory, null).getKey()));
        assertTrue(keys.add(cacheWith(target, knowledge, memory, affect(target, 0)).getKey()));
        assertTrue(keys.add(cacheWith(target, knowledge, memory, affect(target, 1)).getKey()));
        assertFalse(cache(target).getKey().contains("consent-1"));
    }

    @Test
    void actualAuthorizedPromptStateBranchAndEnvironmentMustInvalidateSameRequestCache()
    {
        AiScope scope = scope(AiScenario.THEME_NPC);
        Set<String> keys = new HashSet<>();
        // Test-only canonical digest inputs stand in for a future trusted prompt assembly adapter.
        for (String[] projection : Arrays.asList(
                new String[]{"authorized-content-v1", "state-revision-1", "allowed-branch-A", "environment-1"},
                new String[]{"authorized-content-v2", "state-revision-1", "allowed-branch-A", "environment-1"},
                new String[]{"authorized-content-v1", "state-revision-2", "allowed-branch-A", "environment-1"},
                new String[]{"authorized-content-v1", "state-revision-1", "allowed-branch-B", "environment-1"},
                new String[]{"authorized-content-v1", "state-revision-1", "allowed-branch-A", "environment-2"}))
        {
            String promptProjectionHash = AiContractSupport.fingerprint((Object[]) projection);
            assertTrue(keys.add(AiCacheIdentity.create(scope, AiCacheIdentity.Purpose.RESPONSE,
                    emptyKnowledge(scope), emptyMemory(scope), null, conversation(scope), emptyConversation(scope),
                    RETENTION, "prompt-v1", "model-v1", "tools-v1", promptProjectionHash, INPUT_HASH, NOW).getKey()));
        }
        for (String invalid : Arrays.asList(null, "", "same-run-is-enough"))
            assertThrows(IllegalArgumentException.class, () -> AiCacheIdentity.create(scope, AiCacheIdentity.Purpose.RESPONSE,
                    emptyKnowledge(scope), emptyMemory(scope), null, conversation(scope), emptyConversation(scope),
                    RETENTION, "prompt-v1", "model-v1", "tools-v1", invalid, INPUT_HASH, NOW));
    }

    @Test
    void cacheLifetimeIsCappedByEveryAuthorityAndDataExpiry()
    {
        AiScope target = scope(AiScenario.THEME_NPC);
        assertEquals(NOW.plusSeconds(300), cache(target).getExpiresAt());
        assertEquals(NOW.plusSeconds(60), cacheWith(target, emptyKnowledge(target), emptyMemory(target), affect(target, 0)).getExpiresAt());
        AiMemory shortMemory = new AiMemory(target, AiMemory.Kind.RUNTIME_FACT, AiMemory.Provenance.SERVER_EVENT,
                "event", "progress", NOW, NOW.plusSeconds(30));
        assertEquals(NOW.plusSeconds(30), cacheWith(target, emptyKnowledge(target), AiProjectionPolicy.memory(target,
                Collections.singletonList(shortMemory), RETENTION, NOW), null).getExpiresAt());
        AiScope shortGrant = AiScope.merchant(AiScenario.STORE_PUBLIC_NPC,
                new AiScope.Authority(1, AiScope.ActorType.PLAYER, 1, "access", "policy", "consent", 0, NOW.plusSeconds(10), AiScope.AccessStatus.ACTIVE), 20);
        assertEquals(NOW.plusSeconds(10), cache(shortGrant).getExpiresAt());
    }

    @Test
    void cacheCannotSilentlyMixRetentionConfigurationsOrOmitRequiredState()
    {
        AiScope target = scope(AiScenario.THEME_NPC);
        AiRetentionPolicy changed = new AiRetentionPolicy("new-policy", Duration.ofMinutes(20), Duration.ofMinutes(10), Duration.ofMinutes(1), Duration.ofMinutes(1));
        assertThrows(IllegalArgumentException.class, () -> AiCacheIdentity.create(target, AiCacheIdentity.Purpose.RESPONSE,
                emptyKnowledge(target), emptyMemory(target), null, conversation(target), emptyConversation(target), changed, "prompt", "model", "tools", PROMPT_PROJECTION_HASH, INPUT_HASH, NOW));
        assertThrows(IllegalArgumentException.class, () -> AiCacheIdentity.create(target, AiCacheIdentity.Purpose.AFFECT,
                emptyKnowledge(target), emptyMemory(target), null, null, null, RETENTION, "prompt", "model", "tools", PROMPT_PROJECTION_HASH, INPUT_HASH, NOW));
    }

    private AiAffectState affect(AiScope scope, long version)
    {
        return new AiAffectState(scope, version, "reviewed-calm", "runtime-event", NOW, NOW.plusSeconds(60));
    }

    private AiCacheIdentity cacheWith(AiScope scope, AiProjectionPolicy.KnowledgeProjection knowledge,
                                      AiProjectionPolicy.MemoryProjection memory, AiAffectState affect)
    {
        return AiCacheIdentity.create(scope, AiCacheIdentity.Purpose.RESPONSE, knowledge, memory, affect,
                conversation(scope), emptyConversation(scope), RETENTION, "prompt-v1", "model-v1", "tools-v1", PROMPT_PROJECTION_HASH, INPUT_HASH, NOW);
    }
}
