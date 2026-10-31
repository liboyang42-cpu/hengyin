package com.chengyinhub.business.service.support;

import com.chengyinhub.business.mapper.AppMediaAssetMapper;
import com.chengyinhub.business.service.impl.AppMediaAssetService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppMediaReferenceGuardTest {
    private AppMediaReferenceGuard guard;
    private AppMediaAssetMapper mapper;
    private AppMediaAssetService assets;
    private static final String KEY = "upload/0123456789abcdef0123456789abcdef.glb";
    @BeforeEach void setup() {
        guard = new AppMediaReferenceGuard(); mapper = mock(AppMediaAssetMapper.class); assets = mock(AppMediaAssetService.class);
        ReflectionTestUtils.setField(guard, "mapper", mapper); ReflectionTestUtils.setField(guard, "assets", assets);
        when(assets.approvedUrl(7L)).thenReturn("approved-storage-url");
        when(assets.ownerPreview(7L, 11L)).thenReturn("owned-short-preview");
    }
    private void state(String state) {
        Map<String,Object> row = Map.of("id",7L,"memberId",11L,"objectKey",KEY,"reviewState",state,"ownerType","APP");
        when(mapper.select(7L)).thenReturn(row); when(mapper.selectByObjectKey(KEY)).thenReturn(row);
    }
    @Test void draftStoresCanonicalIdAndPublicAttachmentRequiresRealApprovedState() {
        for (String state : new String[]{"HOLD","REJECTED","UNAVAILABLE"}) {
            state(state);
            assertEquals("media-asset:7", guard.requireAttachment(11L,"media-asset:7",false));
            assertThrows(ServiceException.class, () -> guard.requireAttachment(11L,"media-asset:7",true));
        }
        state("APPROVED");assertEquals("media-asset:7",guard.requireAttachment(11L,"media-asset:7",true));
    }
    @Test void heldOrRejectedMediaIsAuthorOnlyAndNeverGetsPublicUrl() {
        for(String state:new String[]{"HOLD","REJECTED","UNAVAILABLE"}) {
            state(state);
            assertEquals("",guard.visibleUrl("media-asset:7",null));
            assertEquals("",guard.visibleUrl("media-asset:7",22L));
            assertEquals("owned-short-preview",guard.visibleUrl("media-asset:7",11L));
        }
        verify(assets,never()).approvedUrl(anyLong());
    }
    @Test void approvedMediaResolvesRealStorageRatherThanReturningExternalReference() {
        state("APPROVED");
        assertEquals("approved-storage-url",guard.visibleUrl("https://foreign.invalid/"+KEY+"?Signature=untrusted",null));
        assertEquals("media-asset:7",guard.requireAttachment(11L,"https://foreign.invalid/"+KEY,false));
        assertEquals("media-asset:7",guard.requireAttachment(22L,"media-asset:7",false));
    }
    @Test void unknownLegacyTrustedDomainAndMalformedReferencesDoNotGainApproval() {
        for(String ref:new String[]{"https://www.chengyinhub.com/prod-api/profile/legacy.png", "https://foreign.invalid/unreviewed.mp3", "media-asset:9999999999999999999", "https://host/upload/%2e%2e/foo.glb", "https://user@host/"+KEY,"https://host/"+KEY+"#other"}) {
            assertEquals("",guard.visibleUrl(ref,11L));
            assertThrows(ServiceException.class,()->guard.requireAttachment(11L,ref,false));
        }
        verifyNoInteractions(assets);
    }
    @Test void systemActorIdCannotBecomeAppPrivateAuthorButApprovedLocalMediaCanBeReused() {
        String key="/profile/upload/2026/10/12/0123456789abcdef0123456789abcdef.png";
        Map<String,Object> held=Map.of("id",7L,"memberId",11L,"objectKey",key,"reviewState","HOLD","ownerType","SYSTEM");
        when(mapper.select(7L)).thenReturn(held);
        assertEquals("",guard.visibleUrl("media-asset:7",11L));
        assertThrows(ServiceException.class,()->guard.requireAttachment(11L,"media-asset:7",false));
        Map<String,Object> approved=Map.of("id",7L,"memberId",11L,"objectKey",key,"reviewState","APPROVED","ownerType","SYSTEM");
        when(mapper.selectByObjectKey(key)).thenReturn(approved);
        assertEquals("approved-storage-url",guard.visibleUrl("https://foreign.invalid"+key,null));
        assertEquals("media-asset:7",guard.requireAttachment(22L,"https://foreign.invalid"+key,true));
    }

    @Test void missingRegistryAndStorageFailureNeverFallBackToOriginalUrl() {
        assertEquals("",guard.visibleUrl("media-asset:7",11L));
        state("APPROVED"); when(assets.approvedUrl(7L)).thenThrow(new IllegalStateException("storage unavailable"));
        assertThrows(IllegalStateException.class,()->guard.visibleUrl("media-asset:7",11L));
    }
    @Test void emptyOptionalFieldRemainsEmptyWithoutQueriesOrSigning() {
        assertNull(guard.visibleUrl(null,null));assertEquals("",guard.requireAttachment(11L,"",true));
        verifyNoInteractions(mapper,assets);
    }
    @Test void relativeLocalUploadRequiresRegistryAndRequestCacheKeepsAuthorSignaturesScoped() {
        String local="/profile/upload/2026/10/12/0123456789abcdef0123456789abcdef.png";
        when(mapper.selectByObjectKey(local)).thenReturn(Map.of("id",7L,"memberId",11L,"ownerType","SYSTEM","reviewState","APPROVED"));
        assertEquals("approved-storage-url",guard.visibleUrl(local,null));
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            state("HOLD");
            assertEquals("owned-short-preview", guard.visibleUrl("media-asset:7",11L));
            assertEquals("owned-short-preview", guard.visibleUrl("owned-short-preview",11L));
            verify(assets,times(1)).ownerPreview(7L,11L);
            assertEquals("",guard.visibleUrl("owned-short-preview",22L));
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(new org.springframework.mock.web.MockHttpServletRequest()));
            assertEquals("",guard.visibleUrl("owned-short-preview",11L));
        } finally { org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); }
    }
    @Test void supplierUsesReviewedImmutableKeyWithShortSigningAndNeverRawInputHost() {
        state("APPROVED");
        try (org.mockito.MockedStatic<com.chengyinhub.common.utils.file.FileOssUploadUtils> signer = mockStatic(com.chengyinhub.common.utils.file.FileOssUploadUtils.class)) {
            signer.when(() -> com.chengyinhub.common.utils.file.FileOssUploadUtils.generateShortLivedPresignedUrl(KEY,300)).thenReturn("https://real/short-signed");
            assertEquals("https://real/short-signed",guard.processingUrl("https://foreign.invalid/"+KEY,11L));
            signer.verify(() -> com.chengyinhub.common.utils.file.FileOssUploadUtils.generateShortLivedPresignedUrl(KEY,300));
            state("HOLD");
            assertThrows(ServiceException.class,()->guard.processingUrl("media-asset:7",11L));
            assertThrows(ServiceException.class,()->guard.processingUrl("media-asset:7",null));
        }
    }
    @Test void systemLocalMediaIsVisibleButCannotBeProcessedAsTheCurrentUsersUpload() {
        String key="/profile/upload/2026/10/12/0123456789abcdef0123456789abcdef.png";
        when(mapper.selectByObjectKey(key)).thenReturn(Map.of("id",7L,"memberId",11L,"ownerType","SYSTEM",
                "reviewState","APPROVED","storageType","LOCAL","objectKey",key));
        assertEquals("approved-storage-url",guard.visibleUrl("https://foreign.invalid/prod-api"+key,null));
        assertEquals(403,assertThrows(ServiceException.class,
                () -> guard.processingUrl("https://foreign.invalid/prod-api"+key,11L)).getCode());
    }
    @Test void approvedAppPhotosAndAudioCanOnlyBeProcessedByTheirUploader() {
        try (org.mockito.MockedStatic<com.chengyinhub.common.utils.file.FileOssUploadUtils> signer =
                     mockStatic(com.chengyinhub.common.utils.file.FileOssUploadUtils.class)) {
            for (String type : new String[]{"IMAGE", "AUDIO"}) {
                String key = "upload/0123456789abcdef0123456789abcdef." + ("IMAGE".equals(type) ? "png" : "mp3");
                Map<String,Object> row = Map.of("id",7L,"memberId",11L,"ownerType","APP",
                        "objectKey",key,"reviewState","APPROVED","mediaType",type);
                when(mapper.select(7L)).thenReturn(row);
                when(mapper.selectByObjectKey(key)).thenReturn(row);
                assertEquals("media-asset:7", guard.requireAttachment(22L,"media-asset:7",true));
                assertEquals("approved-storage-url", guard.visibleUrl("media-asset:7",22L));
                for (String reference : new String[]{"media-asset:7", "https://foreign.invalid/"+key}) {
                    ServiceException rejected = assertThrows(ServiceException.class,
                            () -> guard.processingUrl(reference,22L,type));
                    assertEquals(403,rejected.getCode());
                }
                signer.verifyNoInteractions();
                signer.when(() -> com.chengyinhub.common.utils.file.FileOssUploadUtils
                        .generateShortLivedPresignedUrl(key,300)).thenReturn("owned-processing-url");
                assertEquals("owned-processing-url",guard.processingUrl("media-asset:7",11L,type));
                signer.verify(() -> com.chengyinhub.common.utils.file.FileOssUploadUtils
                        .generateShortLivedPresignedUrl(key,300));
                signer.clearInvocations();
            }
        }
    }

    @Test void localPathCannotTurnAnotherAppUploadIntoSystemProcessingMaterial() {
        String key = "/profile/upload/2026/10/12/0123456789abcdef0123456789abcdef.png";
        Map<String,Object> row = Map.of("id",7L,"memberId",11L,"ownerType","APP",
                "objectKey",key,"reviewState","APPROVED","mediaType","IMAGE","storageType","LOCAL");
        when(mapper.select(7L)).thenReturn(row);
        when(mapper.selectByObjectKey(key)).thenReturn(row);
        for (String reference : new String[]{"media-asset:7",key,"https://foreign.invalid/prod-api"+key}) {
            assertEquals("media-asset:7",guard.requireAttachment(22L,reference,true));
            assertEquals(403,assertThrows(ServiceException.class,
                    () -> guard.processingUrl(reference,22L,"IMAGE")).getCode());
            assertEquals(key,guard.processingUrl(reference,11L,"IMAGE"));
        }
    }

    @Test void aNonAppOwnerDoesNotGrantTheCurrentUserProcessingPrivilege() {
        for (String ownerType : new String[]{"", "LEGACY", "SYSTEM"}) {
            when(mapper.select(7L)).thenReturn(Map.of("id",7L,"memberId",11L,"ownerType",ownerType,
                    "objectKey",KEY,"reviewState","APPROVED"));
            try (org.mockito.MockedStatic<com.chengyinhub.common.utils.file.FileOssUploadUtils> signer =
                         mockStatic(com.chengyinhub.common.utils.file.FileOssUploadUtils.class)) {
                assertEquals(403,assertThrows(ServiceException.class,
                        () -> guard.processingUrl("media-asset:7",11L)).getCode());
                signer.verifyNoInteractions();
            }
        }
    }

    @Test void stockRhythmAudioIsVisibleButCannotBecomeAVoiceOrAiProcessingInputWithoutRegistry() {
        for (String prefix : new String[]{"/audio/merchant-games/","/pages/play/audio/merchant-games/"}) {
            for (String clip : new String[]{"one","two","three"}) {
                String reference = prefix+"rhythm-"+clip+".mp3";
                assertEquals(reference,guard.visibleUrl(reference,null));
                assertThrows(ServiceException.class,()->guard.requireAttachment(11L,reference,true));
                assertThrows(ServiceException.class,()->guard.processingUrl(reference,11L,"AUDIO"));
            }
        }
        verifyNoInteractions(mapper,assets);
    }


    // ===== G-3 先发后审:本站系统图白名单 + JSON 相册 =====

    @Test void unregisteredLocalSystemImageIsPublishableAndResolvedFromStorageKey() {
        String key = "/profile/upload/2024/05/01/banner_20240501120000A001.png";
        when(assets.systemImageUrl(key)).thenReturn("https://public/profile/upload/2024/05/01/banner_20240501120000A001.png");
        for (String reference : new String[]{key, "https://www.chengyinhub.com/prod-api" + key}) {
            assertEquals(key, guard.requireAttachment(11L, reference, true));
            assertEquals("https://public/profile/upload/2024/05/01/banner_20240501120000A001.png",
                    guard.visibleUrl(reference, null));
        }
        // 系统图仍不是当前用户自己的上传,不能拿去克隆/第三方处理。
        assertThrows(ServiceException.class, () -> guard.processingUrl(key, 11L, "IMAGE"));
    }

    @Test void unregisteredBucketImageKeepsOwnHostWithoutSignatureAndForeignHostStaysRejected() {
        String key = "upload/0123456789abcdef0123456789abcdef.jpg";
        String own = "https://bucket.oss.example/" + key;
        try (org.mockito.MockedStatic<com.chengyinhub.common.utils.file.FileOssUploadUtils> oss =
                     mockStatic(com.chengyinhub.common.utils.file.FileOssUploadUtils.class)) {
            oss.when(() -> com.chengyinhub.common.utils.file.FileOssUploadUtils.isChengyinPhotoAddress(own + "?Signature=old"))
                    .thenReturn(true);
            oss.when(() -> com.chengyinhub.common.utils.file.FileOssUploadUtils.isChengyinPhotoAddress(own)).thenReturn(true);
            when(assets.systemImageUrl(key)).thenReturn("https://bucket.oss.example/fresh-signature");
            assertEquals(own, guard.requireAttachment(11L, own + "?Signature=old", true));
            assertEquals("https://bucket.oss.example/fresh-signature", guard.visibleUrl(own, null));
            String foreign = "https://foreign.invalid/" + key;
            assertThrows(ServiceException.class, () -> guard.requireAttachment(11L, foreign, true));
            assertEquals("", guard.visibleUrl(foreign, null));
        }
    }

    @Test void heldAudioStillCannotBePublishedAndAnonymousWriterGainsNothing() {
        state("HOLD");
        assertThrows(ServiceException.class, () -> guard.requireAttachment(11L, "media-asset:7", true));
        assertThrows(ServiceException.class,
                () -> guard.requireAttachment(null, "/profile/upload/2024/05/01/a.png", false));
    }

    // ===== V-13 / M-29(2026-10-29) =====

    @Test void officialTemplateImagesAreUsableByAnyoneAndVisibleButNeverProcessingInput() {
        for (String official : new String[]{
                "https://www.chengyinhub.com/prod-api/profile/template-covers/p03-capture-this-moment.webp",
                "https://www.chengyinhub.com/prod-api/profile/topic-covers/bund-night.webp",
                "https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/movement-bingo.jpg"}) {
            assertEquals(official, guard.requireAttachment(22L, official, true));
            assertEquals(official, guard.requireAttachment(22L, " " + official + " ", false));
            assertEquals(official, guard.visibleUrl(official, null));
            assertThrows(ServiceException.class, () -> guard.processingUrl(official, 22L));
        }
        assertThrows(ServiceException.class, () -> guard.requireAttachment(null,
                "https://www.chengyinhub.com/prod-api/profile/template-covers/p03-capture-this-moment.webp", false));
        for (String lookalike : new String[]{
                "http://www.chengyinhub.com/prod-api/profile/template-covers/p03.webp",
                "https://www.chengyinhub.com.evil.test/prod-api/profile/template-covers/p03.webp",
                "https://www.chengyinhub.com/prod-api/profile/template-covers/../upload/x.webp",
                "https://www.chengyinhub.com/prod-api/profile/avatar/p03.webp",
                "https://www.chengyinhub.com/prod-api/profile/template-covers/p03.webp?x=1"}) {
            assertThrows(ServiceException.class, () -> guard.requireAttachment(22L, lookalike, true), lookalike);
            assertEquals("", guard.visibleUrl(lookalike, null), lookalike);
        }
        verifyNoInteractions(assets);
    }

    @Test void ugcStoreGalleryAcceptsEveryApprovedUploadEvenFromAnotherStaffAccount() {
        state("APPROVED");
        String signed = "https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/" + KEY + "?Expires=1&OSSAccessKeyId=a&Signature=b";
        assertEquals("[\"media-asset:7\",\"media-asset:7\"]",
                guard.requireAttachments(33L, "[\"" + signed + "\",\"media-asset:7\"]", true));
    }

    @Test void jsonGalleryIsCheckedPerImageAndKeepsArrayShape() {
        state("APPROVED");
        String local = "/profile/upload/2024/05/01/a.png";
        assertEquals("[\"media-asset:7\",\"" + local + "\"]",
                guard.requireAttachments(11L, "[\"media-asset:7\",\" \",\"" + local + "\"]", true));
        assertThrows(ServiceException.class,
                () -> guard.requireAttachments(11L, "[\"https://foreign.invalid/raw.jpg\"]", true));
        assertThrows(ServiceException.class, () -> guard.requireAttachments(11L, "[{\"url\":1}]", true));
        assertThrows(ServiceException.class, () -> guard.requireAttachments(11L, "[\"media-asset:7\"", true));
    }
}
