package com.chengyinhub.common.utils.file;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSSClient;
import com.aliyun.oss.ServiceException;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * W6f(9-15 拍板):售后凭证绑定「门店 + 退款单」。上传时服务端把 merchant-id / refund-id 与对象
 * 同一次 putObject 写入 OSS 元数据;核对时门店或退款单任一不符、无绑定旧对象、不存在对象一律拒绝,
 * 存储故障为可恢复错误。不限上传者本人、不限时效。只替身 OSSClient,上传核心与核对逻辑真实。
 */
class FileOssUploadUtilsAftercareEvidenceBindingTest {
    private static final String BUCKET = "lie-channelwin";
    private static final long MERCHANT_9005 = 9005L;
    private static final long MERCHANT_9002 = 9002L;
    private static final long REFUND_41 = 41L;
    private static final long REFUND_42 = 42L;

    private final Map<String, ObjectMetadata> stored = new HashMap<>();
    private OSSClient oss;

    @BeforeEach
    void setUp() {
        oss = mock(OSSClient.class);
        when(oss.putObject(eq(BUCKET), anyString(), any(InputStream.class))).thenAnswer(call -> {
            stored.put(call.getArgument(1), new ObjectMetadata());
            return new PutObjectResult();
        });
        when(oss.putObject(eq(BUCKET), anyString(), any(InputStream.class), any(ObjectMetadata.class)))
                .thenAnswer(call -> {
                    stored.put(call.getArgument(1), call.getArgument(3));
                    return new PutObjectResult();
                });
        when(oss.getObjectMetadata(eq(BUCKET), anyString())).thenAnswer(call -> {
            ObjectMetadata metadata = stored.get(call.<String>getArgument(1));
            if (metadata == null) throw new ServiceException("not found", "NoSuchKey", "rid", "hid");
            return metadata;
        });
    }

    private static MockMultipartFile image() {
        return new MockMultipartFile("file", "proof.png", "image/png", new byte[] {1, 2, 3});
    }

    private String uploadBound(long uploader, long merchantId, long refundId) throws Exception {
        return FileOssUploadUtils.uploadAftercareEvidence(image(), String.valueOf(uploader), merchantId, refundId,
                oss, BUCKET).getString("fileName");
    }

    @Test
    void uploadWritesStoreAndRefundIntoObjectMetadata() throws Exception {
        String key = uploadBound(901L, MERCHANT_9005, REFUND_41);
        Map<String, String> meta = stored.get(key).getUserMetadata();
        assertEquals("9005", meta.get("merchant-id"));
        assertEquals("41", meta.get("refund-id"));
        assertEquals("901", meta.get("member-id"), "上传者仅留痕,不参与核对");
    }

    @Test
    void sameStoreSameRefundPassesRegardlessOfUploaderAndAge() throws Exception {
        String key = uploadBound(901L, MERCHANT_9005, REFUND_41);
        stored.get(key).setLastModified(new Date(System.currentTimeMillis() - 30L * 24 * 3600_000L));
        assertDoesNotThrow(() -> FileOssUploadUtils.verifyAftercareEvidenceBinding(
                key, MERCHANT_9005, REFUND_41, oss, BUCKET));
    }

    @Test
    void sameStoreOtherRefundIsRejected() throws Exception {
        String key = uploadBound(901L, MERCHANT_9005, REFUND_41);
        assertThrows(IllegalArgumentException.class, () -> FileOssUploadUtils.verifyAftercareEvidenceBinding(
                key, MERCHANT_9005, REFUND_42, oss, BUCKET));
    }

    @Test
    void knownForeignMerchantKeyIsRejected() throws Exception {
        String key = uploadBound(901L, MERCHANT_9005, REFUND_41);
        assertThrows(IllegalArgumentException.class, () -> FileOssUploadUtils.verifyAftercareEvidenceBinding(
                key, MERCHANT_9002, REFUND_41, oss, BUCKET));
    }

    @Test
    void legacyUnboundObjectIsRejectedNotGuessed() throws Exception {
        String legacy = FileOssUploadUtils.upload(image(), true, oss, BUCKET).getString("fileName");
        assertThrows(IllegalArgumentException.class, () -> FileOssUploadUtils.verifyAftercareEvidenceBinding(
                legacy, MERCHANT_9005, REFUND_41, oss, BUCKET));
        String memberOnly = FileOssUploadUtils.upload(image(), true, oss, BUCKET, "901",
                "merchant_aftercare_evidence").getString("fileName");
        assertThrows(IllegalArgumentException.class, () -> FileOssUploadUtils.verifyAftercareEvidenceBinding(
                memberOnly, MERCHANT_9005, REFUND_41, oss, BUCKET));
    }

    @Test
    void nonexistentObjectIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> FileOssUploadUtils.verifyAftercareEvidenceBinding(
                "upload/merchant-aftercare-evidence/0123456789abcdef0123456789abcdef.png",
                MERCHANT_9005, REFUND_41, oss, BUCKET));
    }

    @Test
    void missingStoreOrRefundIsRejected() throws Exception {
        String key = uploadBound(901L, MERCHANT_9005, REFUND_41);
        assertThrows(IllegalArgumentException.class, () -> FileOssUploadUtils.verifyAftercareEvidenceBinding(
                key, null, REFUND_41, oss, BUCKET));
        assertThrows(IllegalArgumentException.class, () -> FileOssUploadUtils.verifyAftercareEvidenceBinding(
                key, MERCHANT_9005, null, oss, BUCKET));
    }

    @Test
    void storageFailureIsRecoverableNotSuccess() throws Exception {
        String key = uploadBound(901L, MERCHANT_9005, REFUND_41);
        when(oss.getObjectMetadata(eq(BUCKET), anyString())).thenThrow(new ClientException("timeout"));
        assertThrows(IllegalStateException.class, () -> FileOssUploadUtils.verifyAftercareEvidenceBinding(
                key, MERCHANT_9005, REFUND_41, oss, BUCKET));
        assertThrows(IllegalStateException.class, () -> FileOssUploadUtils.verifyAftercareEvidenceBinding(
                key, MERCHANT_9005, REFUND_41, null, BUCKET));
    }
}
