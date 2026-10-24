package com.chengyinhub.business.service.npc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.Audience;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.Failure;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.GateException;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.Phase;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.PublishedRevision;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.RequestIdView;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.ReviewStatus;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.ReviewVisibility;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.ThemeMainline;
import com.chengyinhub.business.service.npc.MerchantNpcKnowledgeDraft.ForbiddenAiCommerce;
import com.chengyinhub.business.service.npc.MerchantNpcKnowledgeDraft.Origin;

class MerchantNpcDraftApprovalGateTest {

    @Test
    void aiDraftCannotPublishWithoutAuthorizer() {
        InMemoryNpcFixtures fx = InMemoryNpcFixtures.synthetic();
        MerchantNpcDraftApprovalGate gate = fx.gateWithRecordingPublisher();
        gate.ingestAiDraft(fx.draft());
        assertEquals(Phase.AI_DRAFT, gate.phase());
        assertEquals(Origin.AI, gate.origin());
        assertFalse(gate.draft().encodesLiveCommerceMutation());
        GateException publish = assertThrows(GateException.class, gate::publishApproved);
        assertEquals(Failure.AUTHORIZER_REQUIRED, publish.failure());
        GateException consume = assertThrows(GateException.class, gate::playerConsume);
        assertEquals(Failure.UNPUBLISHED, consume.failure());
    }

    @Test
    void aiCannotPriceDeductStockAwardOrCertifyPresence() {
        InMemoryNpcFixtures fx = InMemoryNpcFixtures.synthetic();
        MerchantNpcDraftApprovalGate gate = fx.gateWithRecordingPublisher();
        gate.ingestAiDraft(fx.draft());
        for (ForbiddenAiCommerce mutation : ForbiddenAiCommerce.values()) {
            assertFalse(gate.draft().applyingAiCommerce(mutation).isEmpty(), mutation.name());
            GateException thrown = assertThrows(GateException.class, () -> gate.applyAiCommerce(mutation),
                    mutation.name());
            assertEquals(Failure.commerce(mutation), thrown.failure(), mutation.name());
        }
    }

    @Test
    void missingReviewAndPublishApisAreWaitingSourceNotSuccessfulStubs() {
        InMemoryNpcFixtures fx = InMemoryNpcFixtures.synthetic();
        MerchantNpcDraftApprovalGate gate = fx.gateWithDefaultWaitingSource();
        gate.ingestAiDraft(fx.draft());
        gate.verifyByAuthorizer(fx.authorizer());
        GateException review = assertThrows(GateException.class,
                () -> gate.submitOriginalReview(fx.requestId()));
        assertTrue(review.isWaitingSource());
        assertEquals(MerchantNpcDraftApprovalGate.KNOWLEDGE_REVIEW_AUTHORITY, review.capability());
        assertEquals(Phase.WAITING_SOURCE, gate.phase());
        GateException generate = assertThrows(GateException.class, gate::requestAiDraftGeneration);
        assertEquals(MerchantNpcDraftApprovalGate.KNOWLEDGE_DRAFT_HTTP, generate.capability());
        GateException path = assertThrows(GateException.class,
                () -> gate.refuseUnservedKnowledgePath("/api/merchant/npc/knowledge/publish"));
        assertEquals("/api/merchant/npc/knowledge/publish", path.capability());
        GateException auth = assertThrows(GateException.class, gate::refuseAiGatewayPublicAuth);
        assertEquals(MerchantNpcDraftApprovalGate.AI_GATEWAY_PUBLIC_AUTH, auth.capability());
        assertEquals(1, MerchantNpcDraftApprovalGate.SERVED_PATHS.size());
        assertTrue(MerchantNpcDraftApprovalGate.SERVED_PATHS.contains("/api/ai/npc/merchant-chat"));
        assertFalse(MerchantNpcDraftApprovalGate.SERVED_PATHS.contains("/api/merchant/npc/knowledge/publish"));
        assertFalse(MerchantNpcDraftApprovalGate.UNSIGNED_ORIGINALS_PRESENT);
    }

    @Test
    void authorizerThenOriginalReviewThenPlayerConsume() {
        InMemoryNpcFixtures fx = InMemoryNpcFixtures.synthetic();
        InMemoryNpcFixtures.RecordingPublisher publisher = new InMemoryNpcFixtures.RecordingPublisher();
        MerchantNpcPublicPrivateIsolation isolation = fx.isolation();
        MerchantNpcDraftApprovalGate gate = new MerchantNpcDraftApprovalGate(
                fx.scope(), publisher, isolation, fx.qualification(), fx::scope);
        MerchantNpcKnowledgeDraft value = fx.draft();
        gate.ingestAiDraft(value);
        assertEquals(Audience.MERCHANT_PRIVATE_ASSISTANT, isolation.audienceFor(gate.phase()));
        GateException leak = assertThrows(GateException.class,
                () -> isolation.rejectPrivateLeak(value.validatedPublicFactsJson(), value, gate.phase()));
        assertEquals(Failure.ISOLATION_VIOLATION, leak.failure());
        gate.verifyByAuthorizer(fx.authorizer());
        gate.submitOriginalReview(fx.requestId());
        assertEquals(1, publisher.submitted());
        PublishedRevision revision = gate.publishApproved();
        assertEquals(1, publisher.publishedCount());
        assertEquals(Phase.PUBLISHED, gate.phase());
        assertEquals(Audience.PLAYER_PUBLIC_NPC, isolation.audienceFor(gate.phase()));
        byte[] consumed = gate.playerConsume();
        assertTrue(Arrays.equals(consumed, revision.facts()));
        assertTrue(Arrays.equals(consumed, value.validatedPublicFactsJson()));
        isolation.rejectPrivateLeak(consumed, value, gate.phase());
        assertEquals(revision.factsHash(), gate.requeryPublished(fx.requestId()).factsHash());
    }

    @Test
    void themeMainlineWorksWhenAiUnavailableUsesApprovedCopyNotFakeSuccess() {
        InMemoryNpcFixtures fx = InMemoryNpcFixtures.synthetic();
        MerchantNpcDraftApprovalGate gate = fx.gateWithRecordingPublisher();
        ThemeMainline mainline = gate.themeMainlineWhenAiUnavailable();
        assertEquals(Failure.AI_UNAVAILABLE, gate.failure());
        assertTrue(gate.themeMainlineAvailableWhenAiUnavailable());
        assertTrue(MerchantNpcPublicPrivateIsolation.THEME_MAINLINE_AVAILABLE_WHEN_AI_UNAVAILABLE);
        assertTrue(mainline.completable());
        assertFalse(mainline.fakeAiSuccess());
        assertNull(mainline.approvedFactsHash());
        MerchantNpcPublicPrivateIsolation isolation = new MerchantNpcPublicPrivateIsolation();
        assertTrue(isolation.themeRunMayProceed(Failure.AI_UNAVAILABLE));
        assertTrue(isolation.themeRunMayProceed(Failure.WAITING_SOURCE));
    }

    @Test
    void wrongAuthorizerCannotPromoteDraft() {
        InMemoryNpcFixtures fx = InMemoryNpcFixtures.synthetic();
        MerchantNpcDraftApprovalGate gate = fx.gateWithRecordingPublisher();
        gate.ingestAiDraft(fx.draft());
        GateException thrown = assertThrows(GateException.class,
                () -> gate.verifyByAuthorizer(fx.wrongAuthorizer()));
        assertEquals(Failure.AUTHORIZER_REQUIRED, thrown.failure());
        assertEquals(Phase.AI_DRAFT, gate.phase());
    }

    @Test
    void missingRequestIdStaysJavaNullNeverEmptyString() {
        InMemoryNpcFixtures fx = InMemoryNpcFixtures.synthetic();
        MerchantNpcDraftApprovalGate gate = fx.gateWithDefaultWaitingSource();
        gate.ingestAiDraft(fx.draftWithoutRequestId());
        assertNull(gate.draft().requestId());
        assertNotEquals("", gate.draft().requestId());
        RequestIdView omitted = gate.inspectRequestId(null);
        assertNull(omitted.requestId());
        assertEquals(Failure.WAITING_SOURCE, omitted.failure());
        RequestIdView blank = gate.inspectRequestId("");
        assertNull(blank.requestId());
        gate.verifyByAuthorizer(fx.authorizer());
        GateException thrown = assertThrows(GateException.class, () -> gate.submitOriginalReview(null));
        assertTrue(thrown.isWaitingSource());
        assertNull(thrown.requestId());
        assertNull(gate.inspectRequestId("   ").requestId());
    }

    @Test
    void currentPublishedProjectionDoesNotRewriteThisRequestsRejectedReview() {
        InMemoryNpcFixtures fx = InMemoryNpcFixtures.synthetic();
        MerchantNpcDraftApprovalGate gate = fx.gateWithRecordingPublisher();
        gate.ingestAiDraft(fx.draft());
        gate.verifyByAuthorizer(fx.authorizer());
        gate.submitOriginalReview(fx.requestId());
        gate.publishApproved();
        String rejectedId = "c06-rejected-review";
        gate.rememberRejectedReview(rejectedId);
        ReviewVisibility visibility = gate.visibilityForRequest(rejectedId, true);
        assertEquals(rejectedId, visibility.requestId());
        assertEquals(ReviewStatus.REJECTED, visibility.status());
        assertFalse(visibility.rewrittenToApproved());
        assertEquals(ReviewStatus.APPROVED,
                gate.visibilityForRequest(fx.requestId(), true).status());
    }

    @Test
    void unsignedOriginalsV12Pack232E16c2c5StayWaitingSource() {
        InMemoryNpcFixtures fx = InMemoryNpcFixtures.synthetic();
        MerchantNpcDraftApprovalGate gate = fx.gateWithDefaultWaitingSource();
        for (String marker : new String[] {
                MerchantNpcDraftApprovalGate.UNSIGNED_V12,
                MerchantNpcDraftApprovalGate.UNSIGNED_FILE_PACK_232,
                MerchantNpcDraftApprovalGate.UNSIGNED_DOT_SHA
        }) {
            GateException thrown = gate.consumeUnsignedOriginal(marker);
            assertTrue(thrown.isWaitingSource(), marker);
            assertEquals("unsigned-original:" + marker, thrown.capability(), marker);
        }
        assertFalse(MerchantNpcDraftApprovalGate.UNSIGNED_ORIGINALS_PRESENT);
    }

    @Test
    void reviewStatusOfNeverSeenIdDoesNotOccupyThenRememberRejectedIsFirstWrite() {
        InMemoryNpcFixtures fx = InMemoryNpcFixtures.synthetic();
        MerchantNpcDraftApprovalGate gate = fx.gateWithRecordingPublisher();
        assertNull(gate.visibilityForRequest(null, true).requestId());
        assertNull(gate.visibilityForRequest(null, true).status());
        assertNull(gate.visibilityForRequest("", true).requestId());
        assertNull(gate.visibilityForRequest("   ", true).status());
        assertNull(gate.visibilityForRequest("c06-review-never", true).status());
        gate.rememberRejectedReview(null);
        gate.rememberRejectedReview("");
        gate.rememberRejectedReview("   ");
        assertNull(gate.visibilityForRequest("c06-review-never", true).status(), "空白回读不得占审核账");
        gate.rememberRejectedReview("c06-review-never");
        assertEquals(ReviewStatus.REJECTED, gate.visibilityForRequest("c06-review-never", true).status());
        assertEquals("c06-review-never", gate.visibilityForRequest("c06-review-never", true).requestId());
        assertNull(gate.visibilityForRequest("c06-review-other", true).status(), "另一未见 id 仍不得占账");
    }

    @Test
    void unpublishedCachedPublicDoesNotOccupyThenUniquePublishIsFirstWrite() {
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
        assertNull(fx.qualification().cachedPublic(fx.scope()));
        assertEquals(Failure.UNPUBLISHED,
                assertThrows(GateException.class, () -> fx.isolation().playerProjection(fx.scope(), fx.qualification()))
                        .failure());

        gate.ingestAiDraft(fx.draft());
        gate.verifyByAuthorizer(fx.authorizer());
        assertNull(gate.requeryPublished(unseen), "核实不得占 published 账");
        assertNull(fx.qualification().cachedPublic(fx.scope()));
        gate.submitOriginalReview(unseen);
        assertNull(gate.requeryPublished(unseen), "提交审核不得占 published 账");
        assertNull(fx.qualification().cachedPublic(fx.scope()));
        assertNull(gate.requeryPublished("c06-pub-never-seen"));

        PublishedRevision published = gate.publishApproved();
        assertEquals(unseen, published.requestId());
        assertEquals(published.factsHash(), gate.requeryPublished(unseen).factsHash());
        assertEquals(published.revisionId(), fx.qualification().cachedPublic(fx.scope()).revisionId());
        assertNull(gate.requeryPublished("c06-pub-never-seen"), "另一未见 id 仍不得占账");
        assertEquals(ReviewStatus.APPROVED, fx.isolation().reviewStatusForRequest(unseen, true));
        assertNull(fx.isolation().reviewStatusForRequest("c06-pub-never-seen", true));
    }

    @Test
    void unpublishedProjectionDoesNotOccupyPrivateScopesThenUniqueRetainPrivateApplies() {
        InMemoryNpcFixtures fx = InMemoryNpcFixtures.synthetic();
        MerchantNpcPublicPrivateIsolation isolation = new MerchantNpcPublicPrivateIsolation();
        MerchantNpcQualificationRevoke qualification = new MerchantNpcQualificationRevoke();
        assertEquals(Failure.UNPUBLISHED,
                assertThrows(GateException.class, () -> isolation.playerProjection(fx.scope(), qualification))
                        .failure());
        MerchantNpcKnowledgeDraft value = fx.draft();
        PublishedRevision first = new PublishedRevision("rev-iso-first", fx.requestId(), fx.scope(),
                value.validatedPublicFactsJson(), value.factsHash());
        qualification.rememberPublished(first, fx.scope());
        assertArrayEquals(first.facts(), isolation.playerProjection(fx.scope(), qualification),
                "unpublished 观察不得占 privateScopes");
        isolation.retainPrivate(value, fx.scope());
        assertEquals(Failure.ISOLATION_VIOLATION,
                assertThrows(GateException.class, () -> isolation.playerProjection(fx.scope(), qualification))
                        .failure());
        isolation.publish(first, fx.scope());
        assertArrayEquals(first.facts(), isolation.playerProjection(fx.scope(), qualification));
    }
}
