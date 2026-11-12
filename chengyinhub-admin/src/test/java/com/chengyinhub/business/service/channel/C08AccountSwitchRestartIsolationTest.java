package com.chengyinhub.business.service.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelAccount4Identity;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelAuthorizationGrant;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelCapability;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelCatalogEntry;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelGovernancePhase;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelIsolatedAction;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelIsolatedRequest;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelPageLease;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelRole;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelRulesCas;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ConversationKind;

/**
 * C12 isolation: ACCOUNT4 switch must not rewrite the original identity;
 * in-process restart requery keeps the original grant; unsigned originals stay
 * WAITING_SOURCE.
 */
class C08AccountSwitchRestartIsolationTest {

    private static ChannelCatalogEntry catalog() {
        return new ChannelCatalogEntry(21, 12, ConversationKind.GROUP,
                new ChannelRulesCas(3, Integer.valueOf(4), 3));
    }

    private static ChannelGovernanceProjection authorized(ChannelAccount4Identity identity) {
        ChannelGovernanceProjection journey = new ChannelGovernanceProjection(catalog(), identity);
        journey.requireRulesConsent();
        journey.consentToPublishedRules(new ChannelRulesAdmission(identity, 3));
        journey.submitInviteOrApply();
        journey.assignMemberRole(ChannelRole.MEMBER);
        journey.authorizeReadWrite();
        return journey;
    }

    @Test
    void otherAccount4CannotAdmitOrRewriteOriginalGrant() {
        ChannelAccount4Identity original = new ChannelAccount4Identity(7, 1L);
        ChannelGovernanceProjection journey = authorized(original);
        ChannelAuthorizationGrant grant = journey.grant();
        assertNotNull(grant);
        assertEquals(original, grant.identity);
        assertEquals(ChannelRole.MEMBER, grant.role);

        ChannelAccount4Identity other = new ChannelAccount4Identity(9, 1L);
        ChannelAccount4Identity otherEpoch = new ChannelAccount4Identity(7, 2L);
        ChannelCatalogEntry catalog = catalog();
        assertEquals(ChannelAuthorizationException.Code.ACCOUNT4_MISMATCH,
                assertThrows(ChannelAuthorizationException.class,
                        () -> new ChannelRulesAdmission(original, 3).admit(other, catalog)).code);
        assertEquals(ChannelAuthorizationException.Code.ACCOUNT4_MISMATCH,
                assertThrows(ChannelAuthorizationException.class,
                        () -> new ChannelRulesAdmission(original, 3).admit(otherEpoch, catalog)).code);

        ChannelPageLease foreignLease = new ChannelPageLease(21, 12, grant.generation, other,
                EnumSet.of(ChannelCapability.WRITE), Integer.valueOf(3));
        ChannelRevokeIsolation isolation = new ChannelRevokeIsolation(21, original, Long.valueOf(grant.generation),
                null, ChannelGovernancePhase.AUTHORIZED_READ_WRITE);
        assertEquals(ChannelAuthorizationException.Code.ACCOUNT4_MISMATCH,
                assertThrows(ChannelAuthorizationException.class,
                        () -> isolation.admit(new ChannelIsolatedRequest(foreignLease,
                                ChannelIsolatedAction.CHANNEL_WRITE))).code);

        ChannelAuthorizationGrant stillOriginal = journey.grant();
        assertEquals(original, stillOriginal.identity);
        assertEquals(7, stillOriginal.identity.accountId);
        assertEquals(1L, stillOriginal.identity.epoch);
        assertNotEquals(other, stillOriginal.identity);
    }

    @Test
    void restartRequeryKeepsOriginalGrantAndUnsignedOriginalWaitingSource() {
        ChannelAccount4Identity identity = new ChannelAccount4Identity(7, 1L);
        ChannelGovernanceProjection original = authorized(identity);
        ChannelAuthorizationGrant grant = original.grant();
        assertEquals(identity, grant.identity);
        assertEquals(ChannelGovernancePhase.AUTHORIZED_READ_WRITE, original.phase());

        ChannelGovernanceProjection restarted = new ChannelGovernanceProjection(catalog(), identity);
        assertEquals(ChannelGovernancePhase.CATALOGED, restarted.phase());
        assertNull(restarted.grant());
        assertEquals(identity, original.grant().identity);
        assertEquals(ChannelRole.MEMBER, original.grant().role);
        assertEquals(Integer.valueOf(3), original.grant().consentedRulesVersion);

        IllegalStateException waiting = assertThrows(IllegalStateException.class,
                () -> original.commitUnsignedOriginal("D5-native-43"));
        assertTrue(waiting.getMessage().startsWith("WAITING_SOURCE:"));
        assertEquals(identity, original.grant().identity);
    }

    @Test
    void missingAccount4StaysMismatchNeverEmptyIdentity() {
        assertEquals(ChannelAuthorizationException.Code.ACCOUNT4_MISMATCH,
                assertThrows(ChannelAuthorizationException.class, () -> new ChannelAccount4Identity(0, 1L)).code);
        assertEquals(ChannelAuthorizationException.Code.ACCOUNT4_MISMATCH,
                assertThrows(ChannelAuthorizationException.class, () -> new ChannelAccount4Identity(-1, 1L)).code);
        ChannelAccount4Identity original = new ChannelAccount4Identity(7, 1L);
        assertNotEquals("", Integer.toString(original.accountId));
        assertEquals(ChannelAuthorizationException.Code.ACCOUNT4_MISMATCH,
                assertThrows(ChannelAuthorizationException.class,
                        () -> new ChannelRulesAdmission(original, 3).admit(null, catalog())).code);
    }

    @Test
    void catalogedGrantDoesNotOccupyThenUniqueAuthorizeIsFirstWrite() {
        ChannelAccount4Identity identity = new ChannelAccount4Identity(7, 1L);
        ChannelGovernanceProjection journey = new ChannelGovernanceProjection(catalog(), identity);
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
}
