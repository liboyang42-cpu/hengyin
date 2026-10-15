package com.chengyinhub.business.community;

import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CommunityReportCase;
import com.chengyinhub.business.domain.community.CommunityPostFeedQuery;
import com.chengyinhub.business.domain.dto.CommunityPostRequests;
import com.chengyinhub.business.domain.dto.CommunityReportSubmitRequest;
import com.chengyinhub.business.domain.dto.SafetyDecision;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.service.CommunityTrustService;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICommunityMediaReceiptService;
import com.chengyinhub.business.service.IContentSafetyGate;
import com.chengyinhub.business.service.impl.CommunityPostServiceImpl;
import com.chengyinhub.business.service.support.CommunityPostLifecyclePolicy;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.Collections;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommunityPostServiceImplTest {
    private CommunityPostMapper mapper;
    private IContentSafetyGate safetyGate;
    private CommunityPostServiceImpl service;
    private ICommunityMediaReceiptService mediaReceiptService;
    private CommunityTrustService communityTrustService;

    @BeforeEach
    void setUp() {
        mapper = mock(CommunityPostMapper.class);
        safetyGate = mock(IContentSafetyGate.class);
        mediaReceiptService = mock(ICommunityMediaReceiptService.class);
        communityTrustService = mock(CommunityTrustService.class);
        service = new CommunityPostServiceImpl(mapper, new CommunityPostLifecyclePolicy(), safetyGate,
                mock(IAuditTaskService.class), communityTrustService, mediaReceiptService);
        when(mapper.countActiveMemberEnforcement(anyLong(), anyList(), any())).thenReturn(0);
        when(mapper.countRecentPosts(anyLong(), any())).thenReturn(0);
        when(mediaReceiptService.verify(anyLong(), anyString(), anyLong(), any())).thenReturn(true);
        when(mapper.selectCommandReceipt(anyLong(), anyString())).thenReturn(null);
        when(mapper.insertCommandReceipt(anyLong(), anyString(), anyString(), anyLong(), anyString(), any()))
                .thenReturn(1);
        when(mapper.insertOutbox(anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.insertTypedOutbox(anyString(), anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.countGuidelineVersion(anyLong())).thenReturn(1);
        when(mapper.countGuidelineRule(anyLong(), anyString())).thenReturn(1);
        when(mapper.countNotificationDeliverable(anyLong(), anyLong(), anyLong(), any())).thenReturn(1);
        when(mapper.countMemberNotificationDeliverable(anyLong(), anyLong())).thenReturn(1);
    }

    @Test
    void publishPassMakesPostVisibleAndWritesSearchAndOutbox() {
        CommunityPost post = draft(7L, 9L, 3);
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post);
        when(mapper.selectActiveGuideline()).thenReturn(Collections.<String, Object>singletonMap("ID", 5L));
        when(mapper.countGuidelineAck(9L, 5L)).thenReturn(1);
        when(mapper.selectMedia(7L)).thenReturn(Collections.emptyList());
        when(safetyGate.decide(any())).thenReturn(SafetyDecision.pass());
        when(mapper.transitionCas(eq(7L), eq("DRAFT"), eq("PUBLISHED"), eq(3), any(), eq(null), eq(1), eq(1), eq(1), any())).thenReturn(1);
        when(mapper.upsertSearchDocument(eq(7L), eq("VISIBLE"), any())).thenReturn(1);
        when(mapper.insertOutbox(anyString(), eq(7L), eq("POST_PUBLISHED"), anyString(), any())).thenReturn(1);
        CommunityPost visible = draft(7L, 9L, 4);
        visible.setLifecycle("PUBLISHED");
        when(mapper.selectById(7L, 9L)).thenReturn(visible);
        when(mapper.selectReferences(7L)).thenReturn(Collections.emptyList());
        when(mapper.selectMentionedMemberIds(7L)).thenReturn(Arrays.asList(21L, 9L));

        service.publish(9L, null, 7L, state(3));

        verify(mapper).upsertSearchDocument(eq(7L), eq("VISIBLE"), any());
        verify(mapper).insertOutbox(anyString(), eq(7L), eq("POST_PUBLISHED"), anyString(), any());
        verify(mapper).insertTypedOutbox(anyString(), eq("COMMUNITY_NOTIFICATION"), eq(21L),
                eq("NOTIFICATION_REQUESTED"), anyString(), any());
    }

    @Test
    void publishWithoutCurrentGuidelineAckStopsBeforeSafetyGate() {
        when(mapper.selectByIdForUpdate(7L)).thenReturn(draft(7L, 9L, 3));
        when(mapper.selectActiveGuideline()).thenReturn(Collections.<String, Object>singletonMap("id", 5L));
        when(mapper.countGuidelineAck(9L, 5L)).thenReturn(0);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.publish(9L, null, 7L, state(3)));

        assertEquals(Integer.valueOf(428), error.getCode());
        verify(safetyGate, never()).decide(any());
    }

    @Test
    void editingPublishedPostRequiresCurrentGuidelineAckBeforeReview() {
        CommunityPost published = draft(7L, 9L, 3);
        published.setLifecycle("PUBLISHED");
        when(mapper.selectByIdForUpdate(7L)).thenReturn(published);
        when(mapper.selectActiveGuideline()).thenReturn(Collections.<String, Object>singletonMap("id", 5L));
        when(mapper.countGuidelineAck(9L, 5L)).thenReturn(0);
        CommunityPostRequests.Upsert request = validUpsert();
        request.setExpectedVersion(3);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.edit(9L, 7L, request));

        assertEquals(Integer.valueOf(428), error.getCode());
        verify(mapper, never()).insertRevision(anyLong(), anyLong(), anyInt(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void publishRetryReturnsStoredStateWithoutRunningSafetyAgain() {
        CommunityPost published = draft(7L, 9L, 4);
        published.setLifecycle("PUBLISHED");
        when(mapper.selectByIdForUpdate(7L)).thenReturn(published);
        Map<String, Object> receipt = new HashMap<>();
        receipt.put("command_type", "POST_PUBLISH");
        receipt.put("aggregate_id", 7L);
        when(mapper.selectCommandReceipt(9L, "publish-request-1")).thenReturn(receipt);
        when(mapper.selectById(7L, 9L)).thenReturn(published);
        when(mapper.selectMedia(7L)).thenReturn(Collections.emptyList());
        when(mapper.selectReferences(7L)).thenReturn(Collections.emptyList());

        service.publish(9L, null, 7L, state(3));

        verify(safetyGate, never()).decide(any());
        verify(mapper, never()).transitionCas(anyLong(), anyString(), anyString(), anyInt(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    void publicPostCannotStoreExactLocation() {
        CommunityPostRequests.Upsert request = validUpsert();
        request.setLocationPrecision("EXACT");

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.createDraft(9L, request));

        assertEquals(Integer.valueOf(400), error.getCode());
        verify(mapper, never()).insertPost(any());
    }

    @Test
    void incompleteBodyCanBeSavedAsDraft() {
        CommunityPostRequests.Upsert request = validUpsert();
        request.setBody("   ");
        CommunityPost stored = draft(7L, 9L, 0);
        stored.setBody("");
        when(mapper.insertPost(any())).thenAnswer(invocation -> {
            CommunityPost inserted = invocation.getArgument(0);
            inserted.setId(7L);
            return 1;
        });
        when(mapper.upsertSearchDocument(eq(7L), eq("HIDDEN"), any())).thenReturn(1);
        when(mapper.selectById(7L, 9L)).thenReturn(stored);
        when(mapper.selectMedia(7L)).thenReturn(Collections.emptyList());
        when(mapper.selectReferences(7L)).thenReturn(Collections.emptyList());

        Map<String, Object> result = service.createDraft(9L, request);

        assertEquals(stored, result.get("post"));
    }

    @Test
    void incompleteBodyCannotBePublished() {
        CommunityPost post = draft(7L, 9L, 3);
        post.setBody(" ");
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.publish(9L, null, 7L, state(3)));

        assertEquals(Integer.valueOf(400), error.getCode());
        verify(safetyGate, never()).decide(any());
    }

    @Test
    void accountEnforcementBlocksCreation() {
        when(mapper.countActiveMemberEnforcement(anyLong(), anyList(), any())).thenReturn(1);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.createDraft(9L, validUpsert()));

        assertEquals(Integer.valueOf(403), error.getCode());
        verify(mapper, never()).insertPost(any());
    }

    @Test
    void referenceSnapshotMustBePublicJsonObject() {
        CommunityPostRequests.Upsert request = validUpsert();
        CommunityPostRequests.Reference reference = new CommunityPostRequests.Reference();
        reference.setReferenceType("ACTIVITY");
        reference.setReferenceId(8L);
        reference.setSnapshotJson("[]");
        reference.setPrivacySnapshot("OWNER_ONLY");
        request.setReferences(Collections.singletonList(reference));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.createDraft(9L, request));

        assertEquals(Integer.valueOf(400), error.getCode());
        verify(mapper, never()).insertPost(any());
    }

    @Test
    void authorCannotForgeUnrecognizedSafetyLabels() {
        CommunityPostRequests.Upsert request = validUpsert();
        request.setSafetyLabels(Collections.singletonList("PLATFORM_VERIFIED"));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.createDraft(9L, request));

        assertEquals(Integer.valueOf(400), error.getCode());
        verify(mapper, never()).insertPost(any());
    }

    @Test
    void mediaRegistrationRejectsExecutableOrUntrustedSchemes() {
        CommunityPostRequests.MediaRegister request = new CommunityPostRequests.MediaRegister();
        request.setUploadRequestId("upload-request-1");
        request.setMediaType("IMAGE");
        request.setObjectKey("javascript:alert(1)");
        request.setMimeType("image/jpeg");
        request.setByteSize(123L);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.registerMedia(9L, request));

        assertEquals(Integer.valueOf(400), error.getCode());
        verify(mapper, never()).insertMedia(anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyLong(), any(), any(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    void followingFeedRequiresPrincipalEvenThoughPublicFeedsAllowGuests() {
        CommunityPostFeedQuery query = new CommunityPostFeedQuery();
        query.setMode("FOLLOWING");

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.feed(0L, query));

        assertEquals(Integer.valueOf(401), error.getCode());
        verify(mapper, never()).selectFeed(any());
    }

    @Test
    void publishPassUsesRealMediaUrlAndMakesApprovedMediaPublic() {
        CommunityPost post = draft(7L, 9L, 3);
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post);
        when(mapper.selectActiveGuideline()).thenReturn(Collections.<String, Object>singletonMap("id", 5L));
        when(mapper.countGuidelineAck(9L, 5L)).thenReturn(1);
        Map<String, Object> media = new HashMap<>();
        media.put("derived_object_key", "https://cdn.example/community/image.jpg");
        when(mapper.selectMediaForSafety(7L)).thenReturn(Collections.singletonList(media));
        when(safetyGate.decide(argThat(request -> request.getMediaUrls().equals(
                Collections.singletonList("https://cdn.example/community/image.jpg")))))
                .thenReturn(SafetyDecision.pass());
        when(mapper.transitionCas(eq(7L), eq("DRAFT"), eq("PUBLISHED"), eq(3), any(), eq(null),
                eq(1), eq(1), eq(1), any())).thenReturn(1);
        when(mapper.updateMediaReviewState(eq(7L), eq("PASS"), any())).thenReturn(1);
        when(mapper.upsertSearchDocument(eq(7L), eq("VISIBLE"), any())).thenReturn(1);
        when(mapper.insertOutbox(anyString(), eq(7L), eq("POST_PUBLISHED"), anyString(), any())).thenReturn(1);
        CommunityPost visible = draft(7L, 9L, 4);
        visible.setLifecycle("PUBLISHED");
        when(mapper.selectById(7L, 9L)).thenReturn(visible);
        when(mapper.selectMedia(7L)).thenReturn(Collections.singletonList(media));
        when(mapper.selectReferences(7L)).thenReturn(Collections.emptyList());

        service.publish(9L, null, 7L, state(3));

        verify(mapper).updateMediaReviewState(eq(7L), eq("PASS"), any());
    }

    @Test
    void mediaRequestIdCannotBeReplayedForAnotherObject() {
        CommunityPostRequests.MediaRegister request = new CommunityPostRequests.MediaRegister();
        request.setUploadRequestId("upload-request-1");
        request.setMediaType("IMAGE");
        request.setObjectKey("https://cdn.example/community/new.jpg");
        request.setMimeType("image/jpeg");
        request.setByteSize(123L);
        request.setUploadReceipt("receipt");
        Map<String, Object> existing = new HashMap<>();
        existing.put("media_type", "IMAGE");
        existing.put("derived_object_key", "https://cdn.example/community/old.jpg");
        existing.put("mime_type", "image/jpeg");
        existing.put("byte_size", 123L);
        when(mapper.selectMediaByOwnerRequest(9L, "upload-request-1")).thenReturn(existing);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.registerMedia(9L, request));

        assertEquals(Integer.valueOf(409), error.getCode());
        verify(mapper, never()).insertMedia(anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyLong(), any(), any(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    void uploadReceiptCannotBeConsumedByAnotherRegistration() {
        CommunityPostRequests.MediaRegister request = new CommunityPostRequests.MediaRegister();
        request.setUploadRequestId("another-request");
        request.setMediaType("IMAGE");
        request.setObjectKey("https://cdn.example/community/photo.jpg");
        request.setMimeType("image/jpeg");
        request.setByteSize(123L);
        request.setUploadReceipt("signed-receipt");
        Map<String, Object> consumed = new HashMap<>();
        consumed.put("owner_member_id", 11L);
        when(mapper.selectMediaByReceiptHash(anyString())).thenReturn(consumed);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.registerMedia(9L, request));

        assertEquals(Integer.valueOf(409), error.getCode());
        verify(mapper, never()).insertMedia(anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyLong(), any(), any(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    void blockedCommentNeverTransitionsToPublished() {
        CommunityPost post = draft(7L, 11L, 3);
        post.setLifecycle("PUBLISHED");
        when(mapper.selectById(7L, 9L)).thenReturn(post);
        CommunityPostRequests.Comment request = new CommunityPostRequests.Comment();
        request.setClientRequestId("comment-request-1");
        request.setBody("违规回复");
        Map<String, Object> inserted = new HashMap<>();
        inserted.put("id", 17L);
        inserted.put("post_id", 7L);
        inserted.put("author_id", 9L);
        inserted.put("body", "违规回复");
        inserted.put("author_approval_state", "VISIBLE");
        inserted.put("lifecycle", "CHECKING");
        when(mapper.selectCommentByAuthorRequest(9L, "comment-request-1")).thenReturn(null, inserted);
        when(mapper.insertComment(eq(7L), eq(9L), eq(null), eq(null), eq(null), anyString(),
                eq("违规回复"), eq("CHECKING"), eq("VISIBLE"), any())).thenReturn(1);
        when(safetyGate.decide(argThat(gate -> AuditTask.BIZ_COMMUNITY_POST_COMMENT.equals(gate.getContentType())
                && Long.valueOf(17L).equals(gate.getContentId())))).thenReturn(SafetyDecision.block("POLICY"));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.comment(9L, 7L, request));

        assertEquals(Integer.valueOf(400), error.getCode());
        verify(mapper, never()).updateCommentLifecycle(anyLong(), anyString(), anyString(), anyInt(), any());
        verify(mapper, never()).recomputeCounts(7L);
    }

    @Test
    void deletedCommentCannotBeResurrectedByLateAuditCallback() {
        Map<String, Object> comment = new HashMap<>();
        comment.put("id", 17L);
        comment.put("post_id", 7L);
        comment.put("lifecycle", "DELETED");
        comment.put("version", 4);
        when(mapper.selectCommentForAudit(17L)).thenReturn(comment);

        service.applyCommentAuditDecision(17L, true);

        verify(mapper, never()).updateCommentLifecycle(anyLong(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void maintenanceKeepsPublishedLifecycleWhileCombiningIndependentLimits() {
        CommunityPost post = draft(7L, 9L, 3);
        post.setLifecycle("LIMITED");
        when(mapper.selectEnforcementBoundaryPostIds(any(), anyInt())).thenReturn(Collections.singletonList(7L));
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post);
        when(mapper.selectActivePostEnforcementTypes(eq(7L), any())).thenReturn(
                Arrays.asList("DOWNRANK", "SEARCH_EXCLUDE", "INTERACTION_LIMIT"));
        when(mapper.reconcilePostEnforcementCas(eq(7L), eq(3), eq("PUBLISHED"), eq(0), eq(0), eq(0), any()))
                .thenReturn(1);

        service.runMaintenance(20);

        verify(mapper).reconcilePostEnforcementCas(eq(7L), eq(3), eq("PUBLISHED"), eq(0), eq(0), eq(0), any());
    }

    @Test
    void overlappingEnforcementKeepsOriginalPublishedBaseline() {
        CommunityPost post = draft(7L, 9L, 3);
        post.setLifecycle("HIDDEN");
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post);
        when(mapper.selectActiveEnforcementBaseline(eq(7L), any())).thenReturn("PUBLISHED");
        when(mapper.insertEnforcement(anyLong(), any(), anyString(), anyString(), anyLong(), anyString(),
                anyString(), anyString(), anyLong(), any(), any(), anyString(), any())).thenReturn(1);
        Map<String, Object> stored = new HashMap<>();
        stored.put("id", 21L);
        when(mapper.selectEnforcementByRequest(5L, "enforce-overlap")).thenReturn(stored);
        when(mapper.selectActivePostEnforcementTypes(eq(7L), any()))
                .thenReturn(Arrays.asList("HIDE_PENDING_REVIEW", "REMOVE"));
        when(mapper.reconcilePostEnforcementCas(eq(7L), eq(3), eq("REMOVED"), eq(0), eq(0), eq(0), any()))
                .thenReturn(1);
        CommunityPostRequests.Enforcement request = new CommunityPostRequests.Enforcement();
        request.setEnforcementType("REMOVE");
        request.setRuleCode("SAFETY");
        request.setGuidelineVersionId(5L);
        request.setReasonCode("HARM");
        request.setEvidenceJson("{\"snapshotId\":21}");
        request.setRequestId("enforce-overlap");

        service.enforce(5L, 7L, request);

        verify(mapper).insertEnforcement(eq(7L), any(), eq("REMOVE"), eq("SAFETY"), eq(5L),
                eq("HARM"), eq("{\"snapshotId\":21}"), eq("PUBLISHED"), eq(5L), any(), any(), eq("enforce-overlap"), any());
    }

    @Test
    void memberEnforcementMustExplicitlyTargetPostAuthor() {
        CommunityPost post = draft(7L, 9L, 3);
        post.setLifecycle("PUBLISHED");
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post);
        CommunityPostRequests.Enforcement request = new CommunityPostRequests.Enforcement();
        request.setEnforcementType("PERMANENT_BAN");
        request.setRuleCode("SERIOUS_ABUSE");
        request.setGuidelineVersionId(5L);
        request.setReasonCode("ABUSE");
        request.setEvidenceJson("{\"snapshotId\":22}");
        request.setRequestId("member-ban-1");

        ServiceException missing = assertThrows(ServiceException.class,
                () -> service.enforce(5L, 7L, request));
        assertEquals(Integer.valueOf(400), missing.getCode());

        request.setTargetMemberId(10L);
        request.setRequestId("member-ban-2");
        ServiceException mismatch = assertThrows(ServiceException.class,
                () -> service.enforce(5L, 7L, request));
        assertEquals(Integer.valueOf(400), mismatch.getCode());
        verify(mapper, never()).insertEnforcement(anyLong(), any(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyLong(), any(), any(), anyString(), any());
    }

    @Test
    void enforcementRequiresNonEmptyStructuredEvidence() {
        CommunityPostRequests.Enforcement request = new CommunityPostRequests.Enforcement();
        request.setEnforcementType("REMOVE");
        request.setRuleCode("SAFETY");
        request.setGuidelineVersionId(5L);
        request.setReasonCode("HARM");
        request.setRequestId("enforce-no-evidence");

        ServiceException missing = assertThrows(ServiceException.class,
                () -> service.enforce(5L, 7L, request));
        assertEquals(Integer.valueOf(400), missing.getCode());

        request.setEvidenceJson("{}");
        ServiceException empty = assertThrows(ServiceException.class,
                () -> service.enforce(5L, 7L, request));
        assertEquals(Integer.valueOf(400), empty.getCode());
        verify(mapper, never()).insertEnforcement(anyLong(), any(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyLong(), any(), any(), anyString(), any());
    }

    @Test
    void enforcementRejectsUnknownGuidelineVersion() {
        when(mapper.countGuidelineVersion(99L)).thenReturn(0);
        CommunityPostRequests.Enforcement request = new CommunityPostRequests.Enforcement();
        request.setEnforcementType("REMOVE");
        request.setRuleCode("SAFETY");
        request.setGuidelineVersionId(99L);
        request.setReasonCode("HARM");
        request.setEvidenceJson("{\"snapshotId\":99}");
        request.setRequestId("enforce-unknown-guideline");

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.enforce(5L, 7L, request));

        assertEquals(Integer.valueOf(409), error.getCode());
        verify(mapper, never()).selectByIdForUpdate(anyLong());
    }

    @Test
    void enforcementRejectsRuleOutsideSelectedGuidelineVersion() {
        when(mapper.countGuidelineRule(5L, "MADE_UP_RULE")).thenReturn(0);
        CommunityPostRequests.Enforcement request = new CommunityPostRequests.Enforcement();
        request.setEnforcementType("REMOVE");
        request.setRuleCode("made_up_rule");
        request.setGuidelineVersionId(5L);
        request.setReasonCode("HARM");
        request.setEvidenceJson("{\"snapshotId\":99}");
        request.setRequestId("enforce-unknown-rule");

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.enforce(5L, 7L, request));

        assertEquals(Integer.valueOf(409), error.getCode());
        verify(mapper, never()).selectByIdForUpdate(anyLong());
    }

    @Test
    void enforcementStoresTheSameNormalizedRuleCodeThatWasValidated() {
        CommunityPost post = draft(7L, 9L, 3);
        post.setLifecycle("PUBLISHED");
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post);
        when(mapper.insertEnforcement(eq(7L), eq(9L), eq("REMOVE"), eq("SAFETY"), eq(5L),
                eq("HARM"), anyString(), eq("PUBLISHED"), eq(5L), any(), any(), anyString(), any()))
                .thenReturn(1);
        Map<String, Object> saved = new HashMap<>();
        saved.put("id", 31L);
        when(mapper.selectEnforcementByRequest(5L, "normalize-rule-1")).thenReturn(saved);
        CommunityPostRequests.Enforcement request = new CommunityPostRequests.Enforcement();
        request.setTargetMemberId(9L);
        request.setEnforcementType("remove");
        request.setRuleCode(" safety ");
        request.setGuidelineVersionId(5L);
        request.setReasonCode("HARM");
        request.setEvidenceJson("{\"snapshotId\":99}");
        request.setRequestId("normalize-rule-1");
        request.setStartsAt(new Date(System.currentTimeMillis() + 60_000L));

        service.enforce(5L, 7L, request);

        verify(mapper).countGuidelineRule(5L, "SAFETY");
        verify(mapper).insertEnforcement(eq(7L), eq(9L), eq("REMOVE"), eq("SAFETY"), eq(5L),
                eq("HARM"), anyString(), eq("PUBLISHED"), eq(5L), any(), any(), anyString(), any());
    }

    @Test
    void appealSubmissionWritesGovernanceNotificationAndOutbox() {
        Map<String, Object> enforcement = new HashMap<>();
        enforcement.put("target_member_id", 9L);
        enforcement.put("post_id", 7L);
        when(mapper.selectEnforcement(31L)).thenReturn(enforcement);
        CommunityPostRequests.Appeal request = new CommunityPostRequests.Appeal();
        request.setEnforcementId(31L);
        request.setReason("需要人工复核");
        request.setRequestId("appeal-submit-1");

        service.appeal(9L, request);

        verify(mapper).insertTypedOutbox(anyString(), eq("COMMUNITY_NOTIFICATION"), eq(9L),
                eq("NOTIFICATION_REQUESTED"), anyString(), any());
        verify(mapper).insertOutbox(anyString(), eq(7L), eq("APPEAL_SUBMITTED"), anyString(), any());
    }

    @Test
    void appealReviewNotifiesAppellantAndWritesOutbox() {
        Map<String, Object> appeal = new HashMap<>();
        appeal.put("enforcement_id", 31L);
        appeal.put("appellant_member_id", 9L);
        Map<String, Object> enforcement = new HashMap<>();
        enforcement.put("post_id", 7L);
        enforcement.put("version", 0);
        when(mapper.selectAppeal(41L)).thenReturn(appeal);
        when(mapper.selectEnforcement(31L)).thenReturn(enforcement);
        when(mapper.reviewAppealCas(eq(41L), eq("UPHELD"), anyString(), eq(5L), eq(0), any())).thenReturn(1);
        CommunityPostRequests.AppealReview request = new CommunityPostRequests.AppealReview();
        request.setDecision("UPHELD");
        request.setReason("复核维持");
        request.setExpectedVersion(0);

        service.reviewAppeal(5L, 41L, request);

        verify(mapper).insertTypedOutbox(anyString(), eq("COMMUNITY_NOTIFICATION"), eq(9L),
                eq("NOTIFICATION_REQUESTED"), anyString(), any());
        verify(mapper).insertOutbox(anyString(), eq(7L), eq("APPEAL_REVIEWED"), anyString(), any());
    }

    @Test
    void accountAppealWritesReplayableAccountOutbox() {
        Map<String, Object> enforcement = new HashMap<>();
        enforcement.put("target_member_id", 9L);
        enforcement.put("post_id", null);
        when(mapper.selectEnforcement(31L)).thenReturn(enforcement);
        CommunityPostRequests.Appeal request = new CommunityPostRequests.Appeal();
        request.setEnforcementId(31L);
        request.setReason("账号处置需要复核");
        request.setRequestId("account-appeal-1");

        service.appeal(9L, request);

        verify(mapper).insertTypedOutbox(anyString(), eq("COMMUNITY_MEMBER"), eq(9L),
                eq("ACCOUNT_APPEAL_SUBMITTED"), anyString(), any());
    }

    @Test
    void accountAppealOutboxUpdatesReplayableMemberGovernanceProjection() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 60L);
        row.put("event_id", "event-60");
        row.put("aggregate_type", "COMMUNITY_MEMBER");
        row.put("aggregate_id", 9L);
        row.put("event_type", "ACCOUNT_APPEAL_SUBMITTED");
        row.put("payload_json", "{\"enforcementId\":31}");
        when(mapper.selectDueOutbox(any(), anyInt())).thenReturn(Collections.singletonList(row));
        when(mapper.claimOutbox(eq(60L), any())).thenReturn(1);
        when(mapper.upsertMemberGovernanceProjection(eq(9L), eq("ACCOUNT_APPEAL_SUBMITTED"),
                eq("{\"enforcementId\":31}"), any())).thenReturn(1);

        service.runMaintenance(20);

        verify(mapper).upsertMemberGovernanceProjection(eq(9L), eq("ACCOUNT_APPEAL_SUBMITTED"),
                eq("{\"enforcementId\":31}"), any());
        verify(mapper).completeOutbox(eq(60L), any());
    }

    @Test
    void activeGuidelineUpdateQueuesOneGovernanceNoticePerUnacknowledgedCommunityMember() {
        when(mapper.selectActiveGuideline()).thenReturn(Collections.<String, Object>singletonMap("id", 5L));
        when(mapper.selectGuidelineNoticeRecipients(5L, "null:GUIDELINE_UPDATED:5", 20))
                .thenReturn(Arrays.asList(9L, 10L));

        Map<String, Integer> result = service.runMaintenance(20);

        assertEquals(Integer.valueOf(2), result.get("guidelineNotices"));
        verify(mapper, times(2)).insertTypedOutbox(anyString(), eq("COMMUNITY_NOTIFICATION"), anyLong(),
                eq("NOTIFICATION_REQUESTED"), anyString(), any());
    }

    @Test
    void appealEvidenceAttachmentsFailClosedUntilOwnershipAndRedactionAreConnected() {
        Map<String, Object> enforcement = new HashMap<>();
        enforcement.put("target_member_id", 9L);
        enforcement.put("post_id", 7L);
        when(mapper.selectEnforcement(31L)).thenReturn(enforcement);
        CommunityPostRequests.Appeal request = new CommunityPostRequests.Appeal();
        request.setEnforcementId(31L);
        request.setReason("请复核");
        request.setRequestId("appeal-evidence-1");
        request.setEvidenceAssetIds(Collections.singletonList(71L));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.appeal(9L, request));

        assertEquals(Integer.valueOf(409), error.getCode());
        verify(mapper, never()).insertAppeal(anyLong(), anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void notificationOutboxRechecksVisibilityBeforeDelivery() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 61L);
        row.put("event_id", "event-61");
        row.put("aggregate_type", "COMMUNITY_NOTIFICATION");
        row.put("aggregate_id", 9L);
        row.put("payload_json", "{\"recipientId\":9,\"postId\":7,\"actorId\":8,"
                + "\"commentId\":17,\"notificationType\":\"INTERACTION\",\"aggregateKey\":\"7:REPLY:8:17\"}");
        when(mapper.selectDueOutbox(any(), anyInt())).thenReturn(Collections.singletonList(row));
        when(mapper.claimOutbox(eq(61L), any())).thenReturn(1);
        when(mapper.countNotificationDeliverable(9L, 7L, 8L, 17L)).thenReturn(0);

        service.runMaintenance(20);

        verify(mapper, never()).insertNotification(anyLong(), anyString(), any(), any(), any(), anyString(),
                anyString(), anyString(), any());
        verify(mapper).completeOutbox(eq(61L), any());
    }

    @Test
    void notificationApiReplacesPayloadActorWithTheRelationshipFilteredActor() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 71L);
        row.put("notification_type", "INTERACTION");
        row.put("actor_member_id", 13L);
        row.put("actor_count", 1);
        row.put("payload_json", "{\"action\":\"LIKE\",\"actorId\":12}");
        when(mapper.selectNotifications(9L, null, 20)).thenReturn(Collections.singletonList(row));

        Map<String, Object> result = service.notifications(9L, null, 20);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertEquals(1, items.size());
        assertEquals(true, String.valueOf(items.get(0).get("payload_json")).contains("\"actorId\":13"));
        assertEquals(false, String.valueOf(items.get(0).get("payload_json")).contains("\"actorId\":12"));
    }

    @Test
    void followQueuesSocialNotificationWithoutInventingAPost() {
        service.notifyFollow(9L, 12L);

        verify(mapper).insertTypedOutbox(anyString(), eq("COMMUNITY_NOTIFICATION"), eq(9L),
                eq("NOTIFICATION_REQUESTED"), argThat(payload -> payload.contains("\"action\":\"FOLLOW\"")
                        && payload.contains("\"postId\":null")), any());
    }

    @Test
    void notificationDeliveryFailureMovesOutboxToRetry() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 62L);
        row.put("event_id", "event-62");
        row.put("aggregate_type", "COMMUNITY_NOTIFICATION");
        row.put("aggregate_id", 9L);
        row.put("payload_json", "{\"recipientId\":9,\"postId\":7,\"actorId\":8,"
                + "\"notificationType\":\"INTERACTION\",\"aggregateKey\":\"7:LIKE:8:0\"}");
        when(mapper.selectDueOutbox(any(), anyInt())).thenReturn(Collections.singletonList(row));
        when(mapper.claimOutbox(eq(62L), any())).thenReturn(1);
        when(mapper.countNotificationDeliverable(9L, 7L, 8L, null)).thenReturn(1);
        when(mapper.insertNotification(eq(9L), eq("event-62"), eq(7L), eq(8L), isNull(), eq("INTERACTION"),
                anyString(), anyString(), any())).thenThrow(new RuntimeException("temporary"));

        service.runMaintenance(20);

        verify(mapper).retryOutbox(eq(62L), eq("RuntimeException"), any(), any());
        verify(mapper, never()).completeOutbox(eq(62L), any());
    }

    @Test
    void malformedNotificationOutboxMovesToRetryInsteadOfWritingNullStrings() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 63L);
        row.put("event_id", "event-63");
        row.put("aggregate_type", "COMMUNITY_NOTIFICATION");
        row.put("aggregate_id", 9L);
        row.put("payload_json", "{\"recipientId\":9,\"postId\":7,\"actorId\":8}");
        when(mapper.selectDueOutbox(any(), anyInt())).thenReturn(Collections.singletonList(row));
        when(mapper.claimOutbox(eq(63L), any())).thenReturn(1);

        service.runMaintenance(20);

        verify(mapper).retryOutbox(eq(63L), eq("ServiceException"), any(), any());
        verify(mapper, never()).insertNotification(anyLong(), anyString(), any(), any(), any(), anyString(),
                anyString(), anyString(), any());
        verify(mapper, never()).completeOutbox(eq(63L), any());
    }

    @Test
    void unknownOutboxAggregateTypeMovesToRetry() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 64L);
        row.put("event_id", "event-64");
        row.put("aggregate_type", "UNRECOGNIZED");
        row.put("aggregate_id", 9L);
        row.put("payload_json", "{}");
        when(mapper.selectDueOutbox(any(), anyInt())).thenReturn(Collections.singletonList(row));
        when(mapper.claimOutbox(eq(64L), any())).thenReturn(1);

        service.runMaintenance(20);

        verify(mapper).retryOutbox(eq(64L), eq("ServiceException"), any(), any());
        verify(mapper, never()).completeOutbox(eq(64L), any());
    }

    @Test
    void notificationPreferencesPatchMergesAndGovernanceCannotBeDisabled() {
        Map<String, Object> after = new HashMap<>();
        after.put("interaction_enabled", 1);
        after.put("mention_enabled", 0);
        after.put("social_enabled", 0);
        when(mapper.selectNotificationPreferences(9L)).thenReturn(after);
        when(mapper.upsertNotificationPreferences(eq(9L), isNull(), eq(false), isNull(), any())).thenReturn(1);
        CommunityPostRequests.NotificationPreferences request = new CommunityPostRequests.NotificationPreferences();
        request.setMentionEnabled(false);

        Map<String, Object> result = service.updateNotificationPreferences(9L, request);

        assertEquals(Boolean.FALSE, result.get("mentionEnabled"));
        assertEquals(Boolean.TRUE, result.get("governanceEnabled"));
        verify(mapper).upsertNotificationPreferences(eq(9L), isNull(), eq(false), isNull(), any());
    }

    @Test
    void commentLikeAndUnlikeWritePostScopedOutboxEvents() {
        Map<String, Object> comment = new HashMap<>();
        comment.put("post_id", 7L);
        when(mapper.selectCommentForAudit(17L)).thenReturn(comment);
        when(mapper.selectCommentLikeActionForUpdate(17L, 9L)).thenReturn(null);
        when(mapper.insertCommentAction(eq(17L), eq(9L), eq("like-comment-1"), any())).thenReturn(1);
        when(mapper.deleteCommentAction(17L, 9L)).thenReturn(1);

        service.likeComment(9L, 17L, "like-comment-1");
        service.unlikeComment(9L, 17L, "unlike-comment-1");

        verify(mapper).insertOutbox(anyString(), eq(7L), eq("COMMENT_LIKED"), anyString(), any());
        verify(mapper).insertOutbox(anyString(), eq(7L), eq("COMMENT_UNLIKED"), anyString(), any());
    }

    @Test
    void concurrentCommentLikeReplayDoesNotEmitDuplicateOutbox() {
        Map<String, Object> replay = new HashMap<>();
        replay.put("comment_id", 17L);
        replay.put("request_id", "like-comment-race");
        when(mapper.selectCommentLikeActionForUpdate(17L, 9L))
                .thenReturn(null, replay);
        when(mapper.insertCommentAction(eq(17L), eq(9L), eq("like-comment-race"), any()))
                .thenThrow(new DuplicateKeyException("duplicate request"));

        service.likeComment(9L, 17L, "like-comment-race");

        verify(mapper, never()).recomputeCommentLikes(anyLong());
        verify(mapper, never()).insertOutbox(anyString(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void postMentionsAreVisibleOnlyToTheAuthor() {
        CommunityPost post = draft(7L, 9L, 3);
        post.setLifecycle("PUBLISHED");
        when(mapper.selectById(7L, 8L)).thenReturn(post);
        when(mapper.selectMedia(7L)).thenReturn(Collections.emptyList());
        when(mapper.selectReferences(7L)).thenReturn(Collections.emptyList());
        when(mapper.selectMentionedMemberIds(7L)).thenReturn(Arrays.asList(21L, 22L));

        Map<String, Object> result = service.detail(8L, 7L);

        assertEquals(Collections.emptyList(), result.get("mentions"));
        verify(mapper, never()).selectMentionedMemberIds(7L);
    }

    @Test
    void detailProjectsViewerCommentPermissionFromServerPolicy() {
        CommunityPost post = draft(7L, 9L, 3);
        post.setLifecycle("PUBLISHED");
        post.setCommentPolicy("FOLLOWERS");
        when(mapper.selectById(7L, 8L)).thenReturn(post);
        when(mapper.selectMedia(7L)).thenReturn(Collections.emptyList());
        when(mapper.selectReferences(7L)).thenReturn(Collections.emptyList());
        when(mapper.countFollow(8L, 9L)).thenReturn(1);

        Map<String, Object> allowed = service.detail(8L, 7L);

        assertEquals(Boolean.TRUE, allowed.get("viewerCanComment"));
    }

    @Test
    void detailHidesCommentComposerForRestrictedViewer() {
        CommunityPost post = draft(7L, 9L, 3);
        post.setLifecycle("PUBLISHED");
        when(mapper.selectById(7L, 8L)).thenReturn(post);
        when(mapper.selectMedia(7L)).thenReturn(Collections.emptyList());
        when(mapper.selectReferences(7L)).thenReturn(Collections.emptyList());
        when(mapper.countActiveMemberEnforcement(eq(8L), anyList(), any())).thenReturn(1);

        Map<String, Object> result = service.detail(8L, 7L);

        assertEquals(Boolean.FALSE, result.get("viewerCanComment"));
    }

    @Test
    void anonymousViewerCannotCommentEvenWhenPolicySaysEveryone() {
        CommunityPost post = draft(7L, 9L, 3);
        post.setLifecycle("PUBLISHED");
        when(mapper.selectById(7L, 0L)).thenReturn(post);
        when(mapper.selectMedia(7L)).thenReturn(Collections.emptyList());
        when(mapper.selectReferences(7L)).thenReturn(Collections.emptyList());

        Map<String, Object> result = service.detail(0L, 7L);

        assertEquals(Boolean.FALSE, result.get("viewerCanComment"));
    }

    @Test
    void commentPageCursorUsesOldestRootInsteadOfLastReply() {
        CommunityPost post = draft(7L, 9L, 3);
        post.setLifecycle("PUBLISHED");
        when(mapper.selectById(7L, 9L)).thenReturn(post);
        when(mapper.selectMedia(7L)).thenReturn(Collections.emptyList());
        when(mapper.selectReferences(7L)).thenReturn(Collections.emptyList());
        Map<String, Object> root = new HashMap<>();
        root.put("id", 100L);
        root.put("parent_id", null);
        Map<String, Object> reply = new HashMap<>();
        reply.put("id", 200L);
        reply.put("parent_id", 100L);
        when(mapper.selectComments(7L, 9L, null, 20)).thenReturn(Arrays.asList(root, reply));

        Map<String, Object> page = service.comments(9L, 7L, null, 20);

        assertEquals(100L, page.get("nextCursor"));
    }

    @Test
    void featuredCommandReplayDoesNotCreateDuplicateCurationEntry() {
        CommunityPost post = draft(7L, 9L, 3);
        post.setLifecycle("PUBLISHED");
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post);
        when(mapper.insertFeatured(eq(7L), eq("CITY_PICK"), eq("SH"), eq("城市精选"), eq(5L), any(), any(), any()))
                .thenReturn(1);
        AtomicReference<String> storedCommandType = new AtomicReference<>();
        when(mapper.insertCommandReceipt(eq(5L), eq("feature-request-1"), anyString(), eq(7L), anyString(), any()))
                .thenAnswer(invocation -> {
                    storedCommandType.set(invocation.getArgument(2));
                    return 1;
                });
        when(mapper.selectCommandReceipt(5L, "feature-request-1")).thenAnswer(invocation -> {
            if (storedCommandType.get() == null) return null;
            Map<String, Object> receipt = new HashMap<>();
            receipt.put("command_type", storedCommandType.get());
            receipt.put("aggregate_id", 7L);
            return receipt;
        });
        CommunityPostRequests.Featured request = new CommunityPostRequests.Featured();
        request.setCollectionCode("CITY_PICK");
        request.setCityCode("SH");
        request.setReason("城市精选");
        request.setRequestId("feature-request-1");

        service.feature(5L, 7L, request);
        service.feature(5L, 7L, request);

        verify(mapper, times(1)).insertFeatured(eq(7L), eq("CITY_PICK"), eq("SH"), eq("城市精选"),
                eq(5L), any(), any(), any());
    }

    @Test
    void commentReportTargetsTheCommentRatherThanItsPost() {
        Map<String, Object> comment = new HashMap<>();
        comment.put("id", 17L);
        comment.put("post_id", 7L);
        comment.put("author_id", 11L);
        comment.put("lifecycle", "PUBLISHED");
        when(mapper.selectCommentForAudit(17L)).thenReturn(comment);
        when(mapper.selectCommentForReply(17L, 7L, 9L)).thenReturn(comment);
        CommunityPost post = draft(7L, 12L, 1);
        post.setLifecycle("PUBLISHED");
        when(mapper.selectById(7L, 9L)).thenReturn(post);
        CommunityReportSubmitRequest request = new CommunityReportSubmitRequest();
        request.setTargetType("COMMENT");
        request.setTargetId(17L);
        request.setReasonCode("HARASSMENT");
        request.setRequestId("report-request-1");
        when(communityTrustService.submitReport(9L, request)).thenReturn(new CommunityReportCase());

        service.report(9L, request);

        assertEquals("COMMENT", request.getTargetType());
        assertEquals(Long.valueOf(17L), request.getTargetId());
        verify(communityTrustService).submitReport(9L, request);
    }

    @Test
    void hiddenCommentCannotBeReportedByGuessingItsId() {
        Map<String, Object> comment = new HashMap<>();
        comment.put("id", 17L);
        comment.put("post_id", 7L);
        comment.put("author_id", 11L);
        comment.put("lifecycle", "CHECKING");
        when(mapper.selectCommentForAudit(17L)).thenReturn(comment);
        CommunityReportSubmitRequest request = new CommunityReportSubmitRequest();
        request.setTargetType("COMMENT");
        request.setTargetId(17L);
        request.setReasonCode("HARASSMENT");
        request.setRequestId("report-hidden-comment");

        ServiceException error = assertThrows(ServiceException.class, () -> service.report(9L, request));

        assertEquals(Integer.valueOf(404), error.getCode());
        verify(communityTrustService, never()).submitReport(anyLong(), any());
    }

    @Test
    void muteRequestIdCannotBeReusedForAnotherTarget() {
        Map<String, Object> replay = new HashMap<>();
        replay.put("muted_member_id", 15L);
        when(mapper.selectMuteByRequest(9L, "mute-request-1")).thenReturn(replay);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.mute(9L, 16L, "mute-request-1"));

        assertEquals(Integer.valueOf(409), error.getCode());
        verify(mapper, never()).insertMute(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void communityAudienceRequiresMembershipAndCommunityId() {
        CommunityPostRequests.Upsert missing = validUpsert();
        missing.setAudience("COMMUNITY");
        ServiceException missingError = assertThrows(ServiceException.class,
                () -> service.createDraft(9L, missing));
        assertEquals(Integer.valueOf(400), missingError.getCode());

        CommunityPostRequests.Upsert outsider = validUpsert();
        outsider.setAudience("COMMUNITY");
        outsider.setCommunityId(88L);
        when(mapper.countCommunityMember(9L, 88L)).thenReturn(0);
        ServiceException outsiderError = assertThrows(ServiceException.class,
                () -> service.createDraft(9L, outsider));
        assertEquals(Integer.valueOf(403), outsiderError.getCode());
        verify(mapper, never()).insertPost(any());
    }

    @Test
    void restrictedCommentPoliciesRequireTheirScope() {
        CommunityPostRequests.Upsert members = validUpsert();
        members.setCommentPolicy("MEMBERS");
        ServiceException missingCommunity = assertThrows(ServiceException.class,
                () -> service.createDraft(9L, members));
        assertEquals(Integer.valueOf(400), missingCommunity.getCode());

        CommunityPostRequests.Upsert mentioned = validUpsert();
        mentioned.setCommentPolicy("MENTIONED");
        ServiceException missingMention = assertThrows(ServiceException.class,
                () -> service.createDraft(9L, mentioned));
        assertEquals(Integer.valueOf(400), missingMention.getCode());
        verify(mapper, never()).insertPost(any());
    }

    @Test
    void publicLocationRequiresCityCode() {
        CommunityPostRequests.Upsert request = validUpsert();
        request.setLocationPrecision("CITY");

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.createDraft(9L, request));

        assertEquals(Integer.valueOf(400), error.getCode());
        verify(mapper, never()).insertPost(any());
    }

    @Test
    void noneLocationRejectsResidualPublicLocationFields() {
        CommunityPostRequests.Upsert request = validUpsert();
        request.setCityCode("SH");
        request.setPoiName("苏河湾");
        request.setApproximateGeohash("wtw3s");

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.createDraft(9L, request));

        assertEquals(Integer.valueOf(400), error.getCode());
        verify(mapper, never()).insertPost(any());
    }

    @Test
    void approximateLocationRequiresCoarseValidGrid() {
        CommunityPostRequests.Upsert request = validUpsert();
        request.setLocationPrecision("APPROXIMATE");
        request.setCityCode("SH");
        request.setApproximateGeohash("not-a-grid");

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.createDraft(9L, request));

        assertEquals(Integer.valueOf(400), error.getCode());
        verify(mapper, never()).insertPost(any());
    }

    @Test
    void locationWithdrawalUsesVersionedErasureQueue() {
        CommunityPostRequests.State request = new CommunityPostRequests.State();
        request.setExpectedVersion(3);
        request.setRequestId("location-withdraw-1");
        when(mapper.withdrawPostLocation(eq(7L), eq(9L), eq(3), any())).thenReturn(1);
        when(mapper.insertErasureJob(eq(9L), eq(7L), eq("LOCATION_WITHDRAW"),
                eq("location-withdraw-1"), anyString(), any())).thenReturn(1);

        service.withdrawLocation(9L, 7L, request);

        verify(mapper).withdrawPostLocation(eq(7L), eq(9L), eq(3), any());
        verify(mapper).insertErasureJob(eq(9L), eq(7L), eq("LOCATION_WITHDRAW"),
                eq("location-withdraw-1"), anyString(), any());
    }

    @Test
    void erasureDoesNotDeleteObjectStillReferencedByAnotherLiveMediaRow() {
        Map<String, Object> job = new HashMap<>();
        job.put("id", 31L);
        job.put("post_id", 7L);
        job.put("trigger_type", "POST_DELETE");
        job.put("legal_hold", 0);
        Map<String, Object> media = new HashMap<>();
        media.put("id", 41L);
        media.put("object_key", "https://cdn.example/upload/shared.jpg");
        when(mapper.selectDueErasureJobs(any(), anyInt())).thenReturn(Collections.singletonList(job));
        when(mapper.claimErasureJob(eq(31L), any())).thenReturn(1);
        when(mapper.selectDueMediaForErasure(eq(7L), any())).thenReturn(Collections.singletonList(media));
        when(mapper.countLiveMediaObjectRefs("https://cdn.example/upload/shared.jpg", 41L)).thenReturn(1);

        service.runMaintenance(20);

        verify(mapper).deletePostNotificationActors(7L);
        verify(mapper).deletePostNotifications(7L);
        verify(mapper).eraseDueMedia(eq(7L), any());
        verify(mapper).completeErasureJob(eq(31L), any());
    }

    private CommunityPost draft(Long id, Long authorId, int version) {
        CommunityPost post = new CommunityPost();
        post.setId(id);
        post.setAuthorId(authorId);
        post.setBody("正文");
        post.setAudience("PUBLIC");
        post.setCommentPolicy("EVERYONE");
        post.setLocationPrecision("NONE");
        post.setLifecycle("DRAFT");
        post.setInteractionEnabled(1);
        post.setGuidelineVersionId(5L);
        post.setVersion(version);
        return post;
    }

    private CommunityPostRequests.State state(int version) {
        CommunityPostRequests.State state = new CommunityPostRequests.State();
        state.setExpectedVersion(version);
        state.setRequestId("publish-request-1");
        return state;
    }

    private CommunityPostRequests.Upsert validUpsert() {
        CommunityPostRequests.Upsert request = new CommunityPostRequests.Upsert();
        request.setClientRequestId("create-request-1");
        request.setPostType("MOMENT");
        request.setBody("正文");
        request.setAudience("PUBLIC");
        request.setCommentPolicy("EVERYONE");
        request.setLocationPrecision("NONE");
        request.setDisclosureType("NONE");
        request.setGuidelineVersionId(5L);
        return request;
    }
}
