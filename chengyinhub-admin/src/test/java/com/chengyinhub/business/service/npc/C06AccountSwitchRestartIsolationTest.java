package com.chengyinhub.business.service.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.Failure;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.GateException;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.Phase;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.PublishedRevision;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.ReviewStatus;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.ReviewVisibility;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.Scope;

/**
 * C12 isolation scenarios that C06 exclusive adapters can prove without HTTP /
 * MySQL / App: account switch, in-process restart recover, timeout UNKNOWN
 * not rewritten by a later approved projection.
 */
class C06AccountSwitchRestartIsolationTest {

    @Test
    void accountSwitchDoesNotConsumeOrRewriteOriginalPublishedRevision() {
        InMemoryNpcFixtures fx = InMemoryNpcFixtures.synthetic();
        InMemoryNpcFixtures.RecordingPublisher publisher = new InMemoryNpcFixtures.RecordingPublisher();
        AtomicReference<Scope> live = new AtomicReference<Scope>(fx.scope());
        MerchantNpcDraftApprovalGate gate = new MerchantNpcDraftApprovalGate(
                fx.scope(), publisher, fx.isolation(), fx.qualification(), live::get);
        gate.ingestAiDraft(fx.draft());
        gate.verifyByAuthorizer(fx.authorizer());
        gate.submitOriginalReview(fx.requestId());
        PublishedRevision published = gate.publishApproved();

        live.set(new Scope(777L, fx.scope().merchantRowId(), InMemoryNpcFixtures.NAMESPACE));
        GateException switched = assertThrows(GateException.class, gate::playerConsume);
        assertEquals(Failure.STALE_SCOPE, switched.failure());
        PublishedRevision original = gate.requeryPublished(fx.requestId());
        assertEquals(published.factsHash(), original.factsHash());
        assertEquals(fx.requestId(), original.requestId());
        assertEquals(fx.scope().accountId(), original.scope().accountId());
        assertEquals(Phase.PUBLISHED, gate.phase());
    }

    @Test
    void restartSharesQualificationStoreAndRecoversOriginalPublishedFacts() {
        InMemoryNpcFixtures fx = InMemoryNpcFixtures.synthetic();
        InMemoryNpcFixtures.RecordingPublisher publisher = new InMemoryNpcFixtures.RecordingPublisher();
        MerchantNpcDraftApprovalGate gate = new MerchantNpcDraftApprovalGate(
                fx.scope(), publisher, fx.isolation(), fx.qualification(), fx::scope);
        gate.ingestAiDraft(fx.draft());
        gate.verifyByAuthorizer(fx.authorizer());
        gate.submitOriginalReview(fx.requestId());
        PublishedRevision published = gate.publishApproved();

        byte[] recovered = fx.isolation().playerProjection(fx.scope(), fx.qualification());
        assertEquals(published.factsHash(), MerchantNpcKnowledgeDraft.sha256Hex(recovered));

        MerchantNpcDraftApprovalGate restarted = new MerchantNpcDraftApprovalGate(
                fx.scope(), publisher, fx.isolation(), fx.qualification(), fx::scope);
        assertEquals(Phase.IDLE, restarted.phase());
        byte[] stillPublic = restarted.isolation().playerProjection(fx.scope(), restarted.qualification());
        assertEquals(published.factsHash(), MerchantNpcKnowledgeDraft.sha256Hex(stillPublic));
        assertEquals(fx.requestId(), fx.qualification().cachedPublic(fx.scope()).requestId());
        assertNull(restarted.requeryPublished(fx.requestId()));
    }

    @Test
    void timeoutUnknownReviewIsNotRewrittenByLaterApprovedProjection() {
        InMemoryNpcFixtures fx = InMemoryNpcFixtures.synthetic();
        MerchantNpcDraftApprovalGate gate = fx.gateWithRecordingPublisher();
        gate.ingestAiDraft(fx.draft());
        gate.verifyByAuthorizer(fx.authorizer());
        gate.submitOriginalReview(fx.requestId());
        gate.publishApproved();

        String timeoutId = "c06-timeout-unknown";
        fx.isolation().rememberReview(timeoutId, ReviewStatus.WAITING_SOURCE);
        ReviewVisibility visibility = gate.visibilityForRequest(timeoutId, true);
        assertEquals(timeoutId, visibility.requestId());
        assertEquals(ReviewStatus.WAITING_SOURCE, visibility.status());
        assertFalse(visibility.rewrittenToApproved());
        assertNull(gate.requeryPublished(timeoutId));
        assertNull(gate.inspectRequestId(null).requestId());
    }

    @Test
    void reviewStatusOfNeverSeenIdDoesNotOccupyLedgerThenRememberReviewIsFirstWrite() {
        InMemoryNpcFixtures fx = InMemoryNpcFixtures.synthetic();
        MerchantNpcPublicPrivateIsolation isolation = fx.isolation();
        assertNull(isolation.reviewStatusForRequest(null, true));
        assertNull(isolation.reviewStatusForRequest("", true));
        assertNull(isolation.reviewStatusForRequest("   ", true));
        assertNull(isolation.reviewStatusForRequest("c06-review-never-seen", true));
        isolation.rememberReview(null, ReviewStatus.PENDING);
        isolation.rememberReview("", ReviewStatus.PENDING);
        assertNull(isolation.reviewStatusForRequest(null, true));
        assertNull(isolation.reviewStatusForRequest("c06-review-never-seen", true));
        isolation.rememberReview("c06-review-never-seen", ReviewStatus.PENDING);
        assertEquals(ReviewStatus.PENDING, isolation.reviewStatusForRequest("c06-review-never-seen", true));
        isolation.rememberReview("c06-review-never-seen", ReviewStatus.REJECTED);
        assertEquals(ReviewStatus.REJECTED, isolation.reviewStatusForRequest("c06-review-never-seen", true));
        assertEquals(ReviewStatus.REJECTED, isolation.reviewStatusForRequest("c06-review-never-seen", true));
    }

    @Test
    void requeryPublishedOfNeverSeenIdDoesNotOccupyLedgerThenUniquePublishIsFirstWrite() {
        InMemoryNpcFixtures fx = InMemoryNpcFixtures.synthetic();
        InMemoryNpcFixtures.RecordingPublisher publisher = new InMemoryNpcFixtures.RecordingPublisher();
        MerchantNpcDraftApprovalGate gate = new MerchantNpcDraftApprovalGate(
                fx.scope(), publisher, fx.isolation(), fx.qualification(), fx::scope);
        String unseen = fx.requestId();
        assertNull(gate.requeryPublished(null));
        assertNull(gate.requeryPublished(""));
        assertNull(gate.requeryPublished("   "));
        assertNull(gate.requeryPublished(unseen));
        assertNull(gate.requeryPublished("c06-pub-never-seen"));
        assertNull(fx.isolation().reviewStatusForRequest(unseen, true));

        gate.ingestAiDraft(fx.draft());
        gate.verifyByAuthorizer(fx.authorizer());
        assertNull(gate.requeryPublished(unseen), "审核票不是 published 账");
        gate.submitOriginalReview(unseen);
        assertNull(gate.requeryPublished(unseen), "提交审核不得占 published 账");
        assertNull(gate.requeryPublished("c06-pub-never-seen"));

        PublishedRevision published = gate.publishApproved();
        assertEquals(unseen, published.requestId());
        assertEquals(published.factsHash(), gate.requeryPublished(unseen).factsHash());
        assertNull(gate.requeryPublished("c06-pub-never-seen"), "另一未见 id 仍不得占账");
        assertEquals(ReviewStatus.APPROVED, fx.isolation().reviewStatusForRequest(unseen, true));
        assertNull(fx.isolation().reviewStatusForRequest("c06-pub-never-seen", true));
    }
}
