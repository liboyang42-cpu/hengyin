package com.chengyinhub.business.service.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.Failure;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.GateException;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.PublishedRevision;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.Scope;

class MerchantNpcQualificationRevokeTest {

    @Test
    void revokeClearsCacheSoOldDraftCannotServe() {
        InMemoryNpcFixtures fx = InMemoryNpcFixtures.synthetic();
        MerchantNpcQualificationRevoke qualification = fx.qualification();
        MerchantNpcPublicPrivateIsolation isolation = fx.isolation();
        InMemoryNpcFixtures.RecordingPublisher publisher = new InMemoryNpcFixtures.RecordingPublisher();
        MerchantNpcDraftApprovalGate gate = new MerchantNpcDraftApprovalGate(
                fx.scope(), publisher, isolation, qualification, fx::scope);
        MerchantNpcKnowledgeDraft value = fx.draft();
        gate.ingestAiDraft(value);
        assertNotNull(qualification.cachedPrivate(fx.scope()));
        gate.verifyByAuthorizer(fx.authorizer());
        gate.submitOriginalReview(fx.requestId());
        PublishedRevision revision = gate.publishApproved();
        assertEquals(MerchantNpcKnowledgeDraft.sha256Hex(revision.facts()),
                MerchantNpcKnowledgeDraft.sha256Hex(gate.playerConsume()));
        assertNull(qualification.cachedPrivate(fx.scope()));
        assertEquals(revision.revisionId(), qualification.cachedPublic(fx.scope()).revisionId());

        qualification.revoke(fx.scope());
        assertTrue(qualification.isRevoked(fx.scope()));
        assertFalse(qualification.allowsServing(fx.scope()));
        assertNull(qualification.cachedPublic(fx.scope()));
        assertNull(qualification.cachedPrivate(fx.scope()));
        GateException consume = assertThrows(GateException.class, gate::playerConsume);
        assertEquals(Failure.QUALIFICATION_REVOKED, consume.failure());
        GateException projection = assertThrows(GateException.class,
                () -> isolation.playerProjection(fx.scope(), qualification));
        assertEquals(Failure.QUALIFICATION_REVOKED, projection.failure());
    }

    @Test
    void oldDraftObjectCannotReenterPublicCacheAfterRevoke() {
        InMemoryNpcFixtures fx = InMemoryNpcFixtures.synthetic();
        MerchantNpcQualificationRevoke qualification = new MerchantNpcQualificationRevoke();
        Scope scope = fx.scope();
        MerchantNpcKnowledgeDraft value = fx.draft();
        PublishedRevision stale = new PublishedRevision("rev-stale", fx.requestId(), scope,
                value.validatedPublicFactsJson(), value.factsHash());
        qualification.rememberPublished(stale, scope);
        qualification.revoke(scope);
        qualification.rememberPublished(stale, scope);
        qualification.rememberPrivate(value, scope);
        assertNull(qualification.cachedPublic(scope));
        assertNull(qualification.cachedPrivate(scope));
        assertFalse(qualification.allowsServing(scope));
    }

    @Test
    void publicStoreIsolatedFromPrivateOpsAfterRevoke() {
        InMemoryNpcFixtures fx = InMemoryNpcFixtures.synthetic();
        MerchantNpcQualificationRevoke qualification = fx.qualification();
        MerchantNpcPublicPrivateIsolation isolation = fx.isolation();
        MerchantNpcDraftApprovalGate gate = new MerchantNpcDraftApprovalGate(
                fx.scope(), new InMemoryNpcFixtures.RecordingPublisher(), isolation, qualification, fx::scope);
        gate.ingestAiDraft(fx.draft());
        assertEquals(MerchantNpcDraftApprovalGate.Audience.MERCHANT_PRIVATE_ASSISTANT,
                isolation.audienceFor(gate.phase()));
        assertFalse(isolation.visibilityIsPublic(gate.phase()));
        qualification.revoke(fx.scope());
        assertNull(qualification.cachedPrivate(fx.scope()));
        GateException consume = assertThrows(GateException.class, gate::playerConsume);
        assertEquals(Failure.QUALIFICATION_REVOKED, consume.failure());
    }
}
