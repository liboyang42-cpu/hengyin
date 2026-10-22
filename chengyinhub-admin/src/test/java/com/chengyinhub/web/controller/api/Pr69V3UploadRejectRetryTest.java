package com.chengyinhub.web.controller.api;

import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.common.config.RuoYiConfig;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.framework.config.ServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PR #69 V3 · CL-39/CL-40(F-L5C-08, #69 e19055f1):本地图片先审核再保存。
 * 被驳回的图片不落盘、不回 URL,作者换图(或审核恢复后)重新上传即可成功;
 * 审核服务本身(微信内容安全)是外部依赖,这里只替换审核出口,保存链路走生产代码。
 */
class Pr69V3UploadRejectRetryTest
{
    @TempDir
    Path profile;
    private String originalProfile;

    @BeforeEach
    void useTempProfile()
    {
        originalProfile = RuoYiConfig.getProfile();
        new RuoYiConfig().setProfile(profile.toString());
    }

    @AfterEach
    void restoreProfile()
    {
        new RuoYiConfig().setProfile(originalProfile);
    }

    @Test
    void rejectedLocalImageIsNotSavedAndTheAuthorCanUploadAgain() throws Exception
    {
        AtomicBoolean reject = new AtomicBoolean(true);
        AtomicInteger reviews = new AtomicInteger();
        ApiCommonController controller = controller(file -> {
            reviews.incrementAndGet();
            assertFalse(hasStoredFile(), "审核必须早于落盘");
            if (reject.get()) throw new UploadRejectedException("图片包含违规内容");
        });

        ResponseEntity<AjaxResult> rejected = controller.uploadFile(png("cover.png"));
        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
        assertEquals(400, rejected.getBody().get(AjaxResult.CODE_TAG));
        assertFalse(rejected.getBody().containsKey("url"), "驳回的图片不能拿到可展示地址");
        assertFalse(hasStoredFile(), "驳回的图片不能留在存储里被他人看到");

        reject.set(false);
        ResponseEntity<AjaxResult> retried = controller.uploadFile(png("cover-v2.png"));
        assertEquals(HttpStatus.OK, retried.getStatusCode());
        assertEquals(200, retried.getBody().get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(retried.getBody().get("url")).startsWith("https://example.test/profile/upload/"));
        assertEquals(1, storedFileCount(), "重试只保存通过审核的那一张");
        assertEquals(2, reviews.get());
    }

    @Test
    void rejectedBatchSavesNothingAndAResubmittedBatchSavesEveryApprovedImage() throws Exception
    {
        AtomicBoolean rejectSecond = new AtomicBoolean(true);
        AtomicInteger reviews = new AtomicInteger();
        ApiCommonController controller = controller(file -> {
            int n = reviews.incrementAndGet();
            assertFalse(hasStoredFile(), "整批审核通过前不能写任何一张");
            if (rejectSecond.get() && n == 2) throw new UploadRejectedException("图片包含违规内容");
        });

        ResponseEntity<AjaxResult> rejected = controller.uploadFiles(Arrays.asList(png("a.png"), png("b.png")));
        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
        assertFalse(rejected.getBody().containsKey("urls"));
        assertFalse(hasStoredFile());

        rejectSecond.set(false);
        ResponseEntity<AjaxResult> retried = controller.uploadFiles(Arrays.asList(png("a.png"), png("c.png")));
        assertEquals(HttpStatus.OK, retried.getStatusCode());
        assertEquals(2, String.valueOf(retried.getBody().get("urls")).split(",").length);
        assertEquals(2, storedFileCount());
    }

    private ApiCommonController controller(AppUploadService.ContentSecurityVerifier verifier)
    {
        ApiCommonController controller = new ApiCommonController();
        ServerConfig serverConfig = mock(ServerConfig.class);
        when(serverConfig.getUrl()).thenReturn("https://example.test");
        ReflectionTestUtils.setField(controller, "serverConfig", serverConfig);
        ReflectionTestUtils.setField(controller, "appUploadService",
                new AppUploadService(Pr69V3UploadRejectRetryTest::unusedOss, verifier));
        return controller;
    }

    private static JSONObject unusedOss(MultipartFile file)
    {
        throw new AssertionError("本地上传不应走 OSS");
    }

    private boolean hasStoredFile()
    {
        return storedFileCount() > 0;
    }

    private long storedFileCount()
    {
        if (!Files.exists(profile)) return 0;
        try (Stream<Path> paths = Files.walk(profile))
        {
            return paths.filter(Files::isRegularFile).count();
        }
        catch (IOException e)
        {
            throw new AssertionError(e);
        }
    }

    private static MockMultipartFile png(String name) throws Exception
    {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(160, 90,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return new MockMultipartFile("file", name, "image/png", out.toByteArray());
    }
}
