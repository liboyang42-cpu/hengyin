package com.chengyinhub.business.service.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.Failure;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.GateException;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.OriginalReviewTicket;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.Phase;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.PublishedRevision;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.RequestIdView;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.ReviewStatus;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.ReviewVisibility;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.ThemeMainline;
import com.chengyinhub.business.service.npc.MerchantNpcKnowledgeDraft.ForbiddenAiCommerce;
import com.chengyinhub.business.service.npc.MerchantNpcKnowledgeDraft.Origin;

/**
 * C06 first journey (isolation): AI draft → authorizer → original review ticket
 * with requestId → publish → requery same hash. AI price attempt fails. Revoke
 * then consume denied. Missing original → WAITING_SOURCE. No MySQL / HTTP.
 */
class C06FirstJourneyIsolationTest {

    @Test
    void aiDraftAuthorizerReviewPublishRequeryThenRevokeDeniesConsume() {
        InMemoryNpcFixtures fx = InMemoryNpcFixtures.synthetic();
        InMemoryNpcFixtures.RecordingPublisher publisher = new InMemoryNpcFixtures.RecordingPublisher();
        MerchantNpcDraftApprovalGate gate = new MerchantNpcDraftApprovalGate(
                fx.scope(), publisher, fx.isolation(), fx.qualification(), fx::scope);

        MerchantNpcKnowledgeDraft value = fx.draft();
        gate.ingestAiDraft(value);
        assertEquals(Phase.AI_DRAFT, gate.phase());
        assertEquals(Origin.AI, gate.origin());
        assertEquals(fx.requestId(), value.requestId());

        GateException price = assertThrows(GateException.class,
                () -> gate.applyAiCommerce(ForbiddenAiCommerce.PRICE));
        assertEquals(Failure.AI_CANNOT_PRICE, price.failure());
        assertEquals(Failure.AI_CANNOT_DEDUCT_STOCK,
                assertThrows(GateException.class, () -> gate.applyAiCommerce(ForbiddenAiCommerce.DEDUCT_STOCK))
                        .failure());
        assertEquals(Failure.AI_CANNOT_AWARD,
                assertThrows(GateException.class, () -> gate.applyAiCommerce(ForbiddenAiCommerce.AWARD)).failure());
        assertEquals(Failure.AI_CANNOT_CERTIFY_PRESENCE,
                assertThrows(GateException.class, () -> gate.applyAiCommerce(ForbiddenAiCommerce.CERTIFY_PRESENCE))
                        .failure());

        gate.verifyByAuthorizer(fx.authorizer());
        OriginalReviewTicket ticket = gate.submitOriginalReview(fx.requestId());
        assertEquals(fx.requestId(), ticket.requestId());
        assertEquals(value.factsHash(), ticket.factsHash());
        assertEquals(1, publisher.submitted());

        PublishedRevision published = gate.publishApproved();
        assertEquals(Phase.PUBLISHED, gate.phase());
        assertEquals(fx.requestId(), published.requestId());
        assertEquals(value.factsHash(), published.factsHash());
        byte[] consumed = gate.playerConsume();
        assertEquals(value.factsHash(), MerchantNpcKnowledgeDraft.sha256Hex(consumed));
        PublishedRevision requery = gate.requeryPublished(fx.requestId());
        assertEquals(published.factsHash(), requery.factsHash());
        assertEquals(fx.requestId(), requery.requestId());

        String rejectedId = "c06-this-request-rejected";
        gate.rememberRejectedReview(rejectedId);
        ReviewVisibility rejected = gate.visibilityForRequest(rejectedId, true);
        assertEquals(ReviewStatus.REJECTED, rejected.status());
        assertFalse(rejected.rewrittenToApproved());

        ThemeMainline mainline = gate.themeMainlineWhenAiUnavailable();
        assertTrue(mainline.completable());
        assertFalse(mainline.fakeAiSuccess());
        assertEquals(published.factsHash(), mainline.approvedFactsHash());

        fx.qualification().revoke(fx.scope());
        GateException revoked = assertThrows(GateException.class, gate::playerConsume);
        assertEquals(Failure.QUALIFICATION_REVOKED, revoked.failure());

        MerchantNpcDraftApprovalGate waiting = fx.gateWithDefaultWaitingSource();
        waiting.ingestAiDraft(fx.draft());
        waiting.verifyByAuthorizer(fx.authorizer());
        GateException missingOriginal = assertThrows(GateException.class,
                () -> waiting.submitOriginalReview(fx.requestId()));
        assertTrue(missingOriginal.isWaitingSource());
        assertEquals(MerchantNpcDraftApprovalGate.KNOWLEDGE_REVIEW_AUTHORITY, missingOriginal.capability());
        assertEquals(Phase.WAITING_SOURCE, waiting.phase());

        RequestIdView missingId = waiting.inspectRequestId(null);
        assertNull(missingId.requestId());
        assertEquals(Failure.WAITING_SOURCE, missingId.failure());
        GateException unsigned = waiting.consumeUnsignedOriginal(MerchantNpcDraftApprovalGate.UNSIGNED_DOT_SHA);
        assertTrue(unsigned.isWaitingSource());
        assertFalse(MerchantNpcDraftApprovalGate.UNSIGNED_ORIGINALS_PRESENT);
    }
}
