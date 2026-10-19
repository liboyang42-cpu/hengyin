package com.chengyinhub.business.community;

import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CommunityReportCase;
import com.chengyinhub.business.domain.vo.CommunityReportVO;
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
import com.chengyinhub.common.utils.JsonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.Collections;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
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
    void editingCheckingPostRefreshesReviewWithSavedVersionAndLifecycle() {
        CommunityPost checking = draft(7L, 9L, 3);
        checking.setLifecycle("CHECKING");
        when(mapper.selectByIdForUpdate(7L)).thenReturn(checking);
        when(mapper.selectById(7L, 9L)).thenReturn(checking);
        when(mapper.selectActiveGuideline()).thenReturn(Collections.<String, Object>singletonMap("id", 5L));
        when(mapper.countGuidelineAck(9L, 5L)).thenReturn(1);
        when(mapper.updateContentCas(any(), eq(3), eq("CHECKING"), any())).thenReturn(1);
        CommunityPostRequests.Upsert request = validUpsert();
        request.setExpectedVersion(3);
        request.setMediaIds(Arrays.asList(41L, 42L));
        when(mapper.attachMedia(eq(7L), eq(9L), anyLong(), anyInt(), any())).thenReturn(1);
        service.edit(9L, 7L, request);
        verify(mapper).detachMedia(eq(7L), eq(9L), eq(Arrays.asList(41L, 42L)), any());

        IAuditTaskService audits = (IAuditTaskService) org.springframework.test.util.ReflectionTestUtils
                .getField(service, "auditTaskService");
        verify(audits).submitSafetyHold(eq(AuditTask.BIZ_COMMUNITY_POST), eq(7L), eq(9L),
                eq("EDIT_REVIEW"), argThat(snapshot -> {
                    CommunityPost saved = JsonUtils.parseObject(snapshot, CommunityPost.class);
                    return Integer.valueOf(4).equals(saved.getVersion()) && "CHECKING".equals(saved.getLifecycle())
                            && request.getBody().equals(saved.getBody());
                }));
        verify(mapper).upsertSearchDocument(eq(7L), eq("HIDDEN"), any());
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
    void legacyMigrationLabelSurvivesEditingWithOrWithoutClientEcho() {
        for (boolean echo : Arrays.asList(true, false)) {
            setUp();
            CommunityPost current = draft(7L, 9L, 3);
            current.setSafetyLabelsJson("[\"LEGACY_MIGRATED\"]");
            when(mapper.selectByIdForUpdate(7L)).thenReturn(current);
            when(mapper.selectById(7L, 9L)).thenReturn(current);
            when(mapper.updateContentCas(any(), eq(3), eq("DRAFT"), any())).thenReturn(1);
            CommunityPostRequests.Upsert request = validUpsert();
            request.setExpectedVersion(3);
            request.setSafetyLabels(echo ? Arrays.asList("LEGACY_MIGRATED", "WEATHER_RISK")
                    : Collections.singletonList("WEATHER_RISK"));

            service.edit(9L, 7L, request);

            verify(mapper).updateContentCas(argThat(post -> {
                List<?> labels = JsonUtils.parseObject(post.getSafetyLabelsJson(), List.class);
                return labels.size() == 2 && labels.contains("LEGACY_MIGRATED") && labels.contains("WEATHER_RISK");
            }), eq(3), eq("DRAFT"), any());
        }
    }

    @Test
    void migrationLabelCannotBeForgedOnCreateOrUnmigratedEdit() {
        CommunityPostRequests.Upsert request = validUpsert();
        request.setExpectedVersion(3);
        request.setSafetyLabels(Collections.singletonList("LEGACY_MIGRATED"));
        assertEquals(Integer.valueOf(400), assertThrows(ServiceException.class,
                () -> service.createDraft(9L, request)).getCode());
        CommunityPost current = draft(7L, 9L, 3);
        current.setSafetyLabelsJson("[]");
        when(mapper.selectByIdForUpdate(7L)).thenReturn(current);
        assertEquals(Integer.valueOf(400), assertThrows(ServiceException.class,
                () -> service.edit(9L, 7L, request)).getCode());
        verify(mapper, never()).insertPost(any());
        verify(mapper, never()).updateContentCas(any(), anyInt(), anyString(), any());
    }

    @Test
    void migratedEditKeepsInvalidDuplicateAndMergedLabelLimits() {
        for (List<String> labels : Arrays.asList(
                Collections.singletonList("PLATFORM_VERIFIED"),
                Arrays.asList("LEGACY_MIGRATED", "LEGACY_MIGRATED"),
                Arrays.asList("DANGEROUS_ACTIVITY", "SENSITIVE_CONTENT", "FLASHING_IMAGES", "SPOILER", "WEATHER_RISK"),
                Arrays.asList("LEGACY_MIGRATED", "DANGEROUS_ACTIVITY", "SENSITIVE_CONTENT", "FLASHING_IMAGES", "SPOILER", "WEATHER_RISK"))) {
            setUp();
            CommunityPost current = draft(7L, 9L, 3);
            current.setSafetyLabelsJson("[\"LEGACY_MIGRATED\"]");
            when(mapper.selectByIdForUpdate(7L)).thenReturn(current);
            CommunityPostRequests.Upsert request = validUpsert();
            request.setExpectedVersion(3);
            request.setSafetyLabels(labels);
            assertEquals(Integer.valueOf(400), assertThrows(ServiceException.class,
                    () -> service.edit(9L, 7L, request)).getCode());
            verify(mapper, never()).insertRevision(anyLong(), anyLong(), anyInt(), anyString(), anyString(), anyString(), any());
            verify(mapper, never()).updateContentCas(any(), anyInt(), anyString(), any());
        }
    }

    @Test
    void migratedEditDoesNotRelaxOwnerOrVersionChecks() {
        for (boolean wrongOwner : Arrays.asList(true, false)) {
            setUp();
            CommunityPost current = draft(7L, 9L, 3);
            current.setSafetyLabelsJson("[\"LEGACY_MIGRATED\"]");
            when(mapper.selectByIdForUpdate(7L)).thenReturn(current);
            CommunityPostRequests.Upsert request = validUpsert();
            request.setExpectedVersion(wrongOwner ? 3 : 2);
            request.setSafetyLabels(Collections.singletonList("LEGACY_MIGRATED"));
            assertThrows(ServiceException.class, () -> service.edit(wrongOwner ? 10L : 9L, 7L, request));
            verify(mapper, never()).updateContentCas(any(), anyInt(), anyString(), any());
        }
    }

    @Test
    void corruptStoredSafetyLabelsCannotBeOverwrittenAsEmpty() {
        CommunityPost current = draft(7L, 9L, 3);
        current.setSafetyLabelsJson("{broken");
        when(mapper.selectByIdForUpdate(7L)).thenReturn(current);
        CommunityPostRequests.Upsert request = validUpsert();
        request.setExpectedVersion(3);
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class,
                () -> service.edit(9L, 7L, request)).getCode());
        verify(mapper, never()).updateContentCas(any(), anyInt(), anyString(), any());
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
    void duplicateCommentRecoversCommittedWinnerWithCurrentRead() {
        CommunityPost post = draft(7L, 9L, 3);
        post.setLifecycle("PUBLISHED");
        when(mapper.selectById(7L, 9L)).thenReturn(post);
        CommunityPostRequests.Comment request = new CommunityPostRequests.Comment();
        request.setClientRequestId("comment-concurrent");
        request.setBody("并发回复");
        Map<String, Object> winner = new HashMap<>();
        winner.put("post_id", 7L);
        winner.put("body", "并发回复");
        winner.put("author_approval_state", "VISIBLE");
        winner.put("lifecycle", "PUBLISHED");
        when(mapper.selectCommentByAuthorRequest(9L, "comment-concurrent")).thenReturn(null);
        when(mapper.insertComment(eq(7L), eq(9L), isNull(), isNull(), isNull(),
                eq("comment-concurrent"), eq("并发回复"), eq("CHECKING"), eq("VISIBLE"), any()))
                .thenThrow(new DuplicateKeyException("committed winner"));
        when(mapper.selectCommentByAuthorRequestForUpdate(9L, "comment-concurrent")).thenReturn(winner);

        Map<String, Object> result = service.comment(9L, 7L, request);

        assertEquals("PUBLISHED", result.get("lifecycle"));
        assertEquals("VISIBLE", result.get("approvalState"));
        verify(mapper, times(1)).selectCommentByAuthorRequest(9L, "comment-concurrent");
        verify(mapper).selectCommentByAuthorRequestForUpdate(9L, "comment-concurrent");
        verify(safetyGate, never()).decide(any());
        verify(mapper, never()).recomputeCounts(anyLong());
        verify(mapper, never()).insertOutbox(anyString(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void duplicateCommentCurrentReadStillRejectsChangedBodyOrPost() {
        CommunityPost post = draft(7L, 9L, 3);
        post.setLifecycle("PUBLISHED");
        when(mapper.selectById(7L, 9L)).thenReturn(post);
        CommunityPostRequests.Comment request = new CommunityPostRequests.Comment();
        request.setClientRequestId("comment-conflict");
        request.setBody("请求正文");
        when(mapper.insertComment(eq(7L), eq(9L), isNull(), isNull(), isNull(),
                eq("comment-conflict"), eq("请求正文"), eq("CHECKING"), eq("VISIBLE"), any()))
                .thenThrow(new DuplicateKeyException("committed winner"));
        for (boolean differentPost : Arrays.asList(false, true)) {
            Map<String, Object> winner = new HashMap<>();
            winner.put("post_id", differentPost ? 8L : 7L);
            winner.put("body", differentPost ? "请求正文" : "不同正文");
            when(mapper.selectCommentByAuthorRequestForUpdate(9L, "comment-conflict")).thenReturn(winner);
            ServiceException error = assertThrows(ServiceException.class,
                    () -> service.comment(9L, 7L, request));
            assertEquals(Integer.valueOf(409), error.getCode());
        }
        verify(safetyGate, never()).decide(any());
        verify(mapper, never()).recomputeCounts(anyLong());
        verify(mapper, never()).insertOutbox(anyString(), anyLong(), anyString(), anyString(), any());
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
        Map<String, Object> before = new HashMap<>();
        before.put("interaction_enabled", 1);
        before.put("mention_enabled", 1);
        before.put("social_enabled", 0);
        Map<String, Object> after = new HashMap<>();
        after.put("interaction_enabled", 1);
        after.put("mention_enabled", 0);
        after.put("social_enabled", 0);
        when(mapper.selectNotificationPreferences(9L)).thenReturn(before, after);
        when(mapper.upsertNotificationPreferences(eq(9L), eq(true), eq(false), eq(false), any())).thenReturn(1);
        CommunityPostRequests.NotificationPreferences request = new CommunityPostRequests.NotificationPreferences();
        request.setMentionEnabled(false);

        Map<String, Object> result = service.updateNotificationPreferences(9L, request);

        assertEquals(Boolean.FALSE, result.get("mentionEnabled"));
        assertEquals(Boolean.TRUE, result.get("governanceEnabled"));
        verify(mapper).upsertNotificationPreferences(eq(9L), eq(true), eq(false), eq(false), any());
    }

    @Test
    void commentLikeAndUnlikeWritePostScopedOutboxEvents() {
        Map<String, Object> comment = new HashMap<>();
        comment.put("post_id", 7L);
        when(mapper.selectCommentForAudit(17L)).thenReturn(comment);
        when(mapper.selectCommentActionByRequest(9L, "like-comment-1")).thenReturn(null);
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
        when(mapper.selectCommentActionByRequest(9L, "like-comment-race"))
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
        when(mapper.selectComments(7L, 9L, null, 21)).thenReturn(Arrays.asList(root, reply));

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
        when(communityTrustService.submitReport(9L, request)).thenReturn(submittedReport("COMMENT", 17L));

        Object result = service.report(9L, request);

        assertEquals("COMMENT", request.getTargetType());
        assertEquals(Long.valueOf(17L), request.getTargetId());
        assertReporterProjection(result, "COMMENT", 17L);
        verify(communityTrustService).submitReport(9L, request);
    }

    @Test
    void contentReportSubmissionUsesTheSamePublicReporterProjection() {
        CommunityPost post = draft(7L, 11L, 1);
        post.setLifecycle("PUBLISHED");
        when(mapper.selectById(7L, 9L)).thenReturn(post);
        CommunityReportSubmitRequest request = new CommunityReportSubmitRequest();
        request.setTargetId(7L);
        request.setRequestId("report-content-request");
        when(communityTrustService.submitReport(9L, request)).thenReturn(submittedReport("CONTENT", 7L));

        Object result = service.report(9L, request);

        assertEquals("CONTENT", request.getTargetType());
        assertReporterProjection(result, "CONTENT", 7L);
        verify(communityTrustService).submitReport(9L, request);
    }

    private CommunityReportCase submittedReport(String targetType, Long targetId) {
        CommunityReportCase row = new CommunityReportCase();
        row.setId(23L);
        row.setReporterMemberId(9L);
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setStage("SUBMITTED");
        row.setVersion(0);
        row.setPolicyVersion("COMMUNITY_REPORT_POLICY_V1");
        row.setDescription("举报说明");
        row.setRequestId("internal-request-marker");
        row.setPayloadHash("internal-payload-digest");
        row.setPrioritySnapshot("URGENT");
        row.setFirstResponseMinutesSnapshot(15);
        return row;
    }

    private void assertReporterProjection(Object result, String targetType, Long targetId) {
        assertEquals(CommunityReportVO.class, result.getClass());
        Map<String, Object> json = com.alibaba.fastjson2.JSON.parseObject(
                com.alibaba.fastjson2.JSON.toJSONString(result));
        assertEquals(23L, ((Number) json.get("id")).longValue());
        assertEquals(targetType, json.get("targetType"));
        assertEquals(targetId.longValue(), ((Number) json.get("targetId")).longValue());
        assertEquals("SUBMITTED", json.get("stage"));
        assertEquals("RECEIVED", json.get("publicStage"));
        assertEquals(0, json.get("version"));
        assertEquals("举报说明", json.get("description"));
        for (String internal : Arrays.asList("reporterMemberId", "requestId", "payloadHash",
                "prioritySnapshot", "firstResponseMinutesSnapshot", "internalNote", "operatorId")) {
            assertEquals(false, json.containsKey(internal), internal);
        }
    }

    @Test
    void deletedCommentAncestorsAreRedactedWithoutDroppingNormalRepliesOrChangingRootCursor() {
        when(mapper.selectById(7L, 9L)).thenReturn(draft(7L, 9L, 3));
        Map<String, Object> root30 = deletedThreadRow(30L, null, null);
        Map<String, Object> middle31 = deletedThreadRow(31L, 30L, 30L);
        Map<String, Object> reply32 = threadRow(32L, 31L); reply32.put("root_id", 30L);
        reply32.put("body", "normal reply"); reply32.put("lifecycle", "PUBLISHED");
        Map<String, Object> deletedLeaf33 = deletedThreadRow(33L, 30L, 30L);
        Map<String, Object> root20 = threadRow(20L, null);
        when(mapper.selectComments(7L, 9L, null, 2))
                .thenReturn(Arrays.asList(root30, middle31, reply32, deletedLeaf33, root20));
        Map<String, Object> first = service.comments(9L, 7L, null, 1);
        List<Map<String, Object>> items = (List<Map<String, Object>>) first.get("items");
        assertEquals(3, items.size());
        for (int i = 0; i < 2; i++) {
            assertEquals("", items.get(i).get("body"));
            assertEquals(true, items.get(i).get("deletedPlaceholder"));
            assertFalse(items.get(i).containsKey("BODY"));
        }
        assertEquals(reply32, items.get(2));
        assertEquals("private deleted body", root30.get("BODY"), "projection must not mutate mapper rows");
        assertEquals(true, first.get("hasMore"));
        assertEquals(30L, first.get("nextCursor"));
        when(mapper.selectComments(7L, 9L, 30L, 2)).thenReturn(Collections.singletonList(root20));
        assertEquals(Collections.singletonList(root20), service.comments(9L, 7L, 30L, 1).get("items"));
    }

    @Test
    void deletedLeavesAndEmptyDeletedThreadsDoNotBecomeVisiblePlaceholders() {
        when(mapper.selectById(7L, 9L)).thenReturn(draft(7L, 9L, 3));
        Map<String, Object> deletedRoot = deletedThreadRow(30L, null, null);
        Map<String, Object> deletedChild = deletedThreadRow(31L, 30L, 30L);
        Map<String, Object> normalRoot = threadRow(20L, null);
        when(mapper.selectComments(7L, 9L, null, 2))
                .thenReturn(Arrays.asList(deletedRoot, deletedChild, normalRoot));
        Map<String, Object> page = service.comments(9L, 7L, null, 1);
        assertEquals(Collections.singletonList(normalRoot), page.get("items"));
        assertEquals(false, page.get("hasMore"));
        assertEquals(20L, page.get("nextCursor"));
    }

    private Map<String, Object> deletedThreadRow(Long id, Long parentId, Long rootId) {
        Map<String, Object> row = threadRow(id, parentId);
        row.put("root_id", rootId);
        row.put("lifecycle", "DELETED");
        row.put("BODY", "private deleted body");
        return row;
    }

    private Map<String, Object> threadRow(Long id, Long parentId) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("parent_id", parentId);
        return row;
    }

    @Test
    void deletedPlaceholderCannotBeReportedByGuessingItsId() {
        Map<String, Object> comment = deletedThreadRow(17L, null, null);
        comment.put("post_id", 7L);
        comment.put("author_id", 11L);
        when(mapper.selectCommentForAudit(17L)).thenReturn(comment);
        CommunityReportSubmitRequest request = new CommunityReportSubmitRequest();
        request.setTargetType("COMMENT");
        request.setTargetId(17L);
        request.setReasonCode("HARASSMENT");
        request.setRequestId("report-deleted-placeholder");
        ServiceException error = assertThrows(ServiceException.class, () -> service.report(9L, request));
        assertEquals(Integer.valueOf(404), error.getCode());
        verify(communityTrustService, never()).submitReport(anyLong(), any());
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

    @Test
    void completedOwnDeletedCreateReceiptReturnsOnlyTerminalFlag() {
        CommunityPostRequests.Upsert request = validUpsert();
        Map<String, Object> receipt = captureCreateReceipt(request);
        CommunityPost deleted = draft(7L, 9L, 1);
        deleted.setLifecycle("DELETED");
        when(mapper.selectCommandReceipt(9L, request.getClientRequestId())).thenReturn(receipt);
        when(mapper.selectByAuthorRequest(9L, request.getClientRequestId())).thenReturn(deleted);
        when(mapper.countAuthorDeleteEvidence(9L, 7L)).thenReturn(1);
        clearInvocations(mapper);

        assertEquals(Map.of("intentDiscarded", true), service.createDraft(9L, request));

        verify(mapper, never()).selectById(anyLong(), anyLong());
        verify(mapper, never()).insertPost(any());
        verify(mapper, never()).insertCommandReceipt(anyLong(), anyString(), anyString(), anyLong(), anyString(), any());
        verify(mapper, never()).insertOutbox(anyString(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void nonDeletedCreateReplaysNeverReturnDeletedTerminalFlag() {
        CommunityPostRequests.Upsert request = validUpsert();
        Map<String, Object> receipt = captureCreateReceipt(request);
        when(mapper.selectCommandReceipt(9L, request.getClientRequestId())).thenReturn(receipt);
        for (String lifecycle : List.of("DRAFT", "CHECKING", "PUBLISHED", "LIMITED", "HIDDEN", "REMOVED")) {
            CommunityPost existing = draft(7L, 9L, 1);
            existing.setLifecycle(lifecycle);
            when(mapper.selectByAuthorRequest(9L, request.getClientRequestId())).thenReturn(existing);
            when(mapper.selectById(7L, 9L)).thenReturn(existing);
            Map<String, Object> result = service.createDraft(9L, request);
            assertFalse(result.containsKey("intentDiscarded"));
        }
        verify(mapper, never()).countAuthorDeleteEvidence(anyLong(), anyLong());
        verify(mapper, times(1)).insertPost(any());
    }

    @Test
    void deletedCreateReplayWithoutVerifiedAuthorDeletionEvidenceNeverDiscards() {
        CommunityPostRequests.Upsert request = validUpsert();
        Map<String, Object> receipt = captureCreateReceipt(request);
        CommunityPost deleted = draft(7L, 9L, 1);
        deleted.setLifecycle("DELETED");
        when(mapper.selectCommandReceipt(9L, request.getClientRequestId())).thenReturn(receipt);
        when(mapper.selectByAuthorRequest(9L, request.getClientRequestId())).thenReturn(deleted);
        when(mapper.selectById(7L, 9L)).thenReturn(null);
        // 0 covers absent/partial deletion evidence, copyright removal, deregistration and legal hold.
        when(mapper.countAuthorDeleteEvidence(9L, 7L)).thenReturn(0);
        clearInvocations(mapper);
        assertEquals(Integer.valueOf(404), assertThrows(ServiceException.class,
                () -> service.createDraft(9L, request)).getCode());
        verify(mapper).countAuthorDeleteEvidence(9L, 7L);
        verify(mapper, never()).insertPost(any());

        CommunityPost otherOwner = draft(7L, 10L, 1);
        otherOwner.setLifecycle("DELETED");
        CommunityPost otherAggregate = draft(8L, 9L, 1);
        otherAggregate.setLifecycle("DELETED");
        for (CommunityPost candidate : Arrays.asList(otherOwner, otherAggregate, null)) {
            when(mapper.selectByAuthorRequest(9L, request.getClientRequestId())).thenReturn(candidate);
            assertEquals(Integer.valueOf(404), assertThrows(ServiceException.class,
                    () -> service.createDraft(9L, request)).getCode());
        }
        verify(mapper, times(1)).countAuthorDeleteEvidence(anyLong(), anyLong());
    }

    private Map<String, Object> captureCreateReceipt(CommunityPostRequests.Upsert request) {
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        when(mapper.insertPost(any())).thenAnswer(invocation -> {
            CommunityPost inserted = invocation.getArgument(0);
            inserted.setId(7L);
            return 1;
        });
        when(mapper.selectById(7L, 9L)).thenReturn(draft(7L, 9L, 0));
        when(mapper.insertCommandReceipt(eq(9L), eq(request.getClientRequestId()), eq("POST_CREATE"),
                eq(7L), anyString(), any())).thenAnswer(invocation -> {
            Map<String, Object> receipt = new HashMap<>();
            receipt.put("member_id", 9L);
            receipt.put("request_id", request.getClientRequestId());
            receipt.put("command_type", "POST_CREATE");
            receipt.put("aggregate_id", 7L);
            receipt.put("result_json", invocation.getArgument(4));
            captured.set(receipt);
            return 1;
        });
        service.createDraft(9L, request);
        return captured.get();
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
    @Test
    void completedReferenceFilterUsesBothPublicSnapshotRulesWithoutTruncation() {
        List<Map<String, Object>> completed = new java.util.ArrayList<>();
        for (long id = 1; id <= 52; id++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dataType", id % 2 == 0 ? 2 : 1);
            row.put("dataId", id); row.put("completed", true);
            completed.add(row);
            when(mapper.selectReferenceSnapshot(id % 2 == 0 ? "TOPIC" : "ACTIVITY", id, 9L))
                    .thenReturn(Collections.singletonMap("reference_id", id));
        }
        assertEquals(completed, service.filterPublicCompletedReferences(9L, completed));
        when(mapper.selectReferenceSnapshot("ACTIVITY", 1L, 9L)).thenReturn(null);
        when(mapper.selectReferenceSnapshot("TOPIC", 2L, 9L)).thenReturn(Collections.emptyMap());
        List<Map<String, Object>> filtered = service.filterPublicCompletedReferences(9L, completed);
        assertEquals(completed.subList(2, 52), filtered);
        assertEquals(52, completed.size());
        assertSame(completed.get(2), filtered.get(0));
        verify(mapper, never()).selectReferenceOptions(anyString(), anyLong(), anyInt());
    }

    @Test
    void completedReferenceFilterRejectsUnknownTypesAndMissingIdentity() {
        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("dataType", 3); unknown.put("dataId", 1L);
        assertTrue(service.filterPublicCompletedReferences(9L, Collections.singletonList(unknown)).isEmpty());
        unknown.put("dataType", "OTHER");
        assertTrue(service.filterPublicCompletedReferences(9L, Collections.singletonList(unknown)).isEmpty());
        unknown.put("dataType", 1);
        assertTrue(service.filterPublicCompletedReferences(null, Collections.singletonList(unknown)).isEmpty());
        assertTrue(service.filterPublicCompletedReferences(0L, Collections.singletonList(unknown)).isEmpty());
        verify(mapper, never()).selectReferenceSnapshot(anyString(), anyLong(), anyLong());
    }

    @Test
    void unavailableReferenceStillRejectedByExistingPostWriteGate() {
        CommunityPostRequests.Upsert request = validUpsert();
        CommunityPostRequests.Reference reference = new CommunityPostRequests.Reference();
        reference.setReferenceType("ACTIVITY");
        reference.setReferenceId(63L);
        request.setReferences(Collections.singletonList(reference));
        when(mapper.insertPost(any())).thenReturn(1);
        ServiceException failure = assertThrows(ServiceException.class, () -> service.createDraft(9L, request));
        assertEquals(Integer.valueOf(409), failure.getCode());
        verify(mapper).selectReferenceSnapshot("ACTIVITY", 63L, 9L);
        verify(mapper, never()).insertReference(any(), any(), any(), any(), any(), any());
    }

}
