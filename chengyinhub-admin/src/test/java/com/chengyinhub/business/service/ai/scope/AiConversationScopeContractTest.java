package com.chengyinhub.business.service.ai.scope;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.chengyinhub.business.service.ai.scope.AiScopeFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class AiConversationScopeContractTest
{
    @ParameterizedTest
    @EnumSource(AiConversationData.Kind.class)
    void concurrentConversationsWithSameGrantNeverExchangeTransientContext(AiConversationData.Kind kind)
    {
        AiScope stable = scope(AiScenario.THEME_NPC);
        AiConversationScope a = new AiConversationScope(stable, "conversation-A", 0);
        AiConversationScope b = new AiConversationScope(stable, "conversation-B", 0);
        AiConversationData secret = data(a, kind, "request-1", "CANARY_A");
        assertEquals(1, AiProjectionPolicy.conversation(a, Collections.singletonList(secret), RETENTION, NOW).getItems().size());
        assertTrue(AiProjectionPolicy.conversation(b, Collections.singletonList(secret), RETENTION, NOW).getItems().isEmpty());
        assertTrue(a.getScope().sameScope(b.getScope()));
        assertFalse(a.sameConversation(b));
    }

    @Test
    void generationResetInvalidatesTranscriptInflightAndReplayWithoutErasingMemory()
    {
        AiScope stable = scope(AiScenario.THEME_NPC);
        AiConversationScope old = new AiConversationScope(stable, "conversation-A", 0);
        AiConversationScope reset = new AiConversationScope(stable, "conversation-A", 1);
        for (AiConversationData.Kind kind : AiConversationData.Kind.values())
            assertTrue(AiProjectionPolicy.conversation(reset, Collections.singletonList(data(old, kind, "request-1", "old")),
                    RETENTION, NOW).getItems().isEmpty());
        AiMemory saved = memory(stable, "runtime-event", "remembered approved progress");
        assertEquals(1, AiProjectionPolicy.memory(reset.getScope(), Collections.singletonList(saved), RETENTION, NOW).getMemories().size());
        assertNotEquals(sessionCache(old, AiCacheIdentity.Purpose.RESPONSE).getKey(),
                sessionCache(reset, AiCacheIdentity.Purpose.RESPONSE).getKey());
    }

    @Test
    void reconnectAndFreshGrantPreserveApprovedRunMemoryAndAffectButRequireCurrentAccess()
    {
        AiScope old = themeWithGrant("old-access", AiScope.AccessStatus.ACTIVE, NOW.plusSeconds(10), 0, "consent-1");
        AiScope renewed = themeWithGrant("new-access", AiScope.AccessStatus.ACTIVE, NOW.plusSeconds(3600), 0, "consent-1");
        AiMemory saved = memory(old, "runtime-event", "approved run progress");
        AiAffectState affect = new AiAffectState(old, 1, "reviewed-calm", "runtime-event", NOW, NOW.plusSeconds(60));
        assertTrue(old.samePersistentScope(renewed));
        assertFalse(old.sameScope(renewed));
        assertThrows(IllegalArgumentException.class, () -> AiProjectionPolicy.memory(old,
                Collections.singletonList(saved), RETENTION, NOW.plusSeconds(20)));
        assertEquals(1, AiProjectionPolicy.memory(renewed, Collections.singletonList(saved), RETENTION, NOW.plusSeconds(20))
                .getMemories().size());
        assertTrue(affect.matches(renewed, 1, RETENTION, NOW.plusSeconds(20)));
        AiConversationScope before = new AiConversationScope(old, "same-server-conversation", 2);
        AiConversationScope after = new AiConversationScope(renewed, "same-server-conversation", 2);
        assertTrue(before.sameConversation(after));
        assertEquals(1, AiProjectionPolicy.conversation(after,
                Collections.singletonList(data(before, AiConversationData.Kind.TRANSCRIPT, "history-1", "same conversation")),
                RETENTION, NOW.plusSeconds(20)).getItems().size());
    }

    @Test
    void samePersistentIdentityDoesNotAuthorizeRevokedExpiredOrForgottenData()
    {
        AiScope active = scope(AiScenario.THEME_NPC);
        AiMemory saved = memory(active, "event", "CANARY_RETAINED");
        AiAffectState affect = new AiAffectState(active, 1, "reviewed-calm", "event", NOW, NOW.plusSeconds(60));
        for (AiScope denied : Arrays.asList(
                themeWithGrant("access-1", AiScope.AccessStatus.REVOKED, NOW.plusSeconds(3600), 0, "consent-1"),
                themeWithGrant("access-1", AiScope.AccessStatus.ACTIVE, NOW, 0, "consent-1")))
        {
            assertTrue(active.samePersistentScope(denied));
            assertThrows(IllegalArgumentException.class, () -> AiProjectionPolicy.memory(denied,
                    Collections.singletonList(saved), RETENTION, NOW));
            assertThrows(IllegalArgumentException.class, () -> AiProjectionPolicy.conversation(conversation(denied),
                    Collections.emptyList(), RETENTION, NOW));
            assertThrows(IllegalArgumentException.class, () -> cache(denied));
            assertFalse(affect.matches(denied, 1, RETENTION, NOW));
        }
        for (AiScope forgotten : Arrays.asList(
                themeWithGrant("access-1", AiScope.AccessStatus.ACTIVE, NOW.plusSeconds(3600), 1, "consent-1"),
                themeWithGrant("access-1", AiScope.AccessStatus.ACTIVE, NOW.plusSeconds(3600), 0, "consent-withdrawn-v2")))
        {
            assertFalse(active.samePersistentScope(forgotten));
            assertTrue(AiProjectionPolicy.memory(forgotten, Collections.singletonList(saved), RETENTION, NOW).getMemories().isEmpty());
            assertTrue(AiProjectionPolicy.conversation(conversation(forgotten),
                    Collections.singletonList(data(conversation(active), AiConversationData.Kind.TRANSCRIPT, "history", "old")),
                    RETENTION, NOW).getItems().isEmpty());
            assertFalse(affect.matches(forgotten, 1, RETENTION, NOW));
        }
    }

    @ParameterizedTest
    @EnumSource(value = AiCacheIdentity.Purpose.class, names = {"RESPONSE", "TRANSCRIPT", "IN_FLIGHT_RESULT", "REPLAY"})
    void everySessionCacheRequiresConversationAndGeneration(AiCacheIdentity.Purpose purpose)
    {
        AiScope scope = scope(AiScenario.STORE_PUBLIC_NPC);
        AiConversationScope a = new AiConversationScope(scope, "a", 0);
        AiConversationScope b = new AiConversationScope(scope, "b", 0);
        AiConversationScope reset = new AiConversationScope(scope, "a", 1);
        assertNotEquals(sessionCache(a, purpose).getKey(), sessionCache(b, purpose).getKey());
        assertNotEquals(sessionCache(a, purpose).getKey(), sessionCache(reset, purpose).getKey());
        assertThrows(IllegalArgumentException.class, () -> AiCacheIdentity.create(scope, purpose,
                emptyKnowledge(scope), emptyMemory(scope), null, null, null, RETENTION,
                "prompt", "model", "tools", PROMPT_PROJECTION_HASH, INPUT_HASH, NOW));
        AiProjectionPolicy.ConversationProjection other = AiProjectionPolicy.conversation(b, Collections.emptyList(), RETENTION, NOW);
        assertThrows(IllegalArgumentException.class, () -> AiCacheIdentity.create(scope, purpose,
                emptyKnowledge(scope), emptyMemory(scope), null, a, other, RETENTION,
                "prompt", "model", "tools", PROMPT_PROJECTION_HASH, INPUT_HASH, NOW));
    }

    @Test
    void persistentCacheCannotSmuggleTransientHistoryAndContextChangesResponseIdentity()
    {
        AiScope scope = scope(AiScenario.THEME_NPC);
        AiConversationScope conversation = conversation(scope);
        AiProjectionPolicy.ConversationProjection history = AiProjectionPolicy.conversation(conversation,
                Collections.singletonList(data(conversation, AiConversationData.Kind.TRANSCRIPT, "history", "CANARY_TRANSCRIPT")), RETENTION, NOW);
        assertThrows(IllegalArgumentException.class, () -> AiCacheIdentity.create(scope, AiCacheIdentity.Purpose.MEMORY,
                emptyKnowledge(scope), emptyMemory(scope), null, conversation, history, RETENTION,
                "prompt", "model", "tools", PROMPT_PROJECTION_HASH, INPUT_HASH, NOW));
        AiCacheIdentity withHistory = AiCacheIdentity.create(scope, AiCacheIdentity.Purpose.RESPONSE,
                emptyKnowledge(scope), emptyMemory(scope), null, conversation, history, RETENTION,
                "prompt", "model", "tools", PROMPT_PROJECTION_HASH, INPUT_HASH, NOW);
        assertNotEquals(sessionCache(conversation, AiCacheIdentity.Purpose.RESPONSE).getKey(), withHistory.getKey());
        assertEquals(NOW.plusSeconds(60), withHistory.getExpiresAt());
    }

    @Test
    void transientContextEnforcesRetentionExpiryImmutabilityAndConflictingIdentity()
    {
        AiConversationScope conversation = conversation(scope(AiScenario.THEME_NPC));
        AiConversationData first = data(conversation, AiConversationData.Kind.REPLAY, "request", "first");
        AiConversationData conflict = data(conversation, AiConversationData.Kind.REPLAY, "request", "second");
        assertEquals(1, AiProjectionPolicy.conversation(conversation, Arrays.asList(first, first), RETENTION, NOW).getItems().size());
        assertThrows(IllegalArgumentException.class, () -> AiProjectionPolicy.conversation(conversation,
                Arrays.asList(first, conflict), RETENTION, NOW));
        assertThrows(UnsupportedOperationException.class, () -> AiProjectionPolicy.conversation(conversation,
                Collections.singletonList(first), RETENTION, NOW).getItems().clear());
        AiConversationData overRetention = new AiConversationData(conversation, AiConversationData.Kind.REPLAY,
                "long", "old", NOW, NOW.plusSeconds(301));
        AiConversationData future = new AiConversationData(conversation, AiConversationData.Kind.REPLAY,
                "future", "later", NOW.plusSeconds(1), NOW.plusSeconds(60));
        assertTrue(AiProjectionPolicy.conversation(conversation, Arrays.asList(overRetention, future), RETENTION, NOW).getItems().isEmpty());
        assertTrue(AiProjectionPolicy.conversation(conversation, Collections.singletonList(first), RETENTION, NOW.plusSeconds(60)).getItems().isEmpty());
    }

    @Test
    void malformedUnicodeCannotEnterConversationIdentityOrPayload()
    {
        AiScope scope = scope(AiScenario.THEME_NPC);
        assertThrows(IllegalArgumentException.class, () -> new AiConversationScope(scope, "\uD800", 0));
        assertThrows(IllegalArgumentException.class, () -> new AiConversationScope(scope, "valid", -1));
        assertThrows(IllegalArgumentException.class, () -> data(conversation(scope), AiConversationData.Kind.TRANSCRIPT, "history", "\uDC00"));
    }

    private AiScope themeWithGrant(String grant, AiScope.AccessStatus status, java.time.Instant validUntil,
                                   long epoch, String consent)
    {
        return AiScope.theme(AiScenario.THEME_NPC, new AiScope.Authority(1, AiScope.ActorType.PLAYER, 1,
                grant, "policy-1", consent, epoch, validUntil, status), release(10, 100), 1000, 2000, 20L);
    }

    private AiConversationData data(AiConversationScope conversation, AiConversationData.Kind kind, String key, String text)
    {
        return new AiConversationData(conversation, kind, key, text, NOW, NOW.plusSeconds(60));
    }

    private AiCacheIdentity sessionCache(AiConversationScope conversation, AiCacheIdentity.Purpose purpose)
    {
        AiScope scope = conversation.getScope();
        return AiCacheIdentity.create(scope, purpose, emptyKnowledge(scope), emptyMemory(scope), null,
                conversation, AiProjectionPolicy.conversation(conversation, Collections.emptyList(), RETENTION, NOW),
                RETENTION, "prompt", "model", "tools", PROMPT_PROJECTION_HASH, INPUT_HASH, NOW);
    }
}
