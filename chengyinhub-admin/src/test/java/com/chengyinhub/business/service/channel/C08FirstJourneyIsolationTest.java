package com.chengyinhub.business.service.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelAccount4Identity;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelAuthorizationGrant;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelCapability;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelCatalogEntry;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelGovernancePhase;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelImMutation;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelIsolatedAction;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelIsolatedAdmission;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelIsolatedRequest;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelPageLease;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelRole;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelRulesCas;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ConversationKind;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.FriendOnlyAction;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.FriendPermissionGrant;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.GroupPollView;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ProgressPrivacySurface;

/**
 * C08 首个可跑旅程（隔离）：规则同意 → 成员读写授权 → 频道授权不能当好友 → 撤权后迟到请求隔离；
 * 历史投票只读保留。APPLIED 投影不是原授权证明。
 */
class C08FirstJourneyIsolationTest {

    private ChannelAccount4Identity identity() {
        return new ChannelAccount4Identity(7, 1L);
    }

    private ChannelRulesCas rules() {
        return new ChannelRulesCas(3, Integer.valueOf(4), 3);
    }

    private ChannelCatalogEntry catalog() {
        return new ChannelCatalogEntry(21, 12, ConversationKind.GROUP, rules());
    }

    @Test
    void testFirstJourneyJoinAfterConsentAuthorizedRWRevokeLateRequestAndFriendDenied() {
        ChannelGovernanceProjection journey = new ChannelGovernanceProjection(catalog(), identity());
        journey.requireRulesConsent();
        journey.consentToPublishedRules(new ChannelRulesAdmission(identity(), 3));
        assertEquals(ChannelGovernancePhase.INVITE_OR_APPLY, journey.phase());
        journey.submitInviteOrApply();
        journey.assignMemberRole(ChannelRole.MEMBER);
        ChannelAuthorizationGrant grant = journey.authorizeReadWrite();
        assertEquals(ChannelGovernancePhase.AUTHORIZED_READ_WRITE, journey.phase());
        assertTrue(grant.permits(ChannelCapability.READ));
        assertTrue(grant.permits(ChannelCapability.WRITE));
        assertEquals(7, grant.identity.accountId);
        assertEquals(Integer.valueOf(3), grant.consentedRulesVersion);
        ChannelPageLease lease = journey.pageLease();
        ChannelMemberAuthorizationProjection.denyFriendImpersonation(grant, ChannelImMutation.READ);
        ChannelAuthorizationException start = assertThrows(ChannelAuthorizationException.class,
                () -> ChannelMemberAuthorizationProjection.denyFriendImpersonation(grant, ChannelImMutation.START));
        assertEquals(ChannelAuthorizationException.Code.FRIEND_PERMISSION_REQUIRED, start.code);
        ChannelAuthorizationException friendDenied = assertThrows(ChannelAuthorizationException.class,
                () -> FriendPermissionDistinctFromChannel.authorize(grant, FriendPermissionGrant.none(),
                        FriendOnlyAction.DIRECT_MESSAGE));
        assertEquals(ChannelAuthorizationException.Code.FRIEND_PERMISSION_REQUIRED, friendDenied.code);
        ChannelAuthorizationException privacy = assertThrows(ChannelAuthorizationException.class,
                () -> FriendPermissionDistinctFromChannel.discloseProgress(grant, FriendPermissionGrant.friend(82),
                        ProgressPrivacySurface.REMARKS));
        assertEquals(ChannelAuthorizationException.Code.PROGRESS_PRIVACY_DENIED, privacy.code);
        journey.leave();
        assertEquals(ChannelGovernancePhase.LEFT, journey.phase());
        assertNull(journey.grant());
        ChannelAuthorizationException late = assertThrows(ChannelAuthorizationException.class,
                () -> journey.admit(new ChannelIsolatedRequest(lease, ChannelIsolatedAction.CHANNEL_WRITE)));
        assertEquals(ChannelAuthorizationException.Code.ISOLATED_LATE_REQUEST, late.code);
        assertEquals(ChannelIsolatedAdmission.ALLOWED,
                journey.admit(new ChannelIsolatedRequest(lease, ChannelIsolatedAction.HISTORICAL_VOTE_READ)));
        ChannelAuthorizationException voteWrite = assertThrows(ChannelAuthorizationException.class,
                () -> journey.admit(new ChannelIsolatedRequest(lease, ChannelIsolatedAction.HISTORICAL_VOTE_WRITE)));
        assertEquals(ChannelAuthorizationException.Code.HISTORICAL_VOTE_WRITE_DENIED, voteWrite.code);
        assertFalse(ChannelGovernanceTypes.v12DocxPresent());
        assertFalse(ChannelGovernanceTypes.pack232Present());
        assertFalse(ChannelGovernanceTypes.unpushedBackendPresent());
        IllegalStateException waiting = assertThrows(IllegalStateException.class,
                () -> journey.commitUnsignedOriginal(ChannelGovernanceTypes.V12_DOCX));
        assertTrue(waiting.getMessage().startsWith("WAITING_SOURCE:"));
    }

    @Test
    void catalogRejectsNonGroupAndP04PendingJoin() {
        assertEquals(ChannelAuthorizationException.Code.INVALID_CATALOG,
                assertThrows(ChannelAuthorizationException.class, () -> new ChannelCatalogEntry(21, 12,
                        ConversationKind.DIRECT, rules())).code);
        assertEquals(ChannelAuthorizationException.Code.ACCOUNT4_MISMATCH,
                assertThrows(ChannelAuthorizationException.class, () -> new ChannelAccount4Identity(0, 1L)).code);
        assertEquals(ChannelGovernanceTypes.ACCOUNT4, ChannelGovernanceTypes.ACCOUNT4);
        ChannelCatalogEntry stale = new ChannelCatalogEntry(21, 12, ConversationKind.GROUP,
                new ChannelRulesCas(3, Integer.valueOf(4), 2));
        assertEquals(ChannelAuthorizationException.Code.RULES_CAS_CONFLICT,
                assertThrows(ChannelAuthorizationException.class,
                        () -> new ChannelRulesAdmission(identity(), 3).admit(identity(), stale)).code);
        assertEquals(ChannelAuthorizationException.Code.RULES_CAS_CONFLICT,
                assertThrows(ChannelAuthorizationException.class,
                        () -> new ChannelRulesAdmission(identity(), 4).admit(identity(), catalog())).code);
        assertEquals(ChannelAuthorizationException.Code.RULES_CONSENT_REQUIRED,
                assertThrows(ChannelAuthorizationException.class,
                        () -> new ChannelRulesAdmission(identity(), 2).admit(identity(), catalog())).code);
    }

    @Test
    void moderatorGovernanceThenBanKeepsAppealDistinctFromLateRw() {
        ChannelGovernanceProjection journey = new ChannelGovernanceProjection(catalog(), identity());
        journey.consentToPublishedRules(new ChannelRulesAdmission(identity(), 3));
        journey.submitInviteOrApply();
        journey.assignMemberRole(ChannelRole.MODERATOR);
        journey.govern();
        assertEquals(ChannelGovernancePhase.GOVERNING, journey.phase());
        ChannelPageLease lease = journey.pageLease();
        journey.ban();
        assertEquals(ChannelGovernancePhase.BANNED, journey.phase());
        assertEquals(ChannelAuthorizationException.Code.ISOLATED_LATE_REQUEST,
                assertThrows(ChannelAuthorizationException.class,
                        () -> journey.admit(new ChannelIsolatedRequest(lease, ChannelIsolatedAction.GOVERNANCE))).code);
        assertEquals(ChannelIsolatedAdmission.ALLOWED,
                journey.admit(new ChannelIsolatedRequest(lease, ChannelIsolatedAction.APPEAL)));
        journey.appeal();
        assertEquals(ChannelGovernancePhase.APPEALING, journey.phase());
    }

    @Test
    void leftGroupHistoricalVotesStayReadOnly() {
        GroupPollView poll = new GroupPollView(9L, 12L, true, null);
        assertTrue(ChannelRevokeIsolation.voteWriteAllowed(poll, true));
        assertFalse(ChannelRevokeIsolation.voteWriteAllowed(poll, false));
        ChannelRevokeIsolation isolation = new ChannelRevokeIsolation(21, identity(), Long.valueOf(4L),
                ChannelGovernanceTypes.ChannelRevokeReason.LEAVE, ChannelGovernancePhase.LEFT);
        assertEquals(ChannelIsolatedAdmission.ALLOWED, isolation.historicalVoteAccess(poll, false));
        assertNotNull(isolation);
    }
}
