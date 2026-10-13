package com.chengyinhub.common.utils.file;

import com.alibaba.fastjson2.JSONObject;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSSClient;
import com.aliyun.oss.ServiceException;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C6 归属校验(边界只替换 OSS SDK 本地替身):真实上传核心写 member-id/biz-type,
 * 校验时核对象归属。不做生产网络调用、不写真实 OSS。
 */
class FileOssUploadUtilsUploadedPhotoTest {
    private static final String BUCKET = "lie-channelwin";
    private static final String ENDPOINT_HOST = "oss-cn-hangzhou.aliyuncs.com";
    private static final String PHOTO_KEY = "upload/0123456789abcdef0123456789abcdef.jpg";
    private static final long FAR_FUTURE_EXPIRES = 4102444800L; // 2100-01-01
    private static final String STAND_IN_SIGNATURE = "stand-in-signature";
    private static final long MEMBER = 77L;

    private final Map<String, ObjectMetadata> storedObjects = new HashMap<>();
    private OSSClient ossClient;

    @BeforeEach
    void setUp()
    {
        ossClient = mock(OSSClient.class);
        when(ossClient.putObject(eq(BUCKET), anyString(), any(InputStream.class))).thenAnswer(invocation -> {
            storedObjects.put(invocation.getArgument(1), new ObjectMetadata());
            return new PutObjectResult();
        });
        when(ossClient.putObject(eq(BUCKET), anyString(), any(InputStream.class), any(ObjectMetadata.class)))
                .thenAnswer(invocation -> {
                    storedObjects.put(invocation.getArgument(1), invocation.getArgument(3));
                    return new PutObjectResult();
                });
        when(ossClient.generatePresignedUrl(any(GeneratePresignedUrlRequest.class))).thenAnswer(invocation -> {
            GeneratePresignedUrlRequest request = invocation.getArgument(0);
            return new URL(presignedUrl(request.getKey(),
                    request.getExpiration().getTime() / 1000L, STAND_IN_SIGNATURE));
        });
        when(ossClient.getObjectMetadata(eq(BUCKET), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(1);
            if (!storedObjects.containsKey(key)) {
                throw new ServiceException("not found", "NoSuchKey", "rid", "hid");
            }
            return storedObjects.get(key);
        });
    }

    @Test
    void realUploadChainWithOwnerPassesVerification() throws Exception
    {
        MockMultipartFile image = new MockMultipartFile(
                "file", "onsite.gif", "image/gif", "GIF89a".getBytes("ISO-8859-1"));

        JSONObject uploaded = FileOssUploadUtils.upload(
                image, false, ossClient, BUCKET, String.valueOf(MEMBER), "image_free");
        String objectKey = uploaded.getString("fileName");
        String picUrl = uploaded.getString("url");

        assertTrue(objectKey.matches("upload/[0-9a-f]{32}\\.gif"), objectKey);
        assertEquals(String.valueOf(MEMBER), storedObjects.get(objectKey).getUserMetadata()
                .get(FileOssUploadUtils.OWNER_MEMBER_METADATA_KEY));
        assertEquals("image_free", storedObjects.get(objectKey).getUserMetadata()
                .get(FileOssUploadUtils.UPLOAD_BIZ_TYPE_METADATA_KEY));
        assertEquals(objectKey, FileOssUploadUtils.resolveUploadedPhotoObjectKey(picUrl));

        FileOssUploadUtils.verifyUploadedPhoto(picUrl, ossClient, BUCKET, MEMBER);

        verify(ossClient).getObjectMetadata(BUCKET, objectKey);
    }

    @Test
    void forgedSignatureIsRejectedWithoutStorageLookup()
    {
        storedObjects.put(PHOTO_KEY, ownerMetadata(String.valueOf(MEMBER)));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> FileOssUploadUtils.verifyUploadedPhoto(
                        presignedUrl(PHOTO_KEY, FAR_FUTURE_EXPIRES, "forged-signature"),
                        ossClient, BUCKET, MEMBER));

        assertEquals("照片上传凭证无效，请重新拍摄上传", failure.getMessage());
        verify(ossClient, never()).getObjectMetadata(anyString(), anyString());
    }

    @Test
    void nonexistentObjectIsRejectedEvenWithValidSignature()
    {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> FileOssUploadUtils.verifyUploadedPhoto(
                        signedUrl(PHOTO_KEY), ossClient, BUCKET, MEMBER));

        assertEquals("照片不存在或已失效，请重新拍摄上传", failure.getMessage());
        verify(ossClient).getObjectMetadata(BUCKET, PHOTO_KEY);
    }

    @Test
    void legacyObjectWithoutOwnerMetadataNeedsReupload()
    {
        storedObjects.put(PHOTO_KEY, new ObjectMetadata());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> FileOssUploadUtils.verifyUploadedPhoto(
                        signedUrl(PHOTO_KEY), ossClient, BUCKET, MEMBER));

        assertEquals("照片缺少上传归属信息，请重新拍摄上传", failure.getMessage());
    }

    @Test
    void anotherMembersObjectIsRejected()
    {
        storedObjects.put(PHOTO_KEY, ownerMetadata("9999"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> FileOssUploadUtils.verifyUploadedPhoto(
                        signedUrl(PHOTO_KEY), ossClient, BUCKET, MEMBER));

        assertEquals("该照片不是当前账号上传的，请重新拍摄上传", failure.getMessage());
    }

    @Test
    void arbitraryStringsForeignDomainsAndNonPhotoObjectsAreRejected()
    {
        storedObjects.put(PHOTO_KEY, ownerMetadata(String.valueOf(MEMBER)));
        String genuine = signedUrl(PHOTO_KEY);
        String aftercareKey = "upload/merchant-aftercare-evidence/0123456789abcdef0123456789abcdef.jpg";
        String path = "/" + PHOTO_KEY;
        String[] invalid = {
                "",
                "   ",
                "not-an-uploaded-photo",
                "javascript:alert(1)",
                genuine.replace(BUCKET + "." + ENDPOINT_HOST, "evil.example"),
                genuine.replace(BUCKET + "." + ENDPOINT_HOST, "otherbucket." + ENDPOINT_HOST),
                genuine.replace("https://", "https://user@"),
                "https://" + BUCKET + "." + ENDPOINT_HOST + ":443" + path
                        + "?Expires=" + FAR_FUTURE_EXPIRES + "&OSSAccessKeyId=stand-in-key&Signature=" + STAND_IN_SIGNATURE,
                signedUrl("upload/0123456789ABCDEF0123456789ABCDEF.jpg"),
                signedUrl("upload/0123456789abcdef0123456789abcdef.mp3"),
                signedUrl("upload/0123456789abcdef0123456789abcdef.jpg.html"),
                signedUrl("upload/%2e%2e/0123456789abcdef0123456789abcdef.jpg"),
                signedUrl(aftercareKey),
                "https://" + BUCKET + "." + ENDPOINT_HOST + path,
                genuine + "&response-content-type=text/html",
                genuine.replace("Signature=", "x-oss-signature="),
                "https://" + BUCKET + "." + ENDPOINT_HOST + path
                        + "?OSSAccessKeyId=stand-in-key&Signature=" + STAND_IN_SIGNATURE
        };
        for (String picUrl : invalid) {
            assertThrows(IllegalArgumentException.class,
                    () -> FileOssUploadUtils.verifyUploadedPhoto(picUrl, ossClient, BUCKET, MEMBER),
                    "应拒绝: " + picUrl);
        }
        verify(ossClient, never()).getObjectMetadata(anyString(), anyString());
    }

    @Test
    void expiredCredentialIsRejectedEvenWhenTheObjectExists()
    {
        storedObjects.put(PHOTO_KEY, ownerMetadata(String.valueOf(MEMBER)));
        long past = System.currentTimeMillis() / 1000L - 60L;

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> FileOssUploadUtils.verifyUploadedPhoto(
                        presignedUrl(PHOTO_KEY, past, STAND_IN_SIGNATURE), ossClient, BUCKET, MEMBER));

        assertEquals("照片上传凭证已过期，请重新拍摄上传", failure.getMessage());
        verify(ossClient, never()).getObjectMetadata(anyString(), anyString());
    }

    @Test
    void storageOutageIsRecoverableInsteadOfInvalid()
    {
        doThrow(new ClientException("connect timed out"))
                .when(ossClient).getObjectMetadata(BUCKET, PHOTO_KEY);
        IllegalStateException outage = assertThrows(IllegalStateException.class,
                () -> FileOssUploadUtils.verifyUploadedPhoto(signedUrl(PHOTO_KEY), ossClient, BUCKET, MEMBER));
        assertTrue(outage.getMessage().startsWith("对象存储查询失败"), outage.getMessage());

        doThrow(new ServiceException("storage service unavailable"))
                .when(ossClient).getObjectMetadata(BUCKET, PHOTO_KEY);
        assertThrows(IllegalStateException.class,
                () -> FileOssUploadUtils.verifyUploadedPhoto(signedUrl(PHOTO_KEY), ossClient, BUCKET, MEMBER));
    }

    @Test
    void nullClientFailsClosedAsStorageUnavailable()
    {
        assertThrows(IllegalStateException.class,
                () -> FileOssUploadUtils.verifyUploadedPhoto("https://x/y.jpg?a=b", null, BUCKET, MEMBER));
    }

    private static ObjectMetadata ownerMetadata(String memberId)
    {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.addUserMetadata(FileOssUploadUtils.OWNER_MEMBER_METADATA_KEY, memberId);
        return metadata;
    }

    private String signedUrl(String objectKey)
    {
        return presignedUrl(objectKey, FAR_FUTURE_EXPIRES, STAND_IN_SIGNATURE);
    }

    private static String presignedUrl(String objectKey, long expiresSeconds, String signature)
    {
        return "https://" + BUCKET + "." + ENDPOINT_HOST + "/" + objectKey
                + "?Expires=" + expiresSeconds
                + "&OSSAccessKeyId=stand-in-key&Signature=" + signature;
    }
}
