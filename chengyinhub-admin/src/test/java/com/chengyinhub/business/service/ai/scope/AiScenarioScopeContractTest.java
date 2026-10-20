package com.chengyinhub.business.service.ai.scope;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.chengyinhub.business.service.ai.scope.AiScopeFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class AiScenarioScopeContractTest
{
    @ParameterizedTest
    @EnumSource(AiScenario.class)
    void scenarioScopesAreDistinctAndHaveOnlyTheirOwnShape(AiScenario scenario)
    {
        AiScope scope = scope(scenario);
        assertEquals(scenario, scope.getScenario());
        for (AiScenario other : AiScenario.values())
            assertEquals(scenario == other, scope.sameScope(scope(other)));
        if (scenario.isTheme())
        {
            assertEquals(10, scope.getRelease().getThemeId());
            assertEquals(100L, scope.getRelease().getReleaseId());
            assertEquals(1000L, scope.getRunId());
            assertEquals(2000L, scope.getBindingId());
        }
        else
        {
            assertNull(scope.getRelease());
            assertNull(scope.getRunId());
            assertNull(scope.getBindingId());
        }
    }

    @Test
    void incompleteManifestAndConfigVersionCannotPretendToBeAssignedRelease()
    {
        AiReleaseReference draft = new AiReleaseReference(10, null, HASH, 1, AiReleaseReference.Readiness.INCOMPLETE);
        assertThrows(IllegalArgumentException.class, () -> AiScope.theme(AiScenario.THEME_NPC,
                authority(), draft, 1000, 2000, 20L));
        AiReleaseReference assignedButIncomplete = new AiReleaseReference(10, 100L, HASH, 1,
                AiReleaseReference.Readiness.INCOMPLETE);
        assertThrows(IllegalArgumentException.class, assignedButIncomplete::requireFrozenReviewed);
        assertThrows(IllegalArgumentException.class, () -> new AiReleaseReference(10, null, HASH, 1,
                AiReleaseReference.Readiness.FROZEN_REVIEWED));
        assertThrows(IllegalArgumentException.class, () -> new AiReleaseReference(10, 100L, HASH, 2,
                AiReleaseReference.Readiness.FROZEN_REVIEWED));
        assertThrows(IllegalArgumentException.class, () -> new AiReleaseReference(10, 100L, "claim-approved", 1,
                AiReleaseReference.Readiness.FROZEN_REVIEWED));
    }

    @Test
    void malformedOrConflatedStoreThemeScopesAreRejected()
    {
        assertThrows(IllegalArgumentException.class, () -> AiScope.merchant(AiScenario.THEME_NPC, authority(), 20));
        assertThrows(IllegalArgumentException.class, () -> AiScope.assistant(AiScenario.STORE_PUBLIC_NPC, authority(), 20));
        assertThrows(IllegalArgumentException.class, () -> AiScope.theme(AiScenario.STORE_PUBLIC_NPC,
                authority(), release(20, 20), 20, 20, 20L));
        assertThrows(IllegalArgumentException.class, () -> AiScope.theme(AiScenario.THEME_NPC,
                authority(), release(10, 100), 0, 2000, 20L));
        assertThrows(IllegalArgumentException.class, () -> AiScope.theme(AiScenario.THEME_NPC,
                authority(), release(10, 100), 1000, -2000, 20L));
        assertThrows(IllegalArgumentException.class, () -> AiScope.merchant(AiScenario.STORE_PUBLIC_NPC, authority(), -20));
    }

    @Test
    void allIsolationDimensionsChangeCacheAndScopeIdentity()
    {
        AiScope base = scope(AiScenario.THEME_NPC);
        Set<String> identities = new HashSet<>();
        identities.add(cache(base).getKey());
        for (AiScope variant : Arrays.asList(
                AiScope.theme(AiScenario.THEME_DIRECTOR, authority(), release(10, 100), 1000, 2000, 20L),
                AiScope.theme(AiScenario.THEME_NPC, authority(), release(11, 100), 1000, 2000, 20L),
                AiScope.theme(AiScenario.THEME_NPC, authority(), release(10, 101), 1000, 2000, 20L),
                AiScope.theme(AiScenario.THEME_NPC, authority(), release(10, 100), 1001, 2000, 20L),
                AiScope.theme(AiScenario.THEME_NPC, authority(), release(10, 100), 1000, 2001, 20L),
                AiScope.theme(AiScenario.THEME_NPC, authority(), release(10, 100), 1000, 2000, 21L),
                AiScope.theme(AiScenario.THEME_NPC, authority(), release(10, 100), 1000, 2000, null),
                AiScope.theme(AiScenario.THEME_NPC, authority(2, AiScope.ActorType.PLAYER, 1, "access-1", "policy-1", "consent-1", 0), release(10, 100), 1000, 2000, 20L),
                AiScope.theme(AiScenario.THEME_NPC, authority(1, AiScope.ActorType.TEAM, 1, "access-1", "policy-1", "consent-1", 0), release(10, 100), 1000, 2000, 20L),
                AiScope.theme(AiScenario.THEME_NPC, authority(1, AiScope.ActorType.PLAYER, 2, "access-1", "policy-1", "consent-1", 0), release(10, 100), 1000, 2000, 20L),
                AiScope.theme(AiScenario.THEME_NPC, authority(1, AiScope.ActorType.PLAYER, 1, "access-2", "policy-1", "consent-1", 0), release(10, 100), 1000, 2000, 20L),
                AiScope.theme(AiScenario.THEME_NPC, authority(1, AiScope.ActorType.PLAYER, 1, "access-1", "policy-2", "consent-1", 0), release(10, 100), 1000, 2000, 20L),
                AiScope.theme(AiScenario.THEME_NPC, authority(1, AiScope.ActorType.PLAYER, 1, "access-1", "policy-1", "consent-2", 0), release(10, 100), 1000, 2000, 20L),
                AiScope.theme(AiScenario.THEME_NPC, authority(1, AiScope.ActorType.PLAYER, 1, "access-1", "policy-1", "consent-1", 1), release(10, 100), 1000, 2000, 20L),
                AiScope.theme(AiScenario.THEME_NPC, authority(), new AiReleaseReference(10, 100L,
                        AiContractSupport.sha256("different release bytes"), 1, AiReleaseReference.Readiness.FROZEN_REVIEWED), 1000, 2000, 20L)))
        {
            assertFalse(base.sameScope(variant));
            assertTrue(identities.add(cache(variant).getKey()), "cache dimension collision");
        }
    }

    @Test
    void lengthPrefixPreventsPolicyConsentDelimiterCollision()
    {
        AiScope a = AiScope.merchant(AiScenario.STORE_PUBLIC_NPC,
                authority(1, AiScope.ActorType.PLAYER, 1, "access-1", "a:b", "c", 0), 20);
        AiScope b = AiScope.merchant(AiScenario.STORE_PUBLIC_NPC,
                authority(1, AiScope.ActorType.PLAYER, 1, "access-1", "a", "b:c", 0), 20);
        assertNotEquals(cache(a).getKey(), cache(b).getKey());
        assertEquals(cache(a).getKey(), cache(a).getKey());
    }

    @Test
    void malformedUnicodeCannotAliasValidScopeOrContentHash()
    {
        for (String invalid : Arrays.asList("\uD800", "\uDC00", "x\uD800y", "\uD800\uD800"))
        {
            assertThrows(IllegalArgumentException.class, () -> AiContractSupport.sha256(invalid));
            assertThrows(IllegalArgumentException.class, () -> AiContractSupport.fingerprint(invalid));
            assertThrows(IllegalArgumentException.class, () -> AiScope.merchant(AiScenario.STORE_PUBLIC_NPC,
                    authority(1, AiScope.ActorType.PLAYER, 1, invalid, "policy", "consent", 0), 20));
            assertThrows(IllegalArgumentException.class, () -> knowledge(scope(AiScenario.STORE_PUBLIC_NPC),
                    AiKnowledge.SourceKind.STORE_PUBLIC_FACT, "source", invalid));
        }
        assertEquals(AiContractSupport.sha256("?"), AiContractSupport.sha256("?"));
        assertNotEquals(AiContractSupport.sha256("?"), AiContractSupport.sha256("\uD83D\uDE00"));
        assertEquals(AiContractSupport.fingerprint("\uD83D\uDE00"), AiContractSupport.fingerprint("\uD83D\uDE00"));
    }

    @Test
    void expiredGrantIsRejectedBeforeProjectionOrOptionalGeneration()
    {
        AiScope expired = AiScope.merchant(AiScenario.STORE_PUBLIC_NPC,
                new AiScope.Authority(1, AiScope.ActorType.PLAYER, 1, "access", "policy", "consent", 0, NOW, AiScope.AccessStatus.ACTIVE), 20);
        assertThrows(IllegalArgumentException.class, () -> AiProjectionPolicy.knowledge(expired,
                Collections.emptyList(), Collections.emptyList(), NOW));
        assertThrows(IllegalArgumentException.class, () -> AiProjectionPolicy.memory(expired,
                Collections.emptyList(), RETENTION, NOW));
    }

    @Test
    void aiOffRequiresNoNpcKnowledgeScopeOrProviderAndMainlineIsAlwaysIndependent()
    {
        ThemeAiPlan off = ThemeAiPlan.off("reviewed-fixed-mainline-v1");
        assertEquals(ThemeAiPlan.Mode.OFF, off.getMode());
        assertNull(off.getScope());
        assertFalse(off.isModelCallAllowed());
        assertFalse(off.isModelRequiredForProgress());
        for (AiScenario scenario : Arrays.asList(AiScenario.THEME_NPC, AiScenario.THEME_DIRECTOR))
        {
            ThemeAiPlan optional = ThemeAiPlan.optional(scope(scenario), "fixed-mainline-v1", NOW);
            assertTrue(optional.isModelCallAllowed());
            assertFalse(optional.isModelRequiredForProgress());
        }
        assertThrows(IllegalArgumentException.class, () -> ThemeAiPlan.off(" "));
        assertThrows(IllegalArgumentException.class, () -> ThemeAiPlan.optional(scope(AiScenario.STORE_PUBLIC_NPC), "fixed", NOW));
    }
}
