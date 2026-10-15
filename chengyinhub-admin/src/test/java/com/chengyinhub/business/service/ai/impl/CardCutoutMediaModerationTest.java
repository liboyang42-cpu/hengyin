package com.chengyinhub.business.service.ai.impl;

import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.service.impl.AppMediaAssetService;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CardCutoutMediaModerationTest {
    private final String key="upload/0123456789abcdef0123456789abcdef.png";
    @Test void generatedImageReturnsOnlyHeldCanonicalAssetAndNeverSignsPublicUrl() throws Exception {
        OssCardCutoutStorage storage=new OssCardCutoutStorage();
        AppMediaAssetService assets=mock(AppMediaAssetService.class);
        ReflectionTestUtils.setField(storage,"mediaAssets",assets);
        when(assets.stage(9L,key)).thenReturn(java.util.Map.of("assetId",12L,"reviewState","HOLD"));
        try(MockedStatic<FileOssUploadUtils> oss=mockStatic(FileOssUploadUtils.class)) {
            JSONObject uploaded=new JSONObject();uploaded.put("fileName",key);uploaded.put("url","premature-url");
            oss.when(()->FileOssUploadUtils.uploadPendingWithOwner(any(),eq("9"),eq(OssCardCutoutStorage.BIZ_TYPE))).thenReturn(uploaded);
            assertEquals("media-asset:12",storage.storeObject(new byte[]{1},"cutout.png","image/png",9L,OssCardCutoutStorage.BIZ_TYPE));
            verify(assets).stage(9L,key);
            oss.verify(()->FileOssUploadUtils.generatePresignedUrl(anyString()),never());
            oss.verify(()->FileOssUploadUtils.generateShortLivedPresignedUrl(anyString(),anyInt()),never());
        }
    }
    @Test void privateProviderInputGetsOnlyFiveMinuteLinkAndUnconfiguredOutputDoesNotUpload() throws Exception {
        OssCardCutoutStorage storage=new OssCardCutoutStorage();
        try(MockedStatic<FileOssUploadUtils> oss=mockStatic(FileOssUploadUtils.class)) {
            assertNull(storage.storeObject(new byte[]{1},"cutout.png","image/png",9L,OssCardCutoutStorage.BIZ_TYPE));
            oss.verifyNoInteractions();
            JSONObject uploaded=new JSONObject();uploaded.put("fileName",key);
            oss.when(()->FileOssUploadUtils.uploadPendingWithOwner(any(),eq("9"),eq(OssCardCutoutStorage.SEG_INPUT_BIZ_TYPE))).thenReturn(uploaded);
            oss.when(()->FileOssUploadUtils.generateShortLivedPresignedUrl(key,300)).thenReturn("short-private-preview");
            assertEquals("short-private-preview",storage.storeObject(new byte[]{1},"input.jpg","image/jpeg",9L,OssCardCutoutStorage.SEG_INPUT_BIZ_TYPE));
            oss.verify(()->FileOssUploadUtils.generatePresignedUrl(anyString()),never());
        }
    }
}
