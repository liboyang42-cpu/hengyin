package com.chengyinhub.business.service.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ImConversation;
import com.chengyinhub.business.domain.ImConversationMember;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ImConversationMapper;
import com.chengyinhub.business.mapper.ImConversationMemberMapper;
import com.chengyinhub.business.mapper.ImMessageMapper;
import com.chengyinhub.business.service.impl.ImServiceImpl;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelAccount4Identity;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelAuthorizationGrant;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelCatalogEntry;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelGovernancePhase;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelRevokeReason;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelRole;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelRulesCas;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ConversationKind;

class ChannelGovernanceProjectionTest {

    @Test
    void joinWithoutRulesConsentCannotAuthorizeRw() {
        ChannelGovernanceProjection journey = new ChannelGovernanceProjection(
                new ChannelCatalogEntry(21, 12, ConversationKind.GROUP, new ChannelRulesCas(3, Integer.valueOf(4), 3)),
                new ChannelAccount4Identity(7, 1L));
        assertEquals(ChannelAuthorizationException.Code.RULES_CONSENT_REQUIRED,
                assertThrows(ChannelAuthorizationException.class, journey::submitInviteOrApply).code);
        journey.requireRulesConsent();
        assertEquals(ChannelGovernancePhase.RULES_CONSENT_REQUIRED, journey.phase());
        assertNull(journey.grant());
        assertEquals(ChannelAuthorizationException.Code.ROLE_REQUIRED,
                assertThrows(ChannelAuthorizationException.class, journey::authorizeReadWrite).code);
    }

    @Test
    void applicantCannotBorrowMemberRwAndWrongAccount4CannotConsent() {
        ChannelAccount4Identity identity = new ChannelAccount4Identity(7, 1L);
        ChannelCatalogEntry catalog = new ChannelCatalogEntry(21, 12, ConversationKind.GROUP,
                new ChannelRulesCas(3, Integer.valueOf(4), 3));
        ChannelGovernanceProjection journey = new ChannelGovernanceProjection(catalog, identity);
        journey.consentToPublishedRules(new ChannelRulesAdmission(identity, 3));
        journey.submitInviteOrApply();
        assertEquals(ChannelRole.APPLICANT, journey.role());
        assertEquals(ChannelAuthorizationException.Code.ROLE_REQUIRED,
                assertThrows(ChannelAuthorizationException.class, journey::authorizeReadWrite).code);
        ChannelAccount4Identity other = new ChannelAccount4Identity(9, 1L);
        assertEquals(ChannelAuthorizationException.Code.ACCOUNT4_MISMATCH,
                assertThrows(ChannelAuthorizationException.class,
                        () -> new ChannelRulesAdmission(identity, 3).admit(other, catalog)).code);
        assertEquals(ChannelAuthorizationException.Code.ROLE_REQUIRED,
                assertThrows(ChannelAuthorizationException.class, () -> journey.assignMemberRole(ChannelRole.NONE)).code);
    }

    @Test
    void catalogedGrantDoesNotOccupyThenUniqueAuthorizeIsFirstWrite() {
        ChannelAccount4Identity identity = new ChannelAccount4Identity(7, 1L);
        ChannelGovernanceProjection journey = new ChannelGovernanceProjection(
                new ChannelCatalogEntry(21, 12, ConversationKind.GROUP, new ChannelRulesCas(3, Integer.valueOf(4), 3)),
                identity);
        assertEquals(ChannelGovernancePhase.CATALOGED, journey.phase());
        assertNull(journey.grant(), "cataloged 观察不得占 grant");
        journey.requireRulesConsent();
        assertNull(journey.grant());
        journey.consentToPublishedRules(new ChannelRulesAdmission(identity, 3));
        assertNull(journey.grant());
        journey.submitInviteOrApply();
        assertNull(journey.grant());
        journey.assignMemberRole(ChannelRole.MEMBER);
        assertEquals(ChannelGovernancePhase.MEMBER_ROLE_ASSIGNED, journey.phase());
        assertNull(journey.grant(), "未授权读写不得占 grant");

        ChannelAuthorizationGrant grant = journey.authorizeReadWrite();
        assertNotNull(grant);
        assertEquals(identity, grant.identity);
        assertEquals(ChannelRole.MEMBER, grant.role);
        assertEquals(Integer.valueOf(3), grant.consentedRulesVersion);
        assertEquals(grant.identity, journey.grant().identity);
        assertEquals(ChannelGovernancePhase.AUTHORIZED_READ_WRITE, journey.phase());
    }

    @Test
    void unusedAppealDoesNotOccupyBanThenUniqueBanApplies() {
        ChannelAccount4Identity identity = new ChannelAccount4Identity(7, 1L);
        ChannelGovernanceProjection journey = new ChannelGovernanceProjection(
                new ChannelCatalogEntry(21, 12, ConversationKind.GROUP, new ChannelRulesCas(3, Integer.valueOf(4), 3)),
                identity);
        journey.consentToPublishedRules(new ChannelRulesAdmission(identity, 3));
        journey.submitInviteOrApply();
        journey.assignMemberRole(ChannelRole.MEMBER);
        assertNotNull(journey.authorizeReadWrite());
        assertEquals(ChannelAuthorizationException.Code.REVOKED,
                assertThrows(ChannelAuthorizationException.class, journey::appeal).code);
        assertEquals(ChannelGovernancePhase.AUTHORIZED_READ_WRITE, journey.phase());
        assertNotNull(journey.grant(), "在籍未封禁申诉不得占 ban");
        journey.ban();
        assertEquals(ChannelGovernancePhase.BANNED, journey.phase());
        assertNull(journey.grant());
        journey.appeal();
        assertEquals(ChannelGovernancePhase.APPEALING, journey.phase());
    }

    @Test
    void unusedCatalogedLeaveDoesNotOccupyThenUniqueLeaveApplies() {
        ChannelAccount4Identity identity = new ChannelAccount4Identity(7, 1L);
        ChannelGovernanceProjection journey = new ChannelGovernanceProjection(
                new ChannelCatalogEntry(21, 12, ConversationKind.GROUP, new ChannelRulesCas(3, Integer.valueOf(4), 3)),
                identity);
        assertEquals(ChannelGovernancePhase.CATALOGED, journey.phase());
        assertEquals(ChannelAuthorizationException.Code.ROLE_REQUIRED,
                assertThrows(ChannelAuthorizationException.class, journey::leave).code);
        assertEquals(ChannelGovernancePhase.CATALOGED, journey.phase(), "cataloged leave 观察不得占 LEFT");
        assertNull(journey.grant());
        journey.consentToPublishedRules(new ChannelRulesAdmission(identity, 3));
        journey.submitInviteOrApply();
        journey.assignMemberRole(ChannelRole.MEMBER);
        assertNotNull(journey.authorizeReadWrite());
        journey.leave();
        assertEquals(ChannelGovernancePhase.LEFT, journey.phase());
        assertNull(journey.grant());
    }

    @Test
    void unusedCatalogedInviteDoesNotOccupyApplicantThenUniqueSubmitApplies() {
        ChannelAccount4Identity identity = new ChannelAccount4Identity(7, 1L);
        ChannelGovernanceProjection journey = new ChannelGovernanceProjection(
                new ChannelCatalogEntry(21, 12, ConversationKind.GROUP, new ChannelRulesCas(3, Integer.valueOf(4), 3)),
                identity);
        assertEquals(ChannelRole.NONE, journey.role());
        assertEquals(ChannelAuthorizationException.Code.RULES_CONSENT_REQUIRED,
                assertThrows(ChannelAuthorizationException.class, journey::submitInviteOrApply).code);
        assertEquals(ChannelRole.NONE, journey.role(), "cataloged invite 观察不得占 applicant");
        assertEquals(ChannelGovernancePhase.CATALOGED, journey.phase());
        journey.consentToPublishedRules(new ChannelRulesAdmission(identity, 3));
        assertEquals(ChannelRole.NONE, journey.role());
        journey.submitInviteOrApply();
        assertEquals(ChannelRole.APPLICANT, journey.role());
        assertEquals(ChannelGovernancePhase.INVITE_OR_APPLY, journey.phase());
        assertNull(journey.grant());
    }

    @Test
    void unusedCatalogedAssignDoesNotOccupyMemberThenUniqueAssignApplies() {
        ChannelAccount4Identity identity = new ChannelAccount4Identity(7, 1L);
        ChannelGovernanceProjection journey = new ChannelGovernanceProjection(
                new ChannelCatalogEntry(21, 12, ConversationKind.GROUP, new ChannelRulesCas(3, Integer.valueOf(4), 3)),
                identity);
        assertEquals(ChannelRole.NONE, journey.role());
        assertEquals(ChannelAuthorizationException.Code.ROLE_REQUIRED,
                assertThrows(ChannelAuthorizationException.class, () -> journey.assignMemberRole(ChannelRole.MEMBER))
                        .code);
        assertEquals(ChannelRole.NONE, journey.role(), "cataloged assign 观察不得占 member");
        assertEquals(ChannelGovernancePhase.CATALOGED, journey.phase());
        journey.consentToPublishedRules(new ChannelRulesAdmission(identity, 3));
        journey.submitInviteOrApply();
        assertEquals(ChannelRole.APPLICANT, journey.role());
        journey.assignMemberRole(ChannelRole.MEMBER);
        assertEquals(ChannelRole.MEMBER, journey.role());
        assertEquals(ChannelGovernancePhase.MEMBER_ROLE_ASSIGNED, journey.phase());
        assertNull(journey.grant());
    }

    @Test
    void authorizeFromMembershipRequiresImActiveGroupMemberThenLiveMemberIssuesGrant() {
        ChannelAccount4Identity identity = new ChannelAccount4Identity(7, 1L);
        ChannelCatalogEntry catalog = new ChannelCatalogEntry(21, 12, ConversationKind.GROUP,
                new ChannelRulesCas(3, Integer.valueOf(4), 3));
        ChannelGovernanceProjection missing = memberReady(catalog, identity, null);
        IllegalStateException waiting = assertThrows(IllegalStateException.class,
                () -> missing.authorizeFromMembership(70L));
        assertTrue(waiting.getMessage().contains(ChannelGovernanceProjection.IM_ACTIVE_GROUP_MEMBER));
        assertNull(missing.grant());
        assertEquals(ChannelGovernancePhase.MEMBER_ROLE_ASSIGNED, missing.phase());

        ChannelGovernanceProjection denied = memberReady(catalog, identity, (memberId, conversationId) -> false);
        assertEquals(ChannelAuthorizationException.Code.NOT_MEMBER,
                assertThrows(ChannelAuthorizationException.class, () -> denied.authorizeFromMembership(70L)).code);
        assertNull(denied.grant());

        ChannelGovernanceProjection live = memberReady(catalog, identity,
                (memberId, conversationId) -> memberId == 70L && conversationId == 12L);
        ChannelAuthorizationGrant grant = live.authorizeFromMembership(70L);
        assertNotNull(grant);
        assertEquals(ChannelGovernancePhase.AUTHORIZED_READ_WRITE, live.phase());
        assertEquals(7, grant.identity.accountId);
    }

    @Test
    void authorizeFromMembershipUsesOriginalImActiveGroupMember() {
        ImConversationMapper conversations = mock(ImConversationMapper.class);
        ImConversationMemberMapper members = mock(ImConversationMemberMapper.class);
        ClubMapper clubs = mock(ClubMapper.class);
        ImServiceImpl im = new ImServiceImpl();
        ReflectionTestUtils.setField(im, "conversationMapper", conversations);
        ReflectionTestUtils.setField(im, "memberMapper", members);
        ReflectionTestUtils.setField(im, "clubMapper", clubs);
        ImConversation group = new ImConversation();
        group.setId(12L);
        group.setType(ImConversation.TYPE_GROUP);
        group.setStatus(0);
        group.setBizKey("club_42");
        when(conversations.selectImConversationById(12L)).thenReturn(group);
        ImConversationMember row = new ImConversationMember();
        row.setMemberId(70L);
        row.setDeleted(0);
        when(members.selectByConvAndMember(12L, 70L)).thenReturn(row);
        Club club = new Club();
        club.setId(42L);
        club.setMemberId(70L);
        club.setStatus(1L);
        club.setDelFlag(0);
        when(clubs.selectClubById(42L)).thenReturn(club);

        ChannelAccount4Identity identity = new ChannelAccount4Identity(7, 1L);
        ChannelCatalogEntry catalog = new ChannelCatalogEntry(21, 12, ConversationKind.GROUP,
                new ChannelRulesCas(3, Integer.valueOf(4), 3));
        ChannelGovernanceProjection live = memberReady(catalog, identity,
                new ChannelGovernanceProjection.ImServiceChannelMembership(im));
        ChannelAuthorizationGrant grant = live.authorizeFromMembership(70L);
        assertNotNull(grant);
        assertEquals(ChannelGovernancePhase.AUTHORIZED_READ_WRITE, live.phase());

        ChannelGovernanceProjection denied = memberReady(catalog, identity,
                new ChannelGovernanceProjection.ImServiceChannelMembership(im));
        assertEquals(ChannelAuthorizationException.Code.NOT_MEMBER,
                assertThrows(ChannelAuthorizationException.class, () -> denied.authorizeFromMembership(71L)).code);
        assertNull(denied.grant());
    }

    @Test
    void readMessagesUsesOriginalImListMessagesAndDoesNotMarkRead() {
        ImConversationMapper conversations = mock(ImConversationMapper.class);
        ImConversationMemberMapper members = mock(ImConversationMemberMapper.class);
        ClubMapper clubs = mock(ClubMapper.class);
        ImMessageMapper messageMapper = mock(ImMessageMapper.class);
        ImServiceImpl im = new ImServiceImpl();
        ReflectionTestUtils.setField(im, "conversationMapper", conversations);
        ReflectionTestUtils.setField(im, "memberMapper", members);
        ReflectionTestUtils.setField(im, "clubMapper", clubs);
        ReflectionTestUtils.setField(im, "messageMapper", messageMapper);
        ImConversation group = new ImConversation();
        group.setId(12L);
        group.setType(ImConversation.TYPE_GROUP);
        group.setStatus(0);
        group.setBizKey("club_42");
        when(conversations.selectImConversationById(12L)).thenReturn(group);
        ImConversationMember row = new ImConversationMember();
        row.setMemberId(70L);
        row.setDeleted(0);
        when(members.selectByConvAndMember(12L, 70L)).thenReturn(row);
        Club club = new Club();
        club.setId(42L);
        club.setMemberId(70L);
        club.setStatus(1L);
        club.setDelFlag(0);
        when(clubs.selectClubById(42L)).thenReturn(club);
        ImMessage visible = new ImMessage();
        visible.setId(5L);
        visible.setConversationId(12L);
        visible.setSenderId(0L);
        visible.setMsgType(1);
        visible.setContent("hello");
        when(messageMapper.selectVisiblePage(eq(12L), eq(70L), isNull(), eq(21))).thenReturn(java.util.List.of(visible));

        ChannelAccount4Identity identity = new ChannelAccount4Identity(7, 1L);
        ChannelCatalogEntry catalog = new ChannelCatalogEntry(21, 12, ConversationKind.GROUP,
                new ChannelRulesCas(3, Integer.valueOf(4), 3));
        ChannelGovernanceProjection waiting = new ChannelGovernanceProjection(catalog, identity,
                new ChannelGovernanceProjection.ImServiceChannelMembership(im),
                new ChannelGovernanceProjection.ImServiceChannelMessages(null));
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> waiting.readMessages(70L, null, 20, null));
        assertTrue(missing.getMessage().contains(ChannelGovernanceProjection.IM_LIST_MESSAGES));
        verify(messageMapper, never()).selectVisiblePage(eq(12L), eq(70L), isNull(), eq(21));

        ChannelGovernanceProjection live = new ChannelGovernanceProjection(catalog, identity,
                new ChannelGovernanceProjection.ImServiceChannelMembership(im),
                new ChannelGovernanceProjection.ImServiceChannelMessages(im));
        live.consentToPublishedRules(new ChannelRulesAdmission(identity, 3));
        live.submitInviteOrApply();
        live.assignMemberRole(ChannelRole.MEMBER);
        long generation = live.generation();
        java.util.Map<String, Object> page = live.readMessages(70L, null, 20, null);
        assertNotNull(page);
        java.util.List<?> rows = (java.util.List<?>) page.get("list");
        assertEquals(1, rows.size());
        assertEquals("hello", ((ImMessage) rows.get(0)).getContent());
        assertEquals(12L, ((ImMessage) rows.get(0)).getConversationId());
        assertEquals(5L, page.get("nextCursor"));
        assertEquals(Boolean.FALSE, page.get("hasMore"));
        assertEquals(ChannelGovernancePhase.MEMBER_ROLE_ASSIGNED, live.phase());
        assertEquals(ChannelRole.MEMBER, live.role());
        assertEquals(generation, live.generation());
        verify(messageMapper).selectVisiblePage(eq(12L), eq(70L), isNull(), eq(21));

        ChannelGovernanceProjection denied = new ChannelGovernanceProjection(catalog, identity,
                new ChannelGovernanceProjection.ImServiceChannelMembership(im),
                new ChannelGovernanceProjection.ImServiceChannelMessages(im));
        denied.consentToPublishedRules(new ChannelRulesAdmission(identity, 3));
        denied.submitInviteOrApply();
        denied.assignMemberRole(ChannelRole.MEMBER);
        assertEquals(ChannelAuthorizationException.Code.NOT_MEMBER,
                assertThrows(ChannelAuthorizationException.class, () -> denied.readMessages(71L, null, 20, null)).code);
        verify(messageMapper, never()).selectVisiblePage(eq(12L), eq(71L), isNull(), eq(21));
    }

    @Test
    void readMessagesUsesListMessagesOnlyAfterActiveMembershipAndDoesNotChangePhase() {
        ChannelAccount4Identity identity = new ChannelAccount4Identity(7, 1L);
        ChannelCatalogEntry catalog = new ChannelCatalogEntry(21, 12, ConversationKind.GROUP,
                new ChannelRulesCas(3, Integer.valueOf(4), 3));
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        ChannelGovernanceProjection.ChannelMessageSource messages = (memberId, conversationId, cursorId, size, afterId) -> {
            reads.incrementAndGet();
            java.util.Map<String, Object> page = new java.util.LinkedHashMap<String, Object>();
            page.put("conversationId", Long.valueOf(conversationId));
            page.put("size", Integer.valueOf(size));
            return page;
        };
        ChannelGovernanceProjection missingMessages = new ChannelGovernanceProjection(catalog, identity,
                (memberId, conversationId) -> true, null);
        IllegalStateException waiting = assertThrows(IllegalStateException.class,
                () -> missingMessages.readMessages(70L, null, 20, null));
        assertTrue(waiting.getMessage().contains(ChannelGovernanceProjection.IM_LIST_MESSAGES));
        assertEquals(ChannelGovernancePhase.CATALOGED, missingMessages.phase());
        assertEquals(0, reads.get());

        ChannelGovernanceProjection denied = new ChannelGovernanceProjection(catalog, identity,
                (memberId, conversationId) -> false, messages);
        assertEquals(ChannelAuthorizationException.Code.NOT_MEMBER,
                assertThrows(ChannelAuthorizationException.class, () -> denied.readMessages(70L, null, 20, null)).code);
        assertEquals(0, reads.get());
        assertEquals(ChannelGovernancePhase.CATALOGED, denied.phase());

        ChannelGovernanceProjection live = new ChannelGovernanceProjection(catalog, identity,
                (memberId, conversationId) -> memberId == 70L && conversationId == 12L, messages);
        java.util.Map<String, Object> page = live.readMessages(70L, null, 20, null);
        assertEquals(12L, page.get("conversationId"));
        assertEquals(1, reads.get());
        ChannelGovernanceProjection.ChannelMessageSource otherConversation =
                (memberId, conversationId, cursorId, size, afterId) -> {
                    java.util.Map<String, Object> foreign = new java.util.LinkedHashMap<String, Object>();
                    foreign.put("conversationId", Long.valueOf(99L));
                    return foreign;
                };
        ChannelGovernanceProjection mismatched = new ChannelGovernanceProjection(catalog, identity,
                (memberId, conversationId) -> true, otherConversation);
        assertNull(mismatched.readMessages(70L, null, 20, null));
        ImMessage otherMessage = new ImMessage();
        otherMessage.setConversationId(99L);
        otherMessage.setContent("other-conversation");
        ChannelGovernanceProjection.ChannelMessageSource otherList =
                (memberId, conversationId, cursorId, size, afterId) -> {
                    java.util.Map<String, Object> foreign = new java.util.LinkedHashMap<String, Object>();
                    foreign.put("list", java.util.List.of(otherMessage));
                    return foreign;
                };
        ChannelGovernanceProjection listed = new ChannelGovernanceProjection(catalog, identity,
                (memberId, conversationId) -> true, otherList);
        assertNull(listed.readMessages(70L, null, 20, null));
        assertEquals(ChannelGovernancePhase.CATALOGED, live.phase());
        assertEquals(1L, live.generation());
    }

    @Test
    void readMessagesAfterBanOrLeaveDoesNotCallListMessagesWhenMembershipStaysTrue() {
        ChannelAccount4Identity identity = new ChannelAccount4Identity(7, 1L);
        ChannelCatalogEntry catalog = new ChannelCatalogEntry(21, 12, ConversationKind.GROUP,
                new ChannelRulesCas(3, Integer.valueOf(4), 3));
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        ChannelGovernanceProjection.ChannelMessageSource messages = (memberId, conversationId, cursorId, size, afterId) -> {
            reads.incrementAndGet();
            return java.util.Collections.<String, Object>emptyMap();
        };
        ChannelGovernanceProjection.ChannelMembershipSource stillMember = (memberId, conversationId) -> true;

        ChannelGovernanceProjection banned = new ChannelGovernanceProjection(catalog, identity, stillMember, messages);
        banned.consentToPublishedRules(new ChannelRulesAdmission(identity, 3));
        banned.submitInviteOrApply();
        banned.assignMemberRole(ChannelRole.MEMBER);
        banned.ban();
        assertEquals(ChannelAuthorizationException.Code.REVOKED,
                assertThrows(ChannelAuthorizationException.class, () -> banned.readMessages(70L, null, 20, null)).code);
        assertEquals(0, reads.get());
        assertEquals(ChannelGovernancePhase.BANNED, banned.phase());
        assertEquals(ChannelRole.NONE, banned.role());
        assertEquals(ChannelRevokeReason.BAN, banned.revokeReason());

        ChannelGovernanceProjection left = new ChannelGovernanceProjection(catalog, identity, stillMember, messages);
        left.consentToPublishedRules(new ChannelRulesAdmission(identity, 3));
        left.submitInviteOrApply();
        left.assignMemberRole(ChannelRole.MEMBER);
        left.leave();
        assertEquals(ChannelAuthorizationException.Code.REVOKED,
                assertThrows(ChannelAuthorizationException.class, () -> left.readMessages(70L, null, 20, null)).code);
        assertEquals(0, reads.get());
        assertEquals(ChannelGovernancePhase.LEFT, left.phase());
        assertEquals(ChannelRole.NONE, left.role());
        assertEquals(ChannelRevokeReason.LEAVE, left.revokeReason());
    }

    private static ChannelGovernanceProjection memberReady(ChannelCatalogEntry catalog,
            ChannelAccount4Identity identity, ChannelGovernanceProjection.ChannelMembershipSource membership) {
        ChannelGovernanceProjection journey = new ChannelGovernanceProjection(catalog, identity, membership);
        journey.consentToPublishedRules(new ChannelRulesAdmission(identity, 3));
        journey.submitInviteOrApply();
        journey.assignMemberRole(ChannelRole.MEMBER);
        return journey;
    }
}
