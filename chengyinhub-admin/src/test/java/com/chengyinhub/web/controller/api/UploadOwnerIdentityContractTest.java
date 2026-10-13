package com.chengyinhub.web.controller.api;

import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.service.IMerchantAftercareService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * C6 上传端:认证会员上传必须把当前登录身份与 bizType 交给归属绑定上传器;
 * 后台/非会员身份不伪造归属且不误伤既有上传;售后凭证仍走专用通道。
 */
class UploadOwnerIdentityContractTest
{
    private static final Long MEMBER = 81L;

    private static final class RecordingOwnerUploader implements AppUploadService.PhotoOwnerUploader
    {
        final AtomicReference<String> memberId = new AtomicReference<>();
        final AtomicReference<String> bizType = new AtomicReference<>();

        @Override
        public JSONObject upload(org.springframework.web.multipart.MultipartFile file,
                                 String ownerMemberId, String bizType)
        {
            memberId.set(ownerMemberId);
            this.bizType.set(bizType);
            JSONObject result = new JSONObject();
            result.put("fileName", "upload/0123456789abcdef0123456789abcdef.gif");
            result.put("url", "https://lie-channelwin.oss-cn-hangzhou.aliyuncs.com/upload/"
                    + "0123456789abcdef0123456789abcdef.gif?Expires=1&OSSAccessKeyId=k&Signature=s");
            return result;
        }
    }

    private static MockMultipartFile gif() throws Exception
    {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                160, 90, java.awt.image.BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "gif", out);
        return new MockMultipartFile("file", "banner.gif", "image/gif", out.toByteArray());
    }

    private ApiCommonController controllerWith(AppUploadService service)
    {
        ApiCommonController controller = spy(new ApiCommonController());
        ReflectionTestUtils.setField(controller, "appUploadService", service);
        return controller;
    }

    private AppUploadService service(RecordingOwnerUploader owner,
                                     AppUploadService.OssUploader aftercare)
    {
        AppUploadService.OssUploader unusedPlain = file -> {
            throw new AssertionError("普通路径不得走旧无归属 uploader");
        };
        return new AppUploadService(unusedPlain, aftercare, file -> { }, owner);
    }

    @Test
    void authenticatedMemberUploadCarriesMemberIdAndBizType() throws Exception
    {
        RecordingOwnerUploader owner = new RecordingOwnerUploader();
        ApiCommonController controller = controllerWith(service(owner, null));
        doReturn(MEMBER).when(controller).getAppUserId();

        ResponseEntity<AjaxResult> response = controller.uploadOSS(gif(), "image_free", null);

        assertEquals(200, response.getBody().get(AjaxResult.CODE_TAG));
        assertEquals(String.valueOf(MEMBER), owner.memberId.get());
        assertEquals("image_free", owner.bizType.get());
        assertFalse(String.valueOf(response.getBody().get("url")).isEmpty());
    }

    @Test
    void nonMemberPrincipalUploadsUnboundAndStillSucceeds() throws Exception
    {
        RecordingOwnerUploader owner = new RecordingOwnerUploader();
        ApiCommonController controller = controllerWith(service(owner, null));
        doThrow(new ServiceException("获取APP用户信息异常", 401)).when(controller).getAppUserId();

        ResponseEntity<AjaxResult> response = controller.uploadOSS(gif(), "image_free", null);

        assertEquals(200, response.getBody().get(AjaxResult.CODE_TAG));
        assertNull(owner.memberId.get(), "非会员身份不得伪造归属");
        assertEquals("image_free", owner.bizType.get());
    }

    @Test
    void anonymousOrInvalidMemberIdIsNotBound() throws Exception
    {
        RecordingOwnerUploader owner = new RecordingOwnerUploader();
        ApiCommonController controller = controllerWith(service(owner, null));
        doReturn(0L).when(controller).getAppUserId();

        ResponseEntity<AjaxResult> response = controller.uploadOSS(gif(), "image_free", null);

        assertEquals(200, response.getBody().get(AjaxResult.CODE_TAG));
        assertNull(owner.memberId.get());
    }

    private static final Long REFUND = 41L;
    private static final Long MERCHANT = 77L;

    private static final class RecordingAftercareUploader implements AppUploadService.AftercareEvidenceUploader
    {
        final AtomicReference<String> uploader = new AtomicReference<>();
        final AtomicReference<Long> merchantId = new AtomicReference<>();
        final AtomicReference<Long> refundId = new AtomicReference<>();

        @Override
        public JSONObject upload(org.springframework.web.multipart.MultipartFile file,
                                 String uploaderMemberId, Long merchantId, Long refundId)
        {
            uploader.set(uploaderMemberId);
            this.merchantId.set(merchantId);
            this.refundId.set(refundId);
            JSONObject result = new JSONObject();
            result.put("fileName", "upload/merchant-aftercare-evidence/0123456789abcdef0123456789abcdef.gif");
            result.put("versionId", "CAEQARiBgMC-example-version-1");
            return result;
        }
    }

    private ApiCommonController aftercareController(RecordingOwnerUploader plain,
                                                    RecordingAftercareUploader aftercare,
                                                    IMerchantAftercareService aftercareService)
    {
        AppUploadService.OssUploader unusedPlain = file -> {
            throw new AssertionError("售后凭证不得走普通 uploader");
        };
        ApiCommonController controller = controllerWith(
                new AppUploadService(unusedPlain, aftercare, file -> { }, plain));
        ReflectionTestUtils.setField(controller, "merchantAftercareService", aftercareService);
        doReturn(MEMBER).when(controller).getAppUserId();
        return controller;
    }

    @Test
    void merchantAftercareEvidenceUploadBindsStoreAndRefundAfterServerSideAuthorization() throws Exception
    {
        RecordingOwnerUploader plain = new RecordingOwnerUploader();
        RecordingAftercareUploader aftercare = new RecordingAftercareUploader();
        IMerchantAftercareService aftercareService = mock(IMerchantAftercareService.class);
        when(aftercareService.requireEvidenceUploadTarget(MEMBER, REFUND)).thenReturn(MERCHANT);
        ApiCommonController controller = aftercareController(plain, aftercare, aftercareService);

        ResponseEntity<AjaxResult> response = controller.uploadOSS(gif(), "merchant_aftercare_evidence", REFUND);

        assertEquals(200, response.getBody().get(AjaxResult.CODE_TAG));
        assertEquals(MERCHANT, aftercare.merchantId.get(), "W6f:服务端校验出的门店写入元数据");
        assertEquals(REFUND, aftercare.refundId.get(), "W6f:退款单写入元数据");
        assertNull(plain.memberId.get(), "售后凭证不得走普通归属上传");
        assertFalse(response.getBody().containsKey("url"));
    }

    @Test
    void merchantAftercareEvidenceUploadWithoutRefundIdIsRejectedBeforeStorage() throws Exception
    {
        RecordingAftercareUploader aftercare = new RecordingAftercareUploader();
        IMerchantAftercareService aftercareService = mock(IMerchantAftercareService.class);
        ApiCommonController controller = aftercareController(new RecordingOwnerUploader(), aftercare, aftercareService);

        ResponseEntity<AjaxResult> response = controller.uploadOSS(gif(), "merchant_aftercare_evidence", null);

        assertEquals(400, response.getBody().get(AjaxResult.CODE_TAG));
        assertNull(aftercare.refundId.get());
        verifyNoInteractions(aftercareService);
    }

    @Test
    void merchantAftercareEvidenceUploadForForeignRefundIsRejectedBeforeStorage() throws Exception
    {
        RecordingAftercareUploader aftercare = new RecordingAftercareUploader();
        IMerchantAftercareService aftercareService = mock(IMerchantAftercareService.class);
        when(aftercareService.requireEvidenceUploadTarget(MEMBER, REFUND))
                .thenThrow(new ServiceException("售后单不存在或无权访问", 404));
        ApiCommonController controller = aftercareController(new RecordingOwnerUploader(), aftercare, aftercareService);

        ResponseEntity<AjaxResult> response = controller.uploadOSS(gif(), "merchant_aftercare_evidence", REFUND);

        assertEquals(404, response.getBody().get(AjaxResult.CODE_TAG));
        assertEquals(404, response.getStatusCode().value());
        assertNull(aftercare.uploader.get(), "未授权不得写 OSS");
    }

    @Test
    void merchantAftercareEvidenceUploadRequiresAppMember() throws Exception
    {
        RecordingAftercareUploader aftercare = new RecordingAftercareUploader();
        IMerchantAftercareService aftercareService = mock(IMerchantAftercareService.class);
        ApiCommonController controller = aftercareController(new RecordingOwnerUploader(), aftercare, aftercareService);
        doThrow(new ServiceException("获取APP用户信息异常", 401)).when(controller).getAppUserId();

        ResponseEntity<AjaxResult> response = controller.uploadOSS(gif(), "merchant_aftercare_evidence", REFUND);

        assertEquals(400, response.getBody().get(AjaxResult.CODE_TAG));
        assertNull(aftercare.uploader.get());
        verifyNoInteractions(aftercareService);
    }
}
