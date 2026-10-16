package com.chengyinhub.business.community;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.CommunityPostServiceImpl;
import com.chengyinhub.business.service.support.CommunityPostLifecyclePolicy;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommunityAuditMediaPreviewTest {
    private static final String KEY = "upload/0123456789abcdef0123456789abcdef.png";
    private static final String OLD = "https://test.oss-cn-shanghai.aliyuncs.com/" + KEY + "?Expires=1&Signature=old";
    private static final String FRESH = "https://test.oss-cn-shanghai.aliyuncs.com/" + KEY + "?Expires=300&Signature=fresh";
    private CommunityPostMapper mapper;
    private IAuditTaskService audits;
    private CommunityPostServiceImpl service;
    private AuditTask task;
    private CommunityPost post;
    private Map<String, Object> media;

    @BeforeEach void setUp() {
        mapper = mock(CommunityPostMapper.class);
        audits = mock(IAuditTaskService.class);
        service = new CommunityPostServiceImpl(mapper, new CommunityPostLifecyclePolicy(),
                mock(IContentSafetyGate.class), audits, mock(CommunityTrustService.class),
                mock(ICommunityMediaReceiptService.class));
        task = new AuditTask(); task.setId(30L); task.setVersion(2); task.setBizId(7L);
        task.setBizType(AuditTask.BIZ_COMMUNITY_POST); task.setSubmitterId(9L); task.setStatus(0);
        post = new CommunityPost(); post.setId(7L); post.setAuthorId(9L); post.setVersion(4);
        post.setLifecycle("CHECKING"); post.setBody("frozen words");
        media = new LinkedHashMap<>(); media.put("id", 13L); media.put("object_key", OLD);
        media.put("derived_object_key", OLD);
        snapshot("DRAFT", 3);
        when(audits.selectAuditTaskById(30L)).thenReturn(task);
        when(mapper.selectById(7L, 9L)).thenReturn(post);
        when(mapper.selectMediaForSafety(7L)).thenReturn(Collections.singletonList(media));
    }

    @Test void publishSnapshotRenewsOnlyItsOriginalBoundMediaWithoutMutation() {
        String original = task.getSnapshotJson();
        try (MockedStatic<FileOssUploadUtils> storage = signer()) {
            Map<String, Object> preview = service.auditMediaPreview(task);
            assertEquals("READY", preview.get("status"));
            assertEquals(30L, preview.get("taskId")); assertEquals(2, preview.get("taskVersion"));
            assertEquals(7L, preview.get("postId")); assertEquals(4, preview.get("postVersion"));
            assertEquals(300, preview.get("expiresInSeconds"));
            Map<?, ?> item = (Map<?, ?>) ((List<?>) preview.get("items")).get(0);
            assertEquals(new HashSet<>(Arrays.asList("id", "url")), item.keySet());
            assertEquals(13L, item.get("id")); assertEquals(FRESH, item.get("url"));
            assertEquals(original, task.getSnapshotJson()); assertEquals(OLD, media.get("derived_object_key"));
            verify(mapper, never()).selectByIdForUpdate(7L);
        }
    }

    @Test void newEditSnapshotUsesTheSavedCheckingVersionWithoutAddingOne() {
        snapshot("CHECKING", 4);
        try (MockedStatic<FileOssUploadUtils> storage = signer()) {
            assertEquals("READY", service.auditMediaPreview(task).get("status"));
        }
    }

    @Test void oldPostOnlyEditSnapshotHasNoInventedHistoricalMediaBinding() {
        task.setSnapshotJson("{\"id\":7,\"authorId\":9,\"version\":4,\"body\":\"frozen words\"}");
        try (MockedStatic<FileOssUploadUtils> storage = signer()) {
            assertEmpty("UNAVAILABLE");
            storage.verify(() -> FileOssUploadUtils.generateShortLivedPresignedUrl(KEY, 300), never());
        }
    }

    @Test void changedPostVersionOrAuthorOrBodyCannotReplaceFrozenEvidence() {
        try (MockedStatic<FileOssUploadUtils> storage = signer()) {
            post.setVersion(5); assertEmpty("STALE"); post.setVersion(4);
            post.setAuthorId(10L); assertEmpty("STALE"); post.setAuthorId(9L);
            post.setBody("new content"); assertEmpty("STALE");
            storage.verify(() -> FileOssUploadUtils.generateShortLivedPresignedUrl(KEY, 300), never());
        }
    }

    @Test void changedTaskVersionCannotMintAnOldPreview() {
        AuditTask newer = new AuditTask(); newer.setVersion(3);
        when(audits.selectAuditTaskById(30L)).thenReturn(newer);
        try (MockedStatic<FileOssUploadUtils> storage = signer()) {
            assertEmpty("STALE"); storage.verifyNoInteractions();
        }
    }

    @Test void differentSnapshotPostOrAuthorIsUnavailable() {
        try (MockedStatic<FileOssUploadUtils> storage = signer()) {
            String original = task.getSnapshotJson();
            task.setSnapshotJson(original.replace("\"id\":7", "\"id\":8")); assertEmpty("UNAVAILABLE");
            task.setSnapshotJson(original.replace("\"authorId\":9", "\"authorId\":10")); assertEmpty("UNAVAILABLE");
            storage.verify(() -> FileOssUploadUtils.generateShortLivedPresignedUrl(KEY, 300), never());
        }
    }

    @Test void changedOrDetachedMediaCannotMintAnyPreview() {
        try (MockedStatic<FileOssUploadUtils> storage = signer()) {
            media.put("id", 14L); assertEmpty("STALE");
            media.put("id", 13L); media.put("object_key", "upload/ffffffffffffffffffffffffffffffff.png");
            storage.when(() -> FileOssUploadUtils.communityMediaObjectKey("upload/ffffffffffffffffffffffffffffffff.png"))
                    .thenReturn("upload/ffffffffffffffffffffffffffffffff.png");
            assertEmpty("STALE");
            when(mapper.selectMediaForSafety(7L)).thenReturn(Collections.emptyList()); assertEmpty("STALE");
            storage.verify(() -> FileOssUploadUtils.generateShortLivedPresignedUrl(KEY, 300), never());
        }
    }

    @Test void foreignSnapshotUrlCannotBeSignedEvenWithACurrentMediaId() {
        task.setSnapshotJson(task.getSnapshotJson().replace(OLD, "https://foreign.invalid/image.png"));
        try (MockedStatic<FileOssUploadUtils> storage = signer()) {
            storage.when(() -> FileOssUploadUtils.communityMediaObjectKey("https://foreign.invalid/image.png"))
                    .thenThrow(new IllegalArgumentException("untrusted"));
            assertEmpty("UNAVAILABLE");
            storage.verify(() -> FileOssUploadUtils.generateShortLivedPresignedUrl(KEY, 300), never());
        }
    }

    @Test void signerFailureKeepsTheTaskReadableWithoutAnyLongUrlFallback() {
        try (MockedStatic<FileOssUploadUtils> storage = signer()) {
            storage.when(() -> FileOssUploadUtils.generateShortLivedPresignedUrl(KEY, 300))
                    .thenThrow(new IllegalStateException("unavailable"));
            assertEmpty("UNAVAILABLE");
            assertTrue(task.getSnapshotJson().contains("frozen words"));
        }
    }

    @Test void responseSanitizationPreservesTextAndRecordsWithoutMutatingTheSnapshot() {
        JSONObject snapshot = JSON.parseObject(task.getSnapshotJson());
        snapshot.getJSONObject("post").put("body", "body includes " + OLD);
        snapshot.getJSONArray("media").getJSONObject(0).put("derivedObjectKey", OLD);
        String original = snapshot.toJSONString();
        try (MockedStatic<FileOssUploadUtils> storage = signer()) {
            JSONObject projected = JSON.parseObject(service.auditSnapshotForRead(original));
            assertEquals("body includes " + OLD, projected.getJSONObject("post").getString("body"));
            JSONObject row = projected.getJSONArray("media").getJSONObject(0);
            assertEquals(KEY, row.getString("object_key"));
            assertEquals(KEY, row.getString("derivedObjectKey"));
            assertEquals(KEY, row.getString("derived_object_key"));
            assertTrue(original.contains("Signature=old"));
            storage.verify(() -> FileOssUploadUtils.generateShortLivedPresignedUrl(KEY, 300), never());
        }
    }

    private void assertEmpty(String status) {
        Map<String, Object> result = service.auditMediaPreview(task);
        assertEquals(status, result.get("status")); assertEquals(Collections.emptyList(), result.get("items"));
    }
    private void snapshot(String lifecycle, int version) {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("id", 7L); expected.put("authorId", 9L); expected.put("version", version);
        expected.put("lifecycle", lifecycle); expected.put("body", "frozen words");
        Map<String, Object> snapshot = new LinkedHashMap<>(); snapshot.put("post", expected);
        snapshot.put("media", Collections.singletonList(new LinkedHashMap<>(media)));
        task.setSnapshotJson(JSON.toJSONString(snapshot));
    }
    private MockedStatic<FileOssUploadUtils> signer() {
        MockedStatic<FileOssUploadUtils> storage = mockStatic(FileOssUploadUtils.class);
        storage.when(() -> FileOssUploadUtils.communityMediaObjectKey(OLD)).thenReturn(KEY);
        storage.when(() -> FileOssUploadUtils.communityMediaObjectKey(KEY)).thenReturn(KEY);
        storage.when(() -> FileOssUploadUtils.generateShortLivedPresignedUrl(KEY, 300)).thenReturn(FRESH);
        return storage;
    }
}
