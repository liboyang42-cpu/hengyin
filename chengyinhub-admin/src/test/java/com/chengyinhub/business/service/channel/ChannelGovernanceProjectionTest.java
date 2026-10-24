package com.chengyinhub.business.service.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelAccount4Identity;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelAuthorizationGrant;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelCatalogEntry;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelGovernancePhase;
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
}
