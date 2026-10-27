package com.chengyinhub.business.community;

import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.domain.dto.CommunityPostRequests;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.service.CommunityTrustService;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICommunityMediaReceiptService;
import com.chengyinhub.business.service.IContentSafetyGate;
import com.chengyinhub.business.service.impl.CommunityMediaReceiptServiceImpl;
import com.chengyinhub.business.service.impl.CommunityPostServiceImpl;
import com.chengyinhub.business.service.support.CommunityPostLifecyclePolicy;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommunityPrivateMediaContractTest {
    private static final String KEY = "upload/0123456789abcdef0123456789abcdef.png";
    private static final String LEGACY = "https://private-test.oss-cn-shanghai.aliyuncs.com/" + KEY
            + "?Expires=1&Signature=expired-preview";
    private static final String PREVIEW = "https://private-test.oss-cn-shanghai.aliyuncs.com/" + KEY
            + "?Expires=300&Signature=current-preview";
    private CommunityPostMapper mapper;
    private ICommunityMediaReceiptService receipts;
    private CommunityPostServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(CommunityPostMapper.class);
        // A missing MyBatis selectOne row is null; Mockito's default empty Map is not a database row.
        when(mapper.selectMediaByOwnerRequest(anyLong(), anyString())).thenReturn(null);
        when(mapper.selectMediaByReceiptHash(anyString())).thenReturn(null);
        receipts = spy(new CommunityMediaReceiptServiceImpl("private-media-unit-test"));
        service = new CommunityPostServiceImpl(mapper, new CommunityPostLifecyclePolicy(),
                mock(IContentSafetyGate.class), mock(IAuditTaskService.class),
                mock(CommunityTrustService.class), receipts);
    }

    @Test
    void ownerReadRenewsLegacyPreviewWithoutChangingStableIdOrStoredReference() {
        ownerPost();
        Map<String, Object> stored = media(LEGACY);
        when(mapper.selectMedia(7L)).thenReturn(Collections.singletonList(stored));
        try (MockedStatic<FileOssUploadUtils> storage = signer()) {
            storage.when(() -> FileOssUploadUtils.generateCommunityMediaPresignedUrl(KEY))
                    .thenReturn(PREVIEW, PREVIEW + "-renewed");
            Map<?, ?> first = firstMedia(service.detail(9L, 7L));
            Map<?, ?> renewed = firstMedia(service.detail(9L, 7L));
            assertEquals(13L, first.get("id"));
            assertEquals(first.get("id"), renewed.get("id"));
            assertEquals(PREVIEW, first.get("derived_object_key"));
            assertEquals(PREVIEW + "-renewed", renewed.get("derived_object_key"));
            assertEquals(LEGACY, stored.get("derived_object_key"));
            assertEquals(LEGACY, stored.get("object_key"));
            storage.verify(() -> FileOssUploadUtils.generatePresignedUrl(anyString()), never());
        }
    }


    @Test
    void camelOnlyAndMixedCaseMediaAliasesCannotRetainTheOriginalBearerUrl() {
        ownerPost();
        for (boolean camelOnly : new boolean[] {true, false}) {
            Map<String, Object> stored = media(LEGACY);
            if (camelOnly) {
                stored.remove("object_key");
                stored.remove("derived_object_key");
            }
            stored.put("objectKey", LEGACY);
            stored.put("derivedObjectKey", LEGACY);
            stored.put("DERIVED_OBJECT_KEY", LEGACY);
            when(mapper.selectMedia(7L)).thenReturn(Collections.singletonList(stored));
            try (MockedStatic<FileOssUploadUtils> storage = signer()) {
                Map<?, ?> rendered = firstMedia(service.detail(9L, 7L));
                assertEquals(KEY, rendered.get("object_key"));
                assertEquals(PREVIEW, rendered.get("derived_object_key"));
                assertFalse(rendered.containsKey("objectKey"));
                assertFalse(rendered.containsKey("derivedObjectKey"));
                assertFalse(rendered.containsKey("DERIVED_OBJECT_KEY"));
                assertFalse(rendered.containsValue(LEGACY));
                assertEquals(LEGACY, stored.get("derivedObjectKey"));
            }
        }
    }

    @Test
    void foreignPrivatePostCannotMintAMediaSignature() {
        when(mapper.selectById(7L, 12L)).thenReturn(null);
        try (MockedStatic<FileOssUploadUtils> storage = mockStatic(FileOssUploadUtils.class)) {
            assertEquals(Integer.valueOf(404), assertThrows(ServiceException.class,
                    () -> service.detail(12L, 7L)).getCode());
            verify(mapper, never()).selectMedia(anyLong());
            storage.verifyNoInteractions();
        }
    }

    @Test
    void emptyAuthorizedMediaProjectionDoesNotMintAnySignature() {
        ownerPost();
        when(mapper.selectMedia(7L)).thenReturn(Collections.emptyList());
        try (MockedStatic<FileOssUploadUtils> storage = mockStatic(FileOssUploadUtils.class)) {
            assertEquals(Collections.emptyList(), service.detail(9L, 7L).get("media"));
            storage.verifyNoInteractions();
        }
    }

    @Test
    void newClientRegistersCanonicalIdentityWithOriginalOwnerAndSizeReceipt() {
        CommunityPostRequests.MediaRegister request = request(KEY);
        request.setUploadReceipt(receipts.issue(9L, KEY, 106));
        when(mapper.selectMediaByOwnerRequest(9L, request.getUploadRequestId()))
                .thenReturn(null, media(KEY));
        try (MockedStatic<FileOssUploadUtils> storage = signer()) {
            Map<String, Object> registered = service.registerMedia(9L, request);
            assertEquals(13L, registered.get("id"));
            assertEquals(KEY, registered.get("object_key"));
            assertEquals(PREVIEW, registered.get("derived_object_key"));
            verify(receipts).verify(9L, KEY, 106L, request.getUploadReceipt());
            verifyCanonicalInsert();
        }
    }

    @Test
    void oldPendingUrlReceiptIsVerifiedAgainstOriginalWireBeforeCanonicalStorage() {
        CommunityPostRequests.MediaRegister request = request(LEGACY);
        request.setUploadReceipt(receipts.issue(9L, LEGACY, 106));
        when(mapper.selectMediaByOwnerRequest(9L, request.getUploadRequestId()))
                .thenReturn(null, media(KEY));
        try (MockedStatic<FileOssUploadUtils> storage = signer()) {
            storage.when(() -> FileOssUploadUtils.communityMediaObjectKey(LEGACY)).thenAnswer(call -> {
                verify(receipts).verify(9L, LEGACY, 106L, request.getUploadReceipt());
                return KEY;
            });
            assertEquals(13L, service.registerMedia(9L, request).get("id"));
            assertEquals(LEGACY, request.getObjectKey());
            verify(receipts, never()).verify(9L, KEY, 106L, request.getUploadReceipt());
            verifyCanonicalInsert();
        }
    }

    @Test
    void oldClientCanRegisterNewPreviewUrlUsingTheNewCanonicalReceipt() {
        CommunityPostRequests.MediaRegister request = request(PREVIEW);
        request.setUploadReceipt(receipts.issue(9L, KEY, 106));
        when(mapper.selectMediaByOwnerRequest(9L, request.getUploadRequestId()))
                .thenReturn(null, media(KEY));
        try (MockedStatic<FileOssUploadUtils> storage = signer()) {
            storage.when(() -> FileOssUploadUtils.communityMediaObjectKey(PREVIEW)).thenReturn(KEY);
            assertEquals(13L, service.registerMedia(9L, request).get("id"));
            verify(receipts).verify(9L, PREVIEW, 106L, request.getUploadReceipt());
            verify(receipts).verify(9L, KEY, 106L, request.getUploadReceipt());
            assertEquals(PREVIEW, request.getObjectKey());
            verifyCanonicalInsert();
        }
    }

    @Test
    void committedLegacyRegistrationReplaysSameIdWithoutNewReceiptOrStorageWrite() {
        CommunityPostRequests.MediaRegister request = request(LEGACY);
        request.setUploadReceipt("expired-original-receipt");
        when(mapper.selectMediaByOwnerRequest(9L, request.getUploadRequestId())).thenReturn(media(LEGACY));
        try (MockedStatic<FileOssUploadUtils> storage = signer()) {
            assertEquals(13L, service.registerMedia(9L, request).get("id"));
            verify(receipts, never()).verify(anyLong(), anyString(), anyLong(), any());
            verifyNoInsert();
            assertEquals(LEGACY, request.getObjectKey());
        }
    }

    @Test
    void canonicalFallbackCannotChangeReceiptOwnerOrStoredByteSize() {
        for (boolean wrongOwner : new boolean[] {true, false}) {
            CommunityPostRequests.MediaRegister request = request(PREVIEW);
            request.setUploadReceipt(receipts.issue(wrongOwner ? 12L : 9L, KEY, wrongOwner ? 106 : 107));
            try (MockedStatic<FileOssUploadUtils> storage = signer()) {
                storage.when(() -> FileOssUploadUtils.communityMediaObjectKey(PREVIEW)).thenReturn(KEY);
                assertEquals(Integer.valueOf(403), assertThrows(ServiceException.class,
                        () -> service.registerMedia(9L, request)).getCode());
                storage.verify(() -> FileOssUploadUtils.generateCommunityMediaPresignedUrl(anyString()), never());
            }
        }
        verifyNoInsert();
    }

    @Test
    void sameRequestCannotBeReusedForAnotherCanonicalObject() {
        String other = "upload/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png";
        CommunityPostRequests.MediaRegister request = request(other);
        when(mapper.selectMediaByOwnerRequest(9L, request.getUploadRequestId())).thenReturn(media(KEY));
        try (MockedStatic<FileOssUploadUtils> storage = signer()) {
            storage.when(() -> FileOssUploadUtils.communityMediaObjectKey(other)).thenReturn(other);
            assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class,
                    () -> service.registerMedia(9L, request)).getCode());
            storage.verify(() -> FileOssUploadUtils.generateCommunityMediaPresignedUrl(anyString()), never());
            verifyNoInsert();
        }
    }

    @Test
    void canonicalReceiptDoesNotAllowAForeignHostToReachTheSigner() {
        CommunityPostRequests.MediaRegister request = request("https://foreign.example/" + KEY);
        request.setUploadReceipt(receipts.issue(9L, KEY, 106));
        try (MockedStatic<FileOssUploadUtils> storage = signer()) {
            storage.when(() -> FileOssUploadUtils.communityMediaObjectKey(request.getObjectKey()))
                    .thenThrow(new IllegalArgumentException("untrusted source"));
            assertEquals(Integer.valueOf(400), assertThrows(ServiceException.class,
                    () -> service.registerMedia(9L, request)).getCode());
            storage.verify(() -> FileOssUploadUtils.generateCommunityMediaPresignedUrl(anyString()), never());
            verifyNoInsert();
        }
    }

    @Test
    void failedShortSignatureNeverFallsBackToTheStoredLongBearerUrl() {
        ownerPost();
        Map<String, Object> stored = media(LEGACY);
        when(mapper.selectMedia(7L)).thenReturn(Collections.singletonList(stored));
        try (MockedStatic<FileOssUploadUtils> storage = signer()) {
            storage.when(() -> FileOssUploadUtils.generateCommunityMediaPresignedUrl(KEY))
                    .thenThrow(new IllegalStateException("storage unavailable"));
            assertThrows(IllegalStateException.class, () -> service.detail(9L, 7L));
            assertEquals(LEGACY, stored.get("derived_object_key"));
            storage.verify(() -> FileOssUploadUtils.generatePresignedUrl(anyString()), never());
        }
    }

    /** P-52/G-3:一张认不出来源的历史媒体只跳过这一张,同帖其余媒体照常读出,整页不报错。 */
    @Test
    void unrecognisedStoredMediaIsSkippedInsteadOfFailingTheWholePost() {
        ownerPost();
        String foreign = "https://foreign.invalid/legacy/photo.png";
        Map<String, Object> broken = media(foreign);
        broken.put("id", 12L);
        when(mapper.selectMedia(7L)).thenReturn(java.util.Arrays.asList(broken, media(LEGACY)));
        try (MockedStatic<FileOssUploadUtils> storage = signer()) {
            storage.when(() -> FileOssUploadUtils.communityMediaObjectKey(foreign))
                    .thenThrow(new IllegalArgumentException("社区媒体来源无效"));
            List<?> media = (List<?>) service.detail(9L, 7L).get("media");
            assertEquals(1, media.size());
            assertEquals(13L, ((Map<?, ?>) media.get(0)).get("id"));
            assertEquals(PREVIEW, ((Map<?, ?>) media.get(0)).get("derived_object_key"));
        }
    }

    private MockedStatic<FileOssUploadUtils> signer() {
        MockedStatic<FileOssUploadUtils> storage = mockStatic(FileOssUploadUtils.class);
        storage.when(() -> FileOssUploadUtils.communityMediaObjectKey(KEY)).thenReturn(KEY);
        storage.when(() -> FileOssUploadUtils.communityMediaObjectKey(LEGACY)).thenReturn(KEY);
        storage.when(() -> FileOssUploadUtils.generateCommunityMediaPresignedUrl(KEY)).thenReturn(PREVIEW);
        return storage;
    }

    private void ownerPost() {
        CommunityPost post = new CommunityPost();
        post.setId(7L); post.setAuthorId(9L); post.setLifecycle("DRAFT");
        post.setAudience("PRIVATE"); post.setCommentPolicy("OFF");
        when(mapper.selectById(7L, 9L)).thenReturn(post);
    }

    private Map<String, Object> media(String reference) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 13L); row.put("owner_member_id", 9L);
        row.put("media_type", "IMAGE"); row.put("object_key", reference);
        row.put("derived_object_key", reference); row.put("mime_type", "image/png");
        row.put("byte_size", 106L); row.put("review_state", "PENDING");
        return row;
    }

    private CommunityPostRequests.MediaRegister request(String reference) {
        CommunityPostRequests.MediaRegister request = new CommunityPostRequests.MediaRegister();
        request.setUploadRequestId("upload-request-214"); request.setMediaType("IMAGE");
        request.setObjectKey(reference); request.setMimeType("image/png"); request.setByteSize(106L);
        return request;
    }

    private Map<?, ?> firstMedia(Map<String, Object> detail) {
        return (Map<?, ?>) ((List<?>) detail.get("media")).get(0);
    }

    private void verifyCanonicalInsert() {
        verify(mapper).insertMedia(eq(9L), eq("upload-request-214"), eq("IMAGE"), eq(KEY), eq("image/png"),
                eq(106L), any(), any(), any(), any(), anyString(), anyString(), any());
    }

    private void verifyNoInsert() {
        verify(mapper, never()).insertMedia(anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyLong(), any(), any(), any(), any(), anyString(), anyString(), any());
    }
}
