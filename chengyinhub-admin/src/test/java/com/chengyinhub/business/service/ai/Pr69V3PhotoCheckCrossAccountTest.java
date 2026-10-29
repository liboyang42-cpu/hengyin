package com.chengyinhub.business.service.ai;

import com.chengyinhub.business.mapper.AppMediaAssetMapper;
import com.chengyinhub.business.service.ConsentGuard;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import com.chengyinhub.common.config.RuoYiConfig;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR #69 V3 · CL-41(#69 1567c252 AppMediaReferenceGuard 归属):
 * 会员 B 不能把会员 A 已审核通过的照片交给 AI 拍照判定(第三方视觉模型)。
 * 真 PhotoCheckVisionService + 真 AppMediaReferenceGuard,只替换媒体登记 mapper 与模型出口。
 */
class Pr69V3PhotoCheckCrossAccountTest
{
    private static final long MEMBER_A = 11L;
    private static final long MEMBER_B = 22L;
    private static final String KEY = "/profile/upload/2026/10/12/0123456789abcdef0123456789abcdef.jpg";

    @TempDir
    Path profile;
    private String originalProfile;
    private LlmClient vision;
    private ConsentGuard consent;
    private PhotoCheckVisionService service;

    @BeforeEach
    void setUp() throws Exception
    {
        originalProfile = RuoYiConfig.getProfile();
        new RuoYiConfig().setProfile(profile.toString());
        Files.createDirectories(profile.resolve("upload/2026/10/12"));
        Files.write(profile.resolve("upload/2026/10/12/0123456789abcdef0123456789abcdef.jpg"), jpeg());

        AppMediaAssetMapper mapper = mock(AppMediaAssetMapper.class);
        Map<String, Object> asset = Map.of("id", 101L, "memberId", MEMBER_A, "ownerType", "APP",
                "reviewState", "APPROVED", "mediaType", "IMAGE", "storageType", "LOCAL", "objectKey", KEY);
        when(mapper.select(101L)).thenReturn(asset);
        when(mapper.selectByObjectKey(KEY)).thenReturn(asset);
        AppMediaReferenceGuard guard = new AppMediaReferenceGuard();
        ReflectionTestUtils.setField(guard, "mapper", mapper);

        vision = mock(LlmClient.class);
        when(vision.chatVision(anyString(), anyString(), anyString()))
                .thenReturn("{\"pass\":true,\"confidence\":90,\"reason\":\"满足要求\"}");
        consent = mock(ConsentGuard.class);
        service = new PhotoCheckVisionService();
        AiGatewayService quota = mock(AiGatewayService.class);
        when(quota.consumeQuota(MEMBER_A, "photo_check")).thenReturn("reservation");
        ReflectionTestUtils.setField(service, "aiGatewayService", quota);
        ReflectionTestUtils.setField(service, "consentGuard", consent);
        ReflectionTestUtils.setField(service, "mediaReferences", guard);
        ReflectionTestUtils.setField(service, "visionClient", vision);
    }

    @AfterEach
    void restoreProfile()
    {
        new RuoYiConfig().setProfile(originalProfile);
    }

    @Test
    void memberBCannotSendMemberAsApprovedPhotoToTheVisionModel()
    {
        for (String reference : new String[]{"media-asset:101", KEY, "https://cdn.example.test/prod-api" + KEY})
        {
            ServiceException denied = assertThrows(ServiceException.class,
                    () -> service.review(MEMBER_B, reference, "拍到窗外街景"), reference);
            assertEquals(403, denied.getCode(), reference);
        }
        verify(vision, never()).chatVision(any(), any(), any());
    }

    @Test
    void theUploaderCanStillHaveTheirOwnPhotoReviewed()
    {
        PhotoCheckVisionResult result = service.review(MEMBER_A, "media-asset:101", "拍到窗外街景");
        assertTrue(result.isPass());
        verify(vision).chatVision(anyString(), anyString(), anyString());
        verify(consent).requireCurrentAgreement(MEMBER_A, "photo_model_processing", "object_photo");
    }

    private static byte[] jpeg() throws Exception
    {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
