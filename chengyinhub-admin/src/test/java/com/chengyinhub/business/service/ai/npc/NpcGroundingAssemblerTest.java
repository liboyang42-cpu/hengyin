package com.chengyinhub.business.service.ai.npc;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.ai.scope.*;
import com.chengyinhub.business.service.ai.npc.ScopedNpcRepository.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NpcGroundingAssemblerTest
{
    final Instant now = Instant.parse("2026-10-04T09:00:00Z");
    final String hash = String.join("", Collections.nCopies(64, "a"));
    final AiScope.Authority authority = new AiScope.Authority(7, AiScope.ActorType.PLAYER, 7,
            "decision", "policy", "consent", 0, now.plusSeconds(300), AiScope.AccessStatus.ACTIVE);
    final AiScope publicScope = AiScope.merchant(AiScenario.STORE_PUBLIC_NPC, authority, 10);
    ScopedNpcRepository repository;
    MmsMerchantMapper merchants;
    NpcProfileMapper profiles;
    MmsMerchant merchant;
    NpcProfile profile;
    NpcGroundingAssembler assembler;
    AiKnowledge.SourceKind forcedKind;
    AiKnowledge.Review review;
    Instant deadline;
    boolean unlocked, foreign, duplicate;

    @BeforeEach void setup()
    {
        repository = mock(ScopedNpcRepository.class);
        merchants = mock(MmsMerchantMapper.class); profiles = mock(NpcProfileMapper.class);
        merchant = new MmsMerchant(); merchant.setId(10L); merchant.setMemberId(900L);
        merchant.setStatus(1L); merchant.setAccountStatus(1L); merchant.setDelFlag(0);
        profile = new NpcProfile(); profile.setId(20L); profile.setScopeType(2); profile.setScopeId(10L);
        profile.setEnabled(1); profile.setAuditStatus(1); profile.setDelFlag("0");
        profile.setKnowledge("PRIVATE_PROFILE_CANARY");
        when(merchants.selectNpcScopeOwnerById(10L)).thenReturn(merchant);
        when(profiles.selectMerchantNpcScopeIdentity(20L)).thenReturn(profile);
        assembler = new NpcGroundingAssembler(repository, new MerchantNpcDirectory(merchants, profiles));
        review = AiKnowledge.Review.APPROVED; deadline = now.plusSeconds(60); unlocked = true;
        when(repository.retrieve(any(), any())).thenAnswer(call -> {
            Query q = call.getArgument(0);
            AiScope scope = foreign ? AiScope.merchant(AiScenario.STORE_PUBLIC_NPC, authority, 999) : q.scope;
            String id = q.kind == AiKnowledge.SourceKind.STORE_PUBLIC_FACT ? "menu" : "hint";
            AiKnowledge source = new AiKnowledge(scope, forcedKind == null ? q.kind : forcedKind, review,
                    "review-1", id, "revision-3", "SYNTHETIC_APPROVED_FACT", unlocked, deadline);
            KnowledgeRow row = new KnowledgeRow(source, q.sourceSetVersion, q.publicationReference,
                    q.packId, q.packHash, q.approvingOwnerMemberId);
            return duplicate ? Arrays.asList(row, row) : Collections.singletonList(row);
        });
    }

    Access access(Theme theme)
    {
        return new Access(7, 7, 10, 900, 20, 0, 0, State.ACTIVE, "decision", "snapshot", "policy",
                "consent", "public-set", "model", "conversation", "publication", now.plusSeconds(300),
                new AiRetentionPolicy("retention", Duration.ofSeconds(60), Duration.ofSeconds(60),
                        Duration.ofSeconds(60), Duration.ofSeconds(60)), Collections.singleton("menu"), theme);
    }

    NpcGroundingAssembler.Context assemble() { return assembler.assemble(access(null), publicScope, publicScope, now); }

    @Test void realDirectoryReadsIdentityDaosAndCarriesImmutableCitationProvenance()
    {
        NpcGroundingAssembler.Context result = assemble();
        verify(merchants).selectNpcScopeOwnerById(10L); verify(profiles).selectMerchantNpcScopeIdentity(20L);
        verifyNoMoreInteractions(merchants, profiles);
        Map<String, String> fact = result.getFacts().get(0);
        assertEquals("menu", fact.get("sourceId")); assertEquals("revision-3", fact.get("revision"));
        assertEquals("STORE_PUBLIC_FACT", fact.get("sourceKind"));
        assertEquals(deadline.toString(), fact.get("validUntil")); assertEquals(64, fact.get("contentHash").length());
        assertFalse(result.getFacts().toString().contains("PRIVATE_PROFILE_CANARY"));
        assertEquals(deadline, result.getProjection().getValidUntil());
        assertThrows(UnsupportedOperationException.class, () -> fact.put("data", "changed"));
        assertThrows(UnsupportedOperationException.class, () -> result.getSourceIds().add("other"));
    }

    @Test void crossMerchantRowsFailClosed() { foreign = true; assertThrows(IllegalArgumentException.class, this::assemble); }
    @Test void duplicateCitationIdentityFailsClosed() { duplicate = true; assertThrows(IllegalArgumentException.class, this::assemble); }
    @Test void expiredPromotionFailsAtExactDeadline() { deadline = now; assertThrows(IllegalArgumentException.class, this::assemble); }
    @Test void lockedFactFailsClosed() { unlocked = false; assertThrows(IllegalArgumentException.class, this::assemble); }
    @Test void withdrawnApprovalIsReadAgain() { assemble(); review = AiKnowledge.Review.REJECTED; assertThrows(IllegalArgumentException.class, this::assemble); }
    @Test void revokedProfileStopsBeforeKnowledgeRead()
    {
        profile.setAuditStatus(2); assertThrows(IllegalArgumentException.class, this::assemble); verifyNoInteractions(repository);
    }
    @Test void changedOwnerStopsBeforeKnowledgeRead()
    {
        merchant.setMemberId(901L); assertThrows(IllegalArgumentException.class, this::assemble); verifyNoInteractions(repository);
    }
    @Test void privateKindsCannotBecomePublicEvenWhenApproved()
    {
        for (AiKnowledge.SourceKind kind : AiKnowledge.SourceKind.values())
        {
            if (kind == AiKnowledge.SourceKind.STORE_PUBLIC_FACT) continue;
            forcedKind = kind; assertThrows(IllegalArgumentException.class, this::assemble, kind.name());
        }
    }
    @Test void themeCluesUseSeparateReleaseQueryAndNeverAppearInPublicContext()
    {
        Theme theme = new Theme(11, 12, 13, 7, 14, 10, 20, 12, 11, 12, 11, 800,
                hash, "theme-publication", "review", "eligibility", "pack", hash, hash,
                "theme-set", "run-state", State.ACTIVE, AiReleaseReference.Readiness.FROZEN_REVIEWED, 1,
                Collections.singleton("hint"));
        AiScope scope = AiScope.theme(AiScenario.THEME_NPC, authority,
                new AiReleaseReference(11, 12L, hash, 1, AiReleaseReference.Readiness.FROZEN_REVIEWED), 13, 14, 10L);
        NpcGroundingAssembler.Context context = assembler.assemble(access(theme), scope, publicScope, now);
        assertEquals(Arrays.asList("hint", "menu"), context.getSourceIds());
        assertEquals("THEME_RELEASE", context.getFacts().get(0).get("sourceKind"));
        verify(repository).retrieve(argThat(q -> q.kind == AiKnowledge.SourceKind.THEME_RELEASE
                && "pack".equals(q.packId) && hash.equals(q.packHash)
                && q.allowedSourceIds.equals(Collections.singleton("hint")) && q.scope.sameScope(scope)), eq(now));
        assertEquals(Collections.singletonList("menu"), assemble().getSourceIds());
    }
}
