package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.ICommunityMediaReceiptService;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.mapper.AppMediaAssetMapper;
import com.chengyinhub.business.service.impl.AppMediaAssetService;
import org.springframework.beans.factory.ObjectProvider;
import com.chengyinhub.common.config.RuoYiConfig;
import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.file.FileUploadUtils;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;
import com.chengyinhub.common.utils.file.FileUtils;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.framework.config.ServerConfig;
import com.alibaba.fastjson2.JSONObject;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class ApiCommonUploadSecurityTest
{
    @Test
    void appUploadEntrypointsRejectHtmlDisguisedAsPdf() throws Exception
    {
        MockMultipartFile payload = new MockMultipartFile("file", "notice.pdf", "application/pdf",
                "<html><script>alert(1)</script></html>".getBytes());
        ApiCommonController controller = new ApiCommonController();

        ResponseEntity<AjaxResult> oss = controller.uploadOSS(payload, null, null);
        ResponseEntity<AjaxResult> single = controller.uploadFile(payload);
        ResponseEntity<AjaxResult> multiple = controller.uploadFiles(Collections.singletonList(payload));

        assertEquals(HttpStatus.BAD_REQUEST, oss.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, single.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, multiple.getStatusCode());
    }

    @Test
    void appUploadEntrypointsRejectHtmlAllowedByLegacyDefault() throws Exception
    {
        MockMultipartFile html = new MockMultipartFile("file", "legacy.html", "text/html",
                "<html><body>legacy content</body></html>".getBytes());
        ApiCommonController controller = new ApiCommonController();

        ResponseEntity<AjaxResult> oss = controller.uploadOSS(html, null, null);
        ResponseEntity<AjaxResult> single = controller.uploadFile(html);
        ResponseEntity<AjaxResult> multiple = controller.uploadFiles(Collections.singletonList(html));

        assertEquals(HttpStatus.BAD_REQUEST, oss.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, single.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, multiple.getStatusCode());
        assertEquals(400, oss.getBody().get(AjaxResult.CODE_TAG));
    }

    @Test
    void ossAllowlistRejectsOtherwiseValidPdf() throws Exception
    {
        MockMultipartFile pdf = new MockMultipartFile("file", "manual.pdf", "application/pdf", "%PDF-1.7".getBytes());

        ResponseEntity<AjaxResult> result = new ApiCommonController().uploadOSS(pdf, null, null);

        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
    }

    @Test
    void ossSanitizesBenignGifCommentsBeforeStorage() throws Exception
    {
        RecordingOssController controller = withDisabledSecurity(new RecordingOssController());
        MockMultipartFile polyglot = new MockMultipartFile("file", "banner.gif", "image/gif",
                gifWithScriptComment("banner.gif", 160, 90));

        ResponseEntity<AjaxResult> result = controller.uploadOSS(polyglot, null, null);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertTrue(controller.uploaded);
        assertFalse(new String(controller.uploadedBytes, "ISO-8859-1").contains("<script>"));
    }

    @Test
    void ossAndLocalImageWritesUseSanitizedBytes(@TempDir Path tempDir) throws Exception
    {
        byte[] sourceBytes = insertExif(jpeg("gps.jpg", 160, 90).getBytes(), "Exif\0\0GPSLatitude=31.2");
        RecordingOssController oss = withDisabledSecurity(new RecordingOssController());
        ResponseEntity<AjaxResult> ossResult = oss.uploadOSS(
                new MockMultipartFile("file", "gps.jpg", "image/jpeg", sourceBytes), "image_free", null);

        configureLocalUpload(tempDir);
        ResponseEntity<AjaxResult> localResult = localController().uploadFile(
                new MockMultipartFile("file", "gps.jpg", "image/jpeg", sourceBytes));
        String localFileName = (String) localResult.getBody().get("fileName");
        byte[] localBytes = Files.readAllBytes(tempDir.resolve(localFileName.substring((Constants.RESOURCE_PREFIX + "/").length())));

        assertEquals(HttpStatus.OK, ossResult.getStatusCode());
        assertFalse(java.util.Arrays.equals(sourceBytes, oss.uploadedBytes));
        assertFalse(new String(oss.uploadedBytes, "ISO-8859-1").contains("GPSLatitude"));
        assertEquals(HttpStatus.OK, localResult.getStatusCode());
        assertFalse(java.util.Arrays.equals(sourceBytes, localBytes));
        assertFalse(new String(localBytes, "ISO-8859-1").contains("GPSLatitude"));
    }

    @Test
    void communityUploadResponseAndReceiptUseStoredImageSize() throws Exception
    {
        byte[] sourceBytes = insertExif(jpeg("gps.jpg", 160, 90).getBytes(), "Exif\0\0GPSLatitude=31.2");
        RecordingOssController controller = new RecordingOssController();
        controller.appMember = 91L;
        ICommunityMediaReceiptService receipts = mock(ICommunityMediaReceiptService.class);
        when(receipts.issue(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn("test-upload-receipt");
        ReflectionTestUtils.setField(controller, "communityMediaReceiptService", receipts);

        ResponseEntity<AjaxResult> response = controller.uploadOSS(
                new MockMultipartFile("file", "gps.jpg", "image/jpeg", sourceBytes), UploadImagePolicy.COMMUNITY_POST_BIZ_TYPE, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(sourceBytes.length != controller.uploadedBytes.length);
        long storedSize = controller.uploadedBytes.length;
        assertEquals(storedSize, response.getBody().get("byteSize"));
        assertEquals("test-upload-receipt", response.getBody().get("uploadReceipt"));
        org.mockito.Mockito.verify(receipts).issue(91L, "https://example.test/upload/test.gif", storedSize);
    }

    @Test
    void ossStoredSizeFollowsImageSanitizationAndUnchangedAudio() throws Exception
    {
        byte[] image = insertExif(jpeg("gps.jpg", 160, 90).getBytes(), "Exif\0\0GPSLatitude=31.2");
        for (String bizType : new String[] { "image_free", "merchant_aftercare_evidence" })
        {
            RecordingOssController controller = new RecordingOssController();
            AppUploadService service = (AppUploadService) ReflectionTestUtils.getField(controller, "appUploadService");
            JSONObject uploaded = service.uploadOss(new MockMultipartFile(
                    "file", "gps.jpg", "image/jpeg", image), bizType);
            assertTrue(image.length != controller.uploadedBytes.length);
            assertEquals((long) controller.uploadedBytes.length, uploaded.get("byteSize"));
        }
        RecordingOssController controller = pendingAudioController(91L);
        AppUploadService service = (AppUploadService) ReflectionTestUtils.getField(controller, "appUploadService");
        byte[] audio = new byte[] { 'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0 };
        JSONObject uploaded = service.uploadOss(
                new MockMultipartFile("file", "voice.mp3", "audio/mpeg", audio), null, 91L, null, null);
        assertEquals((long) audio.length, uploaded.get("byteSize"));
        assertEquals("HOLD", uploaded.get("reviewState"));
        assertFalse(uploaded.containsKey("url"));
        assertEquals(91L, controller.stagedAsset.get("memberId"));
        assertEquals("AUDIO", controller.stagedAsset.get("mediaType"));
        org.junit.jupiter.api.Assertions.assertArrayEquals(audio, controller.uploadedBytes);
    }

    @Test
    void ossRejectsGifJavaScriptPolyglotBeforeStorage() throws Exception
    {
        RecordingOssController controller = withDisabledSecurity(new RecordingOssController());
        byte[] polyglot = java.util.Base64.getDecoder().decode(
                "R0lGODlhLyoBAIAAAAAAAP///ywAAAAAAQABAAACAUwAIf4aKi89MTtnbG9iYWxUaGlzLlBXTkVEPTE7Ly8AOw==");

        ResponseEntity<AjaxResult> result = controller.uploadOSS(
                new MockMultipartFile("file", "banner.gif", "image/gif", polyglot), null, null);

        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
        assertEquals(400, result.getBody().get(AjaxResult.CODE_TAG));
        assertFalse(controller.uploaded);
    }

    @Test
    void localAppUploadRejectsTruncatedPngBeforeWriting(@TempDir Path tempDir) throws Exception
    {
        RuoYiConfig config = new RuoYiConfig();
        config.setProfile(tempDir.toString());
        MockMultipartFile truncated = new MockMultipartFile("file", "cover.png", "image/png", pngWithHeaderOnly());

        ResponseEntity<AjaxResult> result = new ApiCommonController().uploadFile(truncated);

        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
        assertTrue(!Files.exists(tempDir.resolve("upload")));
    }

    @Test
    void appUploadStorageNamesAndOssObjectKeysDoNotExposeOriginalStem(@TempDir Path tempDir) throws Exception
    {
        configureLocalUpload(tempDir);
        String originalName = "张三_13900000000_private.jpeg";
        ResponseEntity<AjaxResult> local = localController().uploadFile(jpeg(originalName, 160, 90));
        String fileName = (String) local.getBody().get("fileName");

        java.lang.reflect.Method keyFactory = FileOssUploadUtils.class.getDeclaredMethod("createObjectKey", MultipartFile.class);
        keyFactory.setAccessible(true);
        String objectKey = (String) keyFactory.invoke(null, jpeg(originalName, 160, 90));

        assertEquals(HttpStatus.OK, local.getStatusCode());
        assertFalse(fileName.contains("张三"));
        assertFalse(fileName.contains("13900000000"));
        assertFalse(objectKey.contains("张三"));
        assertFalse(objectKey.contains("13900000000"));
        assertTrue(objectKey.matches("upload/[0-9a-f]{32}\\.jpeg"));
        assertTrue(Files.exists(tempDir.resolve(fileName.substring((Constants.RESOURCE_PREFIX + "/").length()))));
    }

    @Test
    void ossObjectKeyUsesVerifiedPngExtensionWhenOriginalNameHasNone() throws Exception
    {
        MockMultipartFile noSuffixPng = new MockMultipartFile("file", "private-upload-13900000000", "image/png",
                png("cover.png", 160, 90).getBytes());
        MultipartFile normalized = UploadImagePolicy.sanitizeMetadata(noSuffixPng, null);
        java.lang.reflect.Method keyFactory = FileOssUploadUtils.class.getDeclaredMethod("createObjectKey", MultipartFile.class);
        keyFactory.setAccessible(true);

        String objectKey = (String) keyFactory.invoke(null, normalized);

        assertEquals("png", FileUploadUtils.getExtension(normalized));
        assertTrue(objectKey.matches("upload/[0-9a-f]{32}\\.png"));
        assertFalse(objectKey.contains("private-upload"));
        assertFalse(objectKey.contains("13900000000"));
    }

    @Test
    void ossAudioObjectKeyStaysRandomAndKeepsItsExtension() throws Exception
    {
        MockMultipartFile audio = new MockMultipartFile("file", "voice_13900000000.mp3", "audio/mpeg",
                "ID3audio".getBytes("UTF-8"));
        java.lang.reflect.Method keyFactory = FileOssUploadUtils.class.getDeclaredMethod("createObjectKey", MultipartFile.class);
        keyFactory.setAccessible(true);

        String objectKey = (String) keyFactory.invoke(null, audio);

        assertTrue(objectKey.matches("upload/[0-9a-f]{32}\\.mp3"));
        assertFalse(objectKey.contains("voice_"));
        assertFalse(objectKey.contains("13900000000"));
    }

    @Test
    void ossRejectsWebpUntilDecoderSupportIsApproved() throws Exception
    {
        MockMultipartFile webp = new MockMultipartFile("file", "cover.webp", "image/webp",
                new byte[] { 'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P' });

        ResponseEntity<AjaxResult> result = new ApiCommonController().uploadOSS(webp, null, null);

        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
    }

    @Test
    void uploadEndpointsExposeRejectedFilesAsHttp4xx() throws Exception
    {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApiCommonController()).build();

        mvc.perform(multipart("/api/common/uploadOSS").file(disguisedPdf()))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/common/upload").file(disguisedPdf()))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/common/uploads").file(disguisedPdf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void batchEndpointRejectsTooManyFilesAndTotalSize() throws Exception
    {
        ApiCommonController controller = new ApiCommonController();
        MockMultipartFile validName = new MockMultipartFile("file", "cover.png", "image/png", pngHeader());

        ResponseEntity<AjaxResult> tooMany = controller.uploadFiles(Collections.nCopies(10, validName));
        MultipartFile first = sizedFile(30 * 1024 * 1024L);
        MultipartFile second = sizedFile(30 * 1024 * 1024L);
        ResponseEntity<AjaxResult> tooLarge = controller.uploadFiles(java.util.Arrays.asList(first, second));

        assertEquals(HttpStatus.BAD_REQUEST, tooMany.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, tooLarge.getStatusCode());
    }

    @Test
    void allAppUploadEntrypointsReturnSuccessResponseEntities(@TempDir Path tempDir) throws Exception
    {
        configureLocalUpload(tempDir);
        MockMultipartFile gif = gif("banner.gif", 160, 90);
        ApiCommonController local = localController();
        RecordingOssController oss = new RecordingOssController();
        ResponseEntity<AjaxResult> ossResult = oss.uploadOSS(gif, "image_16_9", null);
        ResponseEntity<AjaxResult> single = local.uploadFile(gif);
        ResponseEntity<AjaxResult> multiple = local.uploadFiles(Collections.singletonList(gif));

        assertEquals(HttpStatus.OK, ossResult.getStatusCode());
        assertTrue(oss.uploaded);
        assertEquals(HttpStatus.OK, single.getStatusCode());
        assertEquals(HttpStatus.OK, multiple.getStatusCode());
    }

    @Test
    void aftercareEvidenceUploadReturnsOnlyObjectKeyWhileOrdinaryUploadKeepsUrl() throws Exception
    {
        RecordingOssController controller = new RecordingOssController();
        controller.appMember = 81L;
        com.chengyinhub.business.service.IMerchantAftercareService aftercare =
                mock(com.chengyinhub.business.service.IMerchantAftercareService.class);
        when(aftercare.requireEvidenceUploadTarget(81L, 41L)).thenReturn(77L);
        ReflectionTestUtils.setField(controller, "merchantAftercareService", aftercare);

        ResponseEntity<AjaxResult> evidence = controller.uploadOSS(
                gif("evidence.gif", 160, 90), "merchant_aftercare_evidence", 41L);
        ResponseEntity<AjaxResult> ordinary = controller.uploadOSS(
                gif("ordinary.gif", 160, 90), "image_free", null);

        assertEquals(HttpStatus.OK, evidence.getStatusCode());
        assertEquals("upload/merchant-aftercare-evidence/0123456789abcdef0123456789abcdef.gif",
                evidence.getBody().get("fileName"));
        assertFalse(evidence.getBody().containsKey("url"),
                "售后凭证上传响应不得泄露长期 bearer URL");
        assertEquals(HttpStatus.OK, ordinary.getStatusCode());
        assertEquals("https://example.test/upload/test.gif", ordinary.getBody().get("url"),
                "普通上传合同必须保持不变");
    }

    @Test
    void adminRefundEvidenceUploadUsesDedicatedSanitizedObjectKeyContract() throws Exception
    {
        java.util.concurrent.atomic.AtomicBoolean ordinaryCalled = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean evidenceCalled = new java.util.concurrent.atomic.AtomicBoolean();
        AppUploadService service = new AppUploadService(file -> {
            ordinaryCalled.set(true);
            return successfulOssResult(file);
        }, file -> {
            evidenceCalled.set(true);
            JSONObject result = new JSONObject();
            result.put("fileName",
                    "upload/merchant-aftercare-evidence/0123456789abcdef0123456789abcdef.gif");
            result.put("versionId", "CAEQARiBgMC-example-version-1");
            return result;
        }, file -> { });

        AppUploadService.ManualRefundEvidenceUpload upload =
                service.uploadManualRefundEvidence(gif("proof.gif", 160, 90));

        assertEquals("upload/merchant-aftercare-evidence/0123456789abcdef0123456789abcdef.gif",
                upload.getObjectKey());
        assertEquals("CAEQARiBgMC-example-version-1", upload.getVersionId());
        assertTrue(evidenceCalled.get());
        assertFalse(ordinaryCalled.get());
    }

    @Test
    void adminRefundEvidenceUploadFails503WhenBucketVersioningIsUnavailable() throws Exception
    {
        AppUploadService service = new AppUploadService(file -> successfulOssResult(file), file -> {
            JSONObject result = new JSONObject();
            result.put("fileName",
                    "upload/merchant-aftercare-evidence/0123456789abcdef0123456789abcdef.gif");
            return result;
        }, file -> { });

        com.chengyinhub.common.exception.ServiceException failure = assertThrows(
                com.chengyinhub.common.exception.ServiceException.class,
                () -> service.uploadManualRefundEvidence(gif("proof.gif", 160, 90)));

        assertEquals(Integer.valueOf(503), failure.getCode());
    }

    @Test
    void adminRefundEvidenceUploadRejectsGenericObjectKeyFromStorage() throws Exception
    {
        AppUploadService service = new AppUploadService(file -> successfulOssResult(file), file -> {
            JSONObject result = new JSONObject();
            result.put("fileName", "upload/0123456789abcdef0123456789abcdef.gif");
            return result;
        }, file -> { });

        com.chengyinhub.common.exception.ServiceException failure = assertThrows(
                com.chengyinhub.common.exception.ServiceException.class,
                () -> service.uploadManualRefundEvidence(gif("proof.gif", 160, 90)));

        assertEquals(Integer.valueOf(500), failure.getCode());
    }

    @Test
    void ossKeepsSupportedAudioFormatsWhileHoldingThemForReview() throws Exception
    {
        RecordingOssController controller = pendingAudioController(91L);
        ResponseEntity<AjaxResult> mp3 = controller.uploadOSS(
                new MockMultipartFile("file", "voice.mp3", "audio/mpeg", new byte[] { 'I', 'D', '3' }), null, null);
        ResponseEntity<AjaxResult> m4a = controller.uploadOSS(
                new MockMultipartFile("file", "voice.m4a", "audio/mp4", new byte[] { 0, 0, 0, 0, 'f', 't', 'y', 'p' }), null, null);
        ResponseEntity<AjaxResult> aac = controller.uploadOSS(
                new MockMultipartFile("file", "voice.aac", "audio/aac", new byte[] { (byte) 0xFF, (byte) 0xF1 }), null, null);

        assertEquals(HttpStatus.OK, mp3.getStatusCode());
        assertEquals(HttpStatus.OK, m4a.getStatusCode());
        assertEquals(HttpStatus.OK, aac.getStatusCode());
        assertEquals(3, controller.uploadCount);
        for (ResponseEntity<AjaxResult> response : java.util.Arrays.asList(mp3, m4a, aac)) {
            assertEquals("HOLD", response.getBody().get("reviewState"));
            org.junit.jupiter.api.Assertions.assertNull(response.getBody().get("url"));
        }
        assertEquals(91L, controller.stagedAsset.get("memberId"));
        assertEquals("AUDIO", controller.stagedAsset.get("mediaType"));
    }

    @Test
    void anonymousAudioCannotStoreOrIssueReviewedAsset() {
        RecordingOssController controller = pendingAudioController(0L);
        ResponseEntity<AjaxResult> result = controller.uploadOSS(new MockMultipartFile(
                "file", "voice.mp3", "audio/mpeg", new byte[] { 'I', 'D', '3' }), null, null);
        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
        assertEquals(400, result.getBody().get(AjaxResult.CODE_TAG));
        assertEquals(0, controller.uploadCount);
        assertFalse(controller.uploaded);
        org.junit.jupiter.api.Assertions.assertNull(controller.stagedAsset);
    }

    @Test
    void ossStorageFailureReturnsConsistentHttp500() throws Exception
    {
        StorageFailingOssController controller = new StorageFailingOssController();
        ResponseEntity<AjaxResult> result = controller.uploadOSS(gif("banner.gif", 160, 90), null, null);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result.getStatusCode());
        assertEquals(500, result.getBody().get(AjaxResult.CODE_TAG));
        assertFalse(result.getBody().isSuccess());
    }

    @Test
    void ossConfigurationFailureReturnsConsistentHttp500WithoutSwallowingOtherIllegalState() throws Exception
    {
        RuntimeFailingOssController configurationFailure = new RuntimeFailingOssController(
                new IllegalStateException("对象存储配置缺失: CHENGYIN_OSS_BUCKET_NAME"));
        RuntimeFailingOssController unexpectedFailure = new RuntimeFailingOssController(
                new IllegalStateException("unexpected program state"));
        RuntimeFailingOssController nullPointerFailure = new RuntimeFailingOssController(
                new NullPointerException("unexpected program bug"));
        MockMultipartFile image = gif("banner.gif", 160, 90);

        ResponseEntity<AjaxResult> result = configurationFailure.uploadOSS(image, null, null);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result.getStatusCode());
        assertEquals(500, result.getBody().get(AjaxResult.CODE_TAG));
        assertThrows(IllegalStateException.class, () -> unexpectedFailure.uploadOSS(image, null, null));
        assertThrows(NullPointerException.class, () -> nullPointerFailure.uploadOSS(image, null, null));
    }

    @Test
    void ossRejectsMissingStorageResultFieldsAndClientFailuresAsHttp500() throws Exception
    {
        JSONObject missingFileName = new JSONObject();
        missingFileName.put("url", "https://example.test/upload/test.gif");
        JSONObject missingUrl = new JSONObject();
        missingUrl.put("fileName", "upload/test.gif");
        JSONObject emptyFields = new JSONObject();
        emptyFields.put("fileName", " ");
        emptyFields.put("url", "");
        MockMultipartFile image = gif("banner.gif", 160, 90);

        assertStorageFailure(withDisabledSecurity(new StaticOssController(null)).uploadOSS(image, null, null));
        assertStorageFailure(withDisabledSecurity(new StaticOssController(new JSONObject())).uploadOSS(image, null, null));
        assertStorageFailure(withDisabledSecurity(new StaticOssController(missingFileName)).uploadOSS(image, null, null));
        assertStorageFailure(withDisabledSecurity(new StaticOssController(missingUrl)).uploadOSS(image, null, null));
        assertStorageFailure(withDisabledSecurity(new StaticOssController(emptyFields)).uploadOSS(image, null, null));
        assertStorageFailure(withDisabledSecurity(new OssClientFailingController()).uploadOSS(image, null, null));
    }

    @Test
    void ossSecurityUnavailableBranchesReturnConsistentHttp503() throws Exception
    {
        MockMultipartFile image = gif("banner.gif", 160, 90);
        ApiCommonController missingConfiguration = new ApiCommonController();
        ReflectionTestUtils.setField(missingConfiguration, "appUploadService", new AppUploadService(
                ApiCommonUploadSecurityTest::successfulOssResult,
                file -> { throw new UploadSecurityUnavailableException("安全检查配置异常，请联系管理员"); }));
        SecurityScenarioOssController tokenUnavailable = new SecurityScenarioOssController(null, AjaxResult.success(), null);
        SecurityScenarioOssController securityException = new SecurityScenarioOssController("token", null,
                new UploadSecurityUnavailableException("安全检查服务异常，请稍后重试"));

        assertServiceUnavailable(missingConfiguration.uploadOSS(image, null, null));
        assertServiceUnavailable(tokenUnavailable.uploadOSS(image, null, null));
        assertServiceUnavailable(securityException.uploadOSS(image, null, null));
    }

    @Test
    void ossContentRejectionRemainsHttp400RatherThanServiceUnavailable() throws Exception
    {
        SecurityScenarioOssController controller = new SecurityScenarioOssController("token",
                AjaxResult.error(HttpStatus.BAD_REQUEST.value(), "图片包含违法违规内容，请重新选择"), null);

        ResponseEntity<AjaxResult> result = controller.uploadOSS(gif("banner.gif", 160, 90), null, null);

        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
        assertEquals(400, result.getBody().get(AjaxResult.CODE_TAG));
        assertFalse(result.getBody().isSuccess());
    }

    @Test
    void rejectedFilesDoNotPersistOrReachOss(@TempDir Path tempDir) throws Exception
    {
        configureLocalUpload(tempDir);
        ApiCommonController local = localController();
        MockMultipartFile valid = gif("banner.gif", 160, 90);
        MockMultipartFile invalid = new MockMultipartFile("file", "banner.gif", "image/gif", "GIF89a".getBytes());
        RecordingOssController oss = new RecordingOssController();

        ResponseEntity<AjaxResult> single = local.uploadFile(invalid);
        ResponseEntity<AjaxResult> multiple = local.uploadFiles(java.util.Arrays.asList(valid, invalid));
        ResponseEntity<AjaxResult> ossResult = oss.uploadOSS(invalid, null, null);

        assertEquals(HttpStatus.BAD_REQUEST, single.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, multiple.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, ossResult.getStatusCode());
        assertTrue(!Files.exists(tempDir.resolve("upload")));
        assertTrue(!oss.uploaded);
    }

    @Test
    void batchUploadRollsBackEarlierFileWhenLaterWriteFails(@TempDir Path tempDir) throws Exception
    {
        configureLocalUpload(tempDir);
        MockMultipartFile valid = gif("first.gif", 160, 90);
        MultipartFile failing = new PartiallyWritingMultipartFile("second.gif", "image/gif", gif("second.gif", 160, 90).getBytes());
        String uploadDirectory = tempDir.resolve("upload").toString();
        FileUploadUtils.UploadDirectoryContext context = FileUploadUtils.newUploadDirectoryContext(uploadDirectory);

        String first = FileUploadUtils.upload(uploadDirectory, valid, AppUploadService.IMAGE_EXTENSIONS, true, context);
        assertThrows(IOException.class, () -> FileUploadUtils.upload(uploadDirectory, failing,
                AppUploadService.IMAGE_EXTENSIONS, true, context));
        FileUtils.deleteFile(tempDir + java.io.File.separator + first.substring((Constants.RESOURCE_PREFIX + "/").length()));
        context.deleteOwnedEmptyDirectories();

        assertFalse(containsUploadedFile(tempDir.resolve("upload")));
        assertFalse(Files.exists(tempDir.resolve("upload")));
    }

    @Test
    void singleUploadLeavesNoPartialOrTemporaryFileWhenWriteFails(@TempDir Path tempDir) throws Exception
    {
        configureLocalUpload(tempDir);
        MultipartFile failing = new PartiallyWritingMultipartFile("cover.gif", "image/gif", gif("cover.gif", 160, 90).getBytes());

        assertThrows(IOException.class, () -> FileUploadUtils.upload(tempDir.resolve("upload").toString(), failing,
                AppUploadService.IMAGE_EXTENSIONS, true));

        assertFalse(containsUploadedFile(tempDir.resolve("upload")));
        assertFalse(Files.exists(tempDir.resolve("upload")));
    }

    @Test
    void failedUploadKeepsPreexistingEmptyDateDirectory(@TempDir Path tempDir) throws Exception
    {
        configureLocalUpload(tempDir);
        Path dateDirectory = tempDir.resolve("upload").resolve(DateUtils.datePath());
        Files.createDirectories(dateDirectory);

        assertThrows(IOException.class, () -> FileUploadUtils.upload(tempDir.resolve("upload").toString(),
                new PartiallyWritingMultipartFile("cover.gif", "image/gif", gif("cover.gif", 160, 90).getBytes()),
                AppUploadService.IMAGE_EXTENSIONS, true));

        assertTrue(Files.isDirectory(tempDir.resolve("upload")));
        assertTrue(Files.isDirectory(dateDirectory));
    }

    @Test
    void batchFailureRemovesOnlyDirectoriesCreatedByThisBatch(@TempDir Path tempDir) throws Exception
    {
        configureLocalUpload(tempDir);
        MockMultipartFile first = gif("first.gif", 160, 90);
        MultipartFile second = new PartiallyWritingMultipartFile("second.gif", "image/gif",
                gif("second.gif", 160, 90).getBytes());
        String uploadDirectory = tempDir.resolve("upload").toString();
        FileUploadUtils.UploadDirectoryContext context = FileUploadUtils.newUploadDirectoryContext(uploadDirectory);

        String saved = FileUploadUtils.upload(uploadDirectory, first, AppUploadService.IMAGE_EXTENSIONS, true, context);
        assertThrows(IOException.class, () -> FileUploadUtils.upload(uploadDirectory, second,
                AppUploadService.IMAGE_EXTENSIONS, true, context));
        FileUtils.deleteFile(tempDir + java.io.File.separator + saved.substring((Constants.RESOURCE_PREFIX + "/").length()));
        context.deleteOwnedEmptyDirectories();

        assertFalse(Files.exists(tempDir.resolve("upload")));
    }

    @Test
    void failedUploadDoesNotDeletePreexistingNonemptyOrOutsideDirectories(@TempDir Path tempDir) throws Exception
    {
        configureLocalUpload(tempDir);
        Path dateDirectory = tempDir.resolve("upload").resolve(DateUtils.datePath());
        Path marker = dateDirectory.resolve("concurrent-upload.marker");
        Path outside = tempDir.resolve("outside.marker");
        Files.createDirectories(dateDirectory);
        Files.write(marker, new byte[] { 1 });
        Files.write(outside, new byte[] { 2 });

        assertThrows(IOException.class, () -> FileUploadUtils.upload(tempDir.resolve("upload").toString(),
                new PartiallyWritingMultipartFile("cover.gif", "image/gif", gif("cover.gif", 160, 90).getBytes()),
                AppUploadService.IMAGE_EXTENSIONS, true));

        assertTrue(Files.exists(marker));
        assertTrue(Files.exists(outside));
    }

    private boolean containsUploadedFile(Path uploadDirectory) throws IOException
    {
        if (!Files.exists(uploadDirectory))
        {
            return false;
        }
        try (Stream<Path> paths = Files.walk(uploadDirectory))
        {
            return paths.anyMatch(Files::isRegularFile);
        }
    }

    private void configureLocalUpload(Path tempDir)
    {
        RuoYiConfig config = new RuoYiConfig();
        config.setProfile(tempDir.toString());
    }

    private ApiCommonController localController()
    {
        ApiCommonController controller = new ApiCommonController();
        ServerConfig serverConfig = mock(ServerConfig.class);
        when(serverConfig.getUrl()).thenReturn("https://example.test");
        ReflectionTestUtils.setField(controller, "serverConfig", serverConfig);
        return controller;
    }

    private <T extends ApiCommonController> T withDisabledSecurity(T controller)
    {
        return controller;
    }

    private void assertServiceUnavailable(ResponseEntity<AjaxResult> result)
    {
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, result.getStatusCode());
        assertEquals(503, result.getBody().get(AjaxResult.CODE_TAG));
        assertFalse(result.getBody().isSuccess());
    }

    private void assertStorageFailure(ResponseEntity<AjaxResult> result)
    {
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result.getStatusCode());
        assertEquals(500, result.getBody().get(AjaxResult.CODE_TAG));
        assertFalse(result.getBody().isSuccess());
    }

    private MockMultipartFile gif(String name, int width, int height) throws Exception
    {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(width, height,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "gif", out);
        return new MockMultipartFile("file", name, "image/gif", out.toByteArray());
    }

    private MockMultipartFile jpeg(String name, int width, int height) throws Exception
    {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(width, height,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "jpg", out);
        return new MockMultipartFile("file", name, "image/jpeg", out.toByteArray());
    }

    private MockMultipartFile png(String name, int width, int height) throws Exception
    {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(width, height,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return new MockMultipartFile("file", name, "image/png", out.toByteArray());
    }

    private byte[] insertExif(byte[] jpeg, String metadata) throws Exception
    {
        byte[] payload = metadata.getBytes("ISO-8859-1");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        int segmentLength = payload.length + 2;
        out.write(segmentLength >> 8);
        out.write(segmentLength);
        out.write(payload);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private byte[] gifWithScriptComment(String name, int width, int height) throws Exception
    {
        byte[] gif = gif(name, width, height).getBytes();
        byte[] script = "<script>alert(1)</script>".getBytes("UTF-8");
        byte[] result = java.util.Arrays.copyOf(gif, gif.length + script.length + 3);
        int insertAt = gif.length - 1;
        result[insertAt] = 0x21;
        result[insertAt + 1] = (byte) 0xFE;
        result[insertAt + 2] = (byte) script.length;
        System.arraycopy(script, 0, result, insertAt + 3, script.length);
        result[result.length - 1] = ';';
        return result;
    }

    private RecordingOssController pendingAudioController(Long currentMemberId) {
        RecordingOssController controller = new RecordingOssController();
        controller.appMember = currentMemberId;
        AppMediaAssetMapper mapper = mock(AppMediaAssetMapper.class);
        when(mapper.insert(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            java.util.Map<String, Object> row = call.getArgument(0);
            assertEquals(91L, row.get("memberId"));
            assertEquals("AUDIO", row.get("mediaType"));
            assertEquals("HOLD", row.get("reviewState"));
            row.put("id", 901L);
            controller.stagedAsset = row;
            return 1;
        });
        when(mapper.bindAuditTask(901L, 0, 902L)).thenReturn(1);
        IAuditTaskService audits = mock(IAuditTaskService.class);
        when(audits.submitSafetyHold(org.mockito.ArgumentMatchers.eq("MEDIA_ASSET"),
                org.mockito.ArgumentMatchers.eq(901L), org.mockito.ArgumentMatchers.eq(91L),
                org.mockito.ArgumentMatchers.eq("NORMAL"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(902L);
        ObjectProvider<IAuditTaskService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(audits);
        AppMediaAssetService assets = new AppMediaAssetService(mapper, provider);
        AppUploadService service = (AppUploadService) ReflectionTestUtils.getField(controller, "appUploadService");
        ReflectionTestUtils.setField(service, "appMediaAssetService", assets);
        return controller;
    }

    private static final class RecordingOssController extends ApiCommonController
    {
        private boolean uploaded;
        private int uploadCount;
        private byte[] uploadedBytes;
        private Long appMember;
        private java.util.Map<String, Object> stagedAsset;

        @Override
        public Long getAppUserId()
        {
            return appMember != null ? appMember : super.getAppUserId();
        }

        private RecordingOssController()
        {
            ReflectionTestUtils.setField(this, "appUploadService", new AppUploadService(
                    this::store, this::storeAftercareEvidence, file -> { }));
        }

        private JSONObject store(MultipartFile file) throws IOException
        {
            String filename = file.getOriginalFilename();
            String audioExtension = filename != null && filename.matches(".*\\.(mp3|m4a|aac|wav)")
                    ? filename.substring(filename.lastIndexOf('.') + 1) : null;
            return store(file, audioExtension == null ? "upload/test.gif"
                    : "upload/0123456789abcdef0123456789abcdef." + audioExtension);
        }

        private JSONObject storeAftercareEvidence(MultipartFile file) throws IOException
        {
            uploaded = true;
            uploadCount++;
            uploadedBytes = file.getBytes();
            JSONObject result = new JSONObject();
            result.put("fileName",
                    "upload/merchant-aftercare-evidence/0123456789abcdef0123456789abcdef.gif");
            return result;
        }

        private JSONObject store(MultipartFile file, String fileName) throws IOException
        {
            uploaded = true;
            uploadCount++;
            uploadedBytes = file.getBytes();
            JSONObject result = new JSONObject();
            result.put("fileName", fileName);
            result.put("url", "https://example.test/upload/test.gif");
            return result;
        }
    }

    private static final class StorageFailingOssController extends ApiCommonController
    {
        private StorageFailingOssController()
        {
            ReflectionTestUtils.setField(this, "appUploadService", new AppUploadService(file -> {
                throw new IOException("simulated OSS write failure");
            }, file -> { }));
        }
    }

    private static final class RuntimeFailingOssController extends ApiCommonController
    {
        private final RuntimeException failure;

        private RuntimeFailingOssController(RuntimeException failure)
        {
            this.failure = failure;
            ReflectionTestUtils.setField(this, "appUploadService", new AppUploadService(file -> {
                throw this.failure;
            }, file -> { }));
        }
    }

    private static final class StaticOssController extends ApiCommonController
    {
        private final JSONObject result;

        private StaticOssController(JSONObject result)
        {
            this.result = result;
            ReflectionTestUtils.setField(this, "appUploadService", new AppUploadService(file -> this.result, file -> { }));
        }
    }

    private static final class OssClientFailingController extends ApiCommonController
    {
        private OssClientFailingController()
        {
            ReflectionTestUtils.setField(this, "appUploadService", new AppUploadService(file -> {
                throw new ClientException("simulated OSS client failure");
            }, file -> { }));
        }
    }

    private static final class SecurityScenarioOssController extends ApiCommonController
    {
        private final String accessToken;
        private final AjaxResult checkResult;
        private final RuntimeException checkException;

        private SecurityScenarioOssController(String accessToken, AjaxResult checkResult, RuntimeException checkException)
        {
            this.accessToken = accessToken;
            this.checkResult = checkResult;
            this.checkException = checkException;
            ReflectionTestUtils.setField(this, "appUploadService", new AppUploadService(
                    ApiCommonUploadSecurityTest::successfulOssResult, file -> {
                if (this.checkException != null)
                {
                    throw this.checkException;
                }
                if (this.accessToken == null)
                {
                    throw new UploadSecurityUnavailableException("安全检查服务异常，请稍后重试");
                }
                if (!this.checkResult.isSuccess())
                {
                    if (Integer.valueOf(400).equals(this.checkResult.get(AjaxResult.CODE_TAG)))
                    {
                        throw new UploadRejectedException((String) this.checkResult.get(AjaxResult.MSG_TAG));
                    }
                    throw new UploadSecurityUnavailableException((String) this.checkResult.get(AjaxResult.MSG_TAG));
                }
            }));
        }
    }

    private static JSONObject successfulOssResult(MultipartFile file)
    {
        JSONObject result = new JSONObject();
        result.put("fileName", "upload/test.gif");
        result.put("url", "https://example.test/upload/test.gif");
        return result;
    }

    @Test
    void ossSdkExceptionsAreMappedByMockMvcToHttpAndBody500() throws Exception
    {
        ApiCommonController clientFailure = new ApiCommonController();
        ReflectionTestUtils.setField(clientFailure, "appUploadService", new AppUploadService(file -> {
            throw new ClientException("simulated client failure");
        }, file -> { }));
        ApiCommonController serviceFailure = new ApiCommonController();
        ReflectionTestUtils.setField(serviceFailure, "appUploadService", new AppUploadService(file -> {
            throw new ServiceException("simulated service failure");
        }, file -> { }));
        ApiCommonController ossFailure = new ApiCommonController();
        ReflectionTestUtils.setField(ossFailure, "appUploadService", new AppUploadService(file -> {
            throw new OSSException("simulated OSS failure");
        }, file -> { }));

        MockMultipartFile image = gif("banner.gif", 160, 90);
        MockMvcBuilders.standaloneSetup(clientFailure).build()
                .perform(multipart("/api/common/uploadOSS").file(image))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500));
        MockMvcBuilders.standaloneSetup(serviceFailure).build()
                .perform(multipart("/api/common/uploadOSS").file(image))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500));
        MockMvcBuilders.standaloneSetup(ossFailure).build()
                .perform(multipart("/api/common/uploadOSS").file(image))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500));
    }

    private static final class PartiallyWritingMultipartFile extends MockMultipartFile
    {
        private PartiallyWritingMultipartFile(String filename, String contentType, byte[] bytes)
        {
            super("file", filename, contentType, bytes);
        }

        @Override
        public void transferTo(Path destination) throws IOException, IllegalStateException
        {
            Files.write(destination, new byte[] { 'p', 'a', 'r', 't', 'i', 'a', 'l' });
            throw new IOException("simulated partial storage failure");
        }
    }

    private MockMultipartFile disguisedPdf()
    {
        return new MockMultipartFile("file", "notice.pdf", "application/pdf",
                "<html><script>alert(1)</script></html>".getBytes());
    }

    private MultipartFile sizedFile(long size)
    {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(size);
        return file;
    }

    private byte[] pngHeader()
    {
        return new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };
    }

    private byte[] pngWithHeaderOnly()
    {
        return new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13,
                'I', 'H', 'D', 'R', 0, 0, 6, 64, 0, 0, 3, (byte) 0x84, 8, 2, 0, 0, 0, 0, 0, 0, 0 };
    }

}
