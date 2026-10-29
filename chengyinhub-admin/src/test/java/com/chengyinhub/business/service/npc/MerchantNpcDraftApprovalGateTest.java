package com.chengyinhub.business.service.npc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.service.IAiCreatorService;
import com.chengyinhub.business.service.impl.AiThemeDraftRequestService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chengyinhub.business.domain.vo.ai.AiThemeDraftRequestState;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftResp;
import com.chengyinhub.business.domain.vo.ai.AiThemePlan;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.Audience;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.DraftQuery;
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
import com.chengyinhub.business.service.npc.MerchantNpcKnowledgeDraft.Product;

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

    @Test
    void unusedVerifyBeforeIngestDoesNotOccupyThenUniqueIngestThenVerifyApplies() {
        InMemoryNpcFixtures fx = InMemoryNpcFixtures.synthetic();
        MerchantNpcDraftApprovalGate gate = fx.gateWithRecordingPublisher();
        assertEquals(Failure.UNPUBLISHED,
                assertThrows(GateException.class, () -> gate.verifyByAuthorizer(fx.authorizer()))
                        .failure());
        assertEquals(Phase.IDLE, gate.phase(), "未见草稿 verify 观察不得占 phase");
        assertNull(gate.authorizer(), "未见草稿 verify 观察不得占 authorizer");
        assertNull(gate.origin());
        assertNull(fx.qualification().cachedPublic(fx.scope()));
        gate.ingestAiDraft(fx.draft());
        assertEquals(Phase.AI_DRAFT, gate.phase());
        assertNull(gate.authorizer());
        gate.verifyByAuthorizer(fx.authorizer());
        assertEquals(Phase.AUTHORIZER_VERIFIED, gate.phase());
        assertEquals(fx.authorizer(), gate.authorizer());
        assertEquals(Origin.AI, gate.origin());
        assertNull(fx.qualification().cachedPublic(fx.scope()), "核实不得占 published 账");
    }

    @Test
    void unusedSubmitBeforeAuthorizerDoesNotOccupyThenUniqueSubmitApplies() {
        InMemoryNpcFixtures fx = InMemoryNpcFixtures.synthetic();
        InMemoryNpcFixtures.RecordingPublisher publisher = new InMemoryNpcFixtures.RecordingPublisher();
        MerchantNpcDraftApprovalGate gate = new MerchantNpcDraftApprovalGate(
                fx.scope(), publisher, fx.isolation(), fx.qualification(), fx::scope);
        assertEquals(Failure.AUTHORIZER_REQUIRED,
                assertThrows(GateException.class, () -> gate.submitOriginalReview(fx.requestId()))
                        .failure());
        assertEquals(Phase.IDLE, gate.phase(), "未见授权 submit 观察不得占 phase");
        assertNull(gate.authorizer());
        assertEquals(0, publisher.submitted(), "未见授权 submit 观察不得占审核票");
        assertNull(gate.visibilityForRequest(fx.requestId(), true).status());
        gate.ingestAiDraft(fx.draft());
        gate.verifyByAuthorizer(fx.authorizer());
        assertEquals(0, publisher.submitted());
        assertNull(gate.visibilityForRequest(fx.requestId(), true).status());
        gate.submitOriginalReview(fx.requestId());
        assertEquals(1, publisher.submitted());
        assertEquals(Phase.ORIGINAL_REVIEW_PENDING, gate.phase());
        assertEquals(ReviewStatus.PENDING, gate.visibilityForRequest(fx.requestId(), true).status());
    }

    @Test
    void unusedInvalidIngestDoesNotOccupyThenUniqueValidIngestApplies() {
        InMemoryNpcFixtures fx = InMemoryNpcFixtures.synthetic();
        MerchantNpcDraftApprovalGate gate = fx.gateWithRecordingPublisher();
        MerchantNpcKnowledgeDraft invalid = MerchantNpcKnowledgeDraft.aiDraft(fx.requestId(),
                List.of(new Product("Tea", "-1", "CNY")));
        assertFalse(invalid.validationIssues().isEmpty());
        assertEquals(Failure.INVALID_DRAFT,
                assertThrows(GateException.class, () -> gate.ingestAiDraft(invalid))
                        .failure());
        assertEquals(Phase.IDLE, gate.phase(), "无效 ingest 观察不得占 phase");
        assertNull(gate.origin(), "无效 ingest 观察不得占 origin");
        assertTrue(gate.draft().isEmpty(), "无效 ingest 观察不得占 draft");
        assertNull(gate.authorizer());
        assertNull(fx.qualification().cachedPublic(fx.scope()));
        gate.ingestAiDraft(fx.draft());
        assertEquals(Phase.AI_DRAFT, gate.phase());
        assertEquals(Origin.AI, gate.origin());
        assertEquals(fx.requestId(), gate.draft().requestId());
        assertFalse(gate.draft().isEmpty());
        assertNull(fx.qualification().cachedPublic(fx.scope()), "合法 ingest 不得占 published 账");
    }

    @Test
    void projectFromDraftQueryMissingPortAndNeverSeenDraftDoNotOccupy() {
        InMemoryNpcFixtures fx = InMemoryNpcFixtures.synthetic();
        InMemoryNpcFixtures.RecordingPublisher publisher = new InMemoryNpcFixtures.RecordingPublisher();
        MerchantNpcDraftApprovalGate missing = new MerchantNpcDraftApprovalGate(
                fx.scope(), publisher, fx.isolation(), fx.qualification(), fx::scope);
        GateException waiting = assertThrows(GateException.class,
                () -> missing.projectFromDraftQuery("c06-draft-never-seen"));
        assertEquals(Failure.WAITING_SOURCE, waiting.failure());
        assertEquals(MerchantNpcDraftApprovalGate.AI_THEME_DRAFT_READ, waiting.capability());
        assertEquals("c06-draft-never-seen", waiting.requestId());
        assertEquals(Phase.IDLE, missing.phase());
        assertNull(missing.published());
        assertNull(missing.requeryPublished("c06-draft-never-seen"));
        assertNull(missing.authorizer());
        assertTrue(missing.draft().isEmpty());
        assertEquals(0, publisher.submitted());
        assertEquals(0, publisher.publishedCount());

        MerchantNpcKnowledgeDraft value = fx.draft();
        PublishedRevision seeded = new PublishedRevision("rev-read-seed", "c06-seed-pub", fx.scope(),
                value.validatedPublicFactsJson(), value.factsHash());
        fx.qualification().rememberPublished(seeded, fx.scope());
        int[] calls = new int[1];
        DraftQuery query = (memberId, requestId) -> {
            calls[0]++;
            assertEquals(Long.valueOf(fx.scope().accountId()), memberId);
            if ("c06-draft-never-seen".equals(requestId)) {
                AiThemeDraftRequestState absent = new AiThemeDraftRequestState();
                absent.setRequestId(requestId);
                absent.setState(AiThemeDraftRequestState.NOT_FOUND);
                return absent;
            }
            if ("c06-draft-seen".equals(requestId)) {
                AiThemePlan plan = new AiThemePlan();
                plan.setTitle("Seen draft");
                AiThemeDraftResp resp = new AiThemeDraftResp();
                resp.setDraft(plan);
                AiThemeDraftRequestState seen = new AiThemeDraftRequestState();
                seen.setRequestId(requestId);
                seen.setState(AiThemeDraftRequestState.SUCCEEDED);
                seen.setResult(resp);
                return seen;
            }
            return null;
        };
        MerchantNpcDraftApprovalGate wired = new MerchantNpcDraftApprovalGate(
                fx.scope(), publisher, fx.isolation(), fx.qualification(), fx::scope, query);
        assertNull(wired.projectFromDraftQuery(null));
        assertNull(wired.projectFromDraftQuery("  "));
        assertEquals(0, calls[0], "空白草稿 id 不得调用读端口");
        assertNull(wired.projectFromDraftQuery("c06-draft-never-seen"));
        assertEquals(1, calls[0]);
        assertArrayEquals(seeded.facts(), fx.isolation().playerProjection(fx.scope(), fx.qualification()),
                "未见草稿 id 不得占 privateScopes");
        assertNull(wired.requeryPublished("c06-draft-never-seen"), "未见草稿 id 不得占 publish 账");
        assertNull(wired.published());
        assertEquals(Phase.IDLE, wired.phase());
        assertNull(wired.origin());
        assertNull(fx.isolation().reviewStatusForRequest("c06-draft-never-seen", true));
        assertEquals(0, publisher.submitted());
        assertEquals(0, publisher.publishedCount());

        AiThemeDraftRequestState seen = wired.projectFromDraftQuery("c06-draft-seen");
        assertEquals(AiThemeDraftRequestState.SUCCEEDED, seen.getState());
        assertEquals("Seen draft", seen.getResult().getDraft().getTitle());
        assertEquals(2, calls[0]);
        assertArrayEquals(seeded.facts(), fx.isolation().playerProjection(fx.scope(), fx.qualification()),
                "已读草稿不得占 privateScopes");
        assertNull(wired.requeryPublished("c06-draft-seen"));
        assertNull(wired.published());
        assertEquals(Phase.IDLE, wired.phase());
        assertEquals(0, publisher.publishedCount());
        assertFalse(wired.draft().encodesLiveCommerceMutation());
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectFromDraftQueryUsesOriginalDraftReadAndDoesNotCallTheModel() {
        InMemoryNpcFixtures fx = InMemoryNpcFixtures.synthetic();
        InMemoryNpcFixtures.RecordingPublisher publisher = new InMemoryNpcFixtures.RecordingPublisher();
        Map<Object, Object> redis = new HashMap<Object, Object>();
        RedisTemplate<Object, Object> template = mock(RedisTemplate.class);
        ValueOperations<Object, Object> values = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        when(values.get(any())).thenAnswer(inv -> redis.get(inv.getArgument(0)));
        IAiCreatorService creator = mock(IAiCreatorService.class);
        AiThemeDraftRequestService drafts = new AiThemeDraftRequestService();
        ReflectionTestUtils.setField(drafts, "aiCreatorService", creator);
        ReflectionTestUtils.setField(drafts, "redisTemplate", template);
        String id = "c06-draft-seen";
        redis.put("ai:theme:draft:request:" + fx.scope().accountId() + ":" + id,
                "{\"state\":\"SUCCEEDED\",\"result\":{\"draft\":{\"title\":\"Seen draft\"}}}");

        MerchantNpcDraftApprovalGate closed = new MerchantNpcDraftApprovalGate(
                fx.scope(), publisher, fx.isolation(), fx.qualification(), fx::scope,
                new MerchantNpcDraftApprovalGate.AiThemeDraftRequestQuery(null));
        GateException waiting = assertThrows(GateException.class, () -> closed.projectFromDraftQuery(id));
        assertEquals(Failure.WAITING_SOURCE, waiting.failure());
        assertEquals(MerchantNpcDraftApprovalGate.AI_THEME_DRAFT_READ, waiting.capability());
        verify(creator, never()).themeDraft(any(), any());

        MerchantNpcDraftApprovalGate gate = new MerchantNpcDraftApprovalGate(
                fx.scope(), publisher, fx.isolation(), fx.qualification(), fx::scope,
                new MerchantNpcDraftApprovalGate.AiThemeDraftRequestQuery(drafts));
        AiThemeDraftRequestState seen = gate.projectFromDraftQuery(id);
        assertEquals(AiThemeDraftRequestState.SUCCEEDED, seen.getState());
        assertEquals("Seen draft", seen.getResult().getDraft().getTitle());
        assertEquals(Phase.IDLE, gate.phase());
        assertNull(gate.published());
        assertEquals(0, publisher.publishedCount());
        assertEquals(AiThemeDraftRequestState.NOT_FOUND, drafts.read(902L, id).getState());
        verify(creator, never()).themeDraft(any(), any());
    }
}
