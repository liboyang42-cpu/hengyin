package com.chengyinhub.common.utils.file;

import com.aliyun.oss.ClientConfiguration;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSSClient;
import com.aliyun.oss.ServiceException;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ObjectMetadata;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * C6/R1/P1 真实 SDK 事实:真 aliyun-sdk-oss 3.10.2 签名(https 与裸 endpoint/http),
 * 只替身 getObjectMetadata(不触网);AK/签名/host/过期/对象归属必须匹配现行配置与当前登录会员。
 */
class FileOssRealSdkPhotoVerificationTest
{
    private static final String BUCKET = "lie-channelwin";
    private static final String HTTPS_ENDPOINT = "https://oss-cn-hangzhou.aliyuncs.com";
    private static final String BARE_ENDPOINT = "oss-cn-hangzhou.aliyuncs.com";
    private static final String AK = "REVIEW-AK-ID";
    private static final String SK = "REVIEW-SK-SECRET";
    private static final String ROTATED_SK = "REVIEW-SK-ROTATED";
    private static final long EXPIRES_2100 = 4102444800L;
    private static final long MEMBER = 81L;
    private static final String KEY = "upload/0123456789abcdef0123456789abcdef.jpg";

    private OSSClient client(String endpoint, String secret)
    {
        return spy(new OSSClient(endpoint,
                new DefaultCredentialProvider(AK, secret), new ClientConfiguration()));
    }

    private void stubOwner(OSSClient ossClient, String memberId)
    {
        ObjectMetadata metadata = new ObjectMetadata();
        if (memberId != null) {
            metadata.addUserMetadata(FileOssUploadUtils.OWNER_MEMBER_METADATA_KEY, memberId);
        }
        doReturn(metadata).when(ossClient).getObjectMetadata(eq(BUCKET), anyString());
    }

    private String realUrl(OSSClient ossClient, long expiresSeconds)
    {
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(BUCKET, KEY);
        request.setExpiration(new Date(expiresSeconds * 1000L));
        URL url = ossClient.generatePresignedUrl(request);
        return url.toString();
    }

    @Test
    void genuineHttpsSdkSignatureFromOwnerIsAccepted()
    {
        OSSClient ossClient = client(HTTPS_ENDPOINT, SK);
        stubOwner(ossClient, String.valueOf(MEMBER));

        FileOssUploadUtils.verifyUploadedPhoto(realUrl(ossClient, EXPIRES_2100), ossClient, BUCKET, MEMBER);

        verify(ossClient).getObjectMetadata(BUCKET, KEY);
    }

    @Test
    void genuineBareEndpointHttpSignatureIsAccepted()
    {
        OSSClient ossClient = client(BARE_ENDPOINT, SK);
        stubOwner(ossClient, String.valueOf(MEMBER));
        String url = realUrl(ossClient, EXPIRES_2100);
        assertTrue(url.startsWith("http://"), url);

        FileOssUploadUtils.verifyUploadedPhoto(url, ossClient, BUCKET, MEMBER);

        verify(ossClient).getObjectMetadata(BUCKET, KEY);
    }

    @Test
    void httpsSignedUrlIsCompatibleWithHttpConfiguredClientForSameObject()
    {
        String httpsUrl = realUrl(client(HTTPS_ENDPOINT, SK), EXPIRES_2100);
        OSSClient bare = client(BARE_ENDPOINT, SK);
        stubOwner(bare, String.valueOf(MEMBER));

        // v1 签名不绑定 scheme:同一 host/桶/key/AK/签名的存量 URL 不因协议迁移失效。
        FileOssUploadUtils.verifyUploadedPhoto(httpsUrl, bare, BUCKET, MEMBER);

        verify(bare).getObjectMetadata(BUCKET, KEY);
    }

    @Test
    void tamperedAccessKeyIdIsRejectedBeforeObjectLookup()
    {
        OSSClient ossClient = client(HTTPS_ENDPOINT, SK);
        stubOwner(ossClient, String.valueOf(MEMBER));
        String tampered = realUrl(ossClient, EXPIRES_2100)
                .replace("OSSAccessKeyId=" + AK, "OSSAccessKeyId=TAMPERED-KEY-ID");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> FileOssUploadUtils.verifyUploadedPhoto(tampered, ossClient, BUCKET, MEMBER));

        assertEquals("照片上传凭证无效，请重新拍摄上传", failure.getMessage());
        verify(ossClient, never()).getObjectMetadata(anyString(), anyString());
    }

    @Test
    void rotatedSecretSignatureIsRejected()
    {
        String genuine = realUrl(client(HTTPS_ENDPOINT, SK), EXPIRES_2100);
        OSSClient rotated = client(HTTPS_ENDPOINT, ROTATED_SK);
        stubOwner(rotated, String.valueOf(MEMBER));

        assertThrows(IllegalArgumentException.class,
                () -> FileOssUploadUtils.verifyUploadedPhoto(genuine, rotated, BUCKET, MEMBER));
        verify(rotated, never()).getObjectMetadata(anyString(), anyString());
    }

    @Test
    void tamperedSignatureIsRejected()
    {
        OSSClient ossClient = client(HTTPS_ENDPOINT, SK);
        stubOwner(ossClient, String.valueOf(MEMBER));
        String tampered = realUrl(ossClient, EXPIRES_2100)
                .replaceAll("Signature=[^&]+", "Signature=forged%3D");

        assertThrows(IllegalArgumentException.class,
                () -> FileOssUploadUtils.verifyUploadedPhoto(tampered, ossClient, BUCKET, MEMBER));
        verify(ossClient, never()).getObjectMetadata(anyString(), anyString());
    }

    @Test
    void expiredRealCredentialIsRejectedBeforeObjectLookup()
    {
        OSSClient ossClient = client(HTTPS_ENDPOINT, SK);
        stubOwner(ossClient, String.valueOf(MEMBER));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> FileOssUploadUtils.verifyUploadedPhoto(
                        realUrl(ossClient, System.currentTimeMillis() / 1000L - 60L),
                        ossClient, BUCKET, MEMBER));

        assertEquals("照片上传凭证已过期，请重新拍摄上传", failure.getMessage());
        verify(ossClient, never()).getObjectMetadata(anyString(), anyString());
    }

    @Test
    void missingObjectIsRejected()
    {
        OSSClient ossClient = client(HTTPS_ENDPOINT, SK);
        doThrow(new ServiceException("not found", "NoSuchKey", "rid", "hid"))
                .when(ossClient).getObjectMetadata(BUCKET, KEY);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> FileOssUploadUtils.verifyUploadedPhoto(
                        realUrl(ossClient, EXPIRES_2100), ossClient, BUCKET, MEMBER));

        assertEquals("照片不存在或已失效，请重新拍摄上传", failure.getMessage());
    }

    @Test
    void storageOutageIsRecoverableNotInvalid()
    {
        OSSClient ossClient = client(HTTPS_ENDPOINT, SK);
        String url = realUrl(ossClient, EXPIRES_2100);
        doThrow(new ClientException("connect timed out"))
                .when(ossClient).getObjectMetadata(BUCKET, KEY);

        IllegalStateException outage = assertThrows(IllegalStateException.class,
                () -> FileOssUploadUtils.verifyUploadedPhoto(url, ossClient, BUCKET, MEMBER));
        assertTrue(outage.getMessage().startsWith("对象存储查询失败"), outage.getMessage());

        OSSClient serviceDown = client(HTTPS_ENDPOINT, SK);
        doThrow(new ServiceException("service unavailable"))
                .when(serviceDown).getObjectMetadata(BUCKET, KEY);
        assertThrows(IllegalStateException.class,
                () -> FileOssUploadUtils.verifyUploadedPhoto(
                        realUrl(serviceDown, EXPIRES_2100), serviceDown, BUCKET, MEMBER));
    }

    @Test
    void legacyObjectWithoutOwnerMetadataNeedsReupload()
    {
        OSSClient ossClient = client(HTTPS_ENDPOINT, SK);
        stubOwner(ossClient, null);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> FileOssUploadUtils.verifyUploadedPhoto(
                        realUrl(ossClient, EXPIRES_2100), ossClient, BUCKET, MEMBER));

        assertEquals("照片缺少上传归属信息，请重新拍摄上传", failure.getMessage());
    }

    @Test
    void anotherMembersUploadIsRejectedForCurrentMember()
    {
        OSSClient ossClient = client(HTTPS_ENDPOINT, SK);
        stubOwner(ossClient, "9999");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> FileOssUploadUtils.verifyUploadedPhoto(
                        realUrl(ossClient, EXPIRES_2100), ossClient, BUCKET, MEMBER));

        assertEquals("该照片不是当前账号上传的，请重新拍摄上传", failure.getMessage());
    }

    @Test
    void wrongBucketHostIsRejected()
    {
        OSSClient ossClient = client(HTTPS_ENDPOINT, SK);
        stubOwner(ossClient, String.valueOf(MEMBER));
        String url = realUrl(ossClient, EXPIRES_2100).replace(BUCKET + ".", "otherbucket.");

        assertThrows(IllegalArgumentException.class,
                () -> FileOssUploadUtils.verifyUploadedPhoto(url, ossClient, BUCKET, MEMBER));
        verify(ossClient, never()).getObjectMetadata(anyString(), anyString());
    }

    @Test
    void nullClientFailsClosedAsStorageUnavailable()
    {
        assertThrows(IllegalStateException.class,
                () -> FileOssUploadUtils.verifyUploadedPhoto("https://x/y.jpg?a=b", null, BUCKET, MEMBER));
    }

    @Test
    void blankMemberIdFailsClosedAsLoginRequired()
    {
        OSSClient ossClient = client(HTTPS_ENDPOINT, SK);
        stubOwner(ossClient, String.valueOf(MEMBER));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> FileOssUploadUtils.verifyUploadedPhoto(
                        realUrl(ossClient, EXPIRES_2100), ossClient, BUCKET, null));

        assertEquals("请先登录", failure.getMessage());
        verify(ossClient, never()).getObjectMetadata(anyString(), anyString());
    }

    @Test
    void verificationClientUsesShortTimeoutsInsteadOfSdkDefault()
    {
        ClientConfiguration configuration = FileOssUploadUtils.verificationClientConfiguration();

        assertEquals(FileOssUploadUtils.VERIFY_CONNECT_TIMEOUT_MS, configuration.getConnectionTimeout());
        assertEquals(FileOssUploadUtils.VERIFY_SOCKET_TIMEOUT_MS, configuration.getSocketTimeout());
        assertTrue(configuration.getConnectionTimeout() < 10000, "校验不能沿用 SDK 默认 50s 超时");
    }
}
