package com.chengyinhub.common.utils.file;

import com.alibaba.fastjson2.JSONObject;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSSClient;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * C6 上传侧:归属与 bizType 必须在上传同一次 putObject 里写成 OSS user metadata;
 * 对象写失败必须向上暴露,不得返回假成功。SDK 边界替身(不触网)。
 */
class FileOssOwnerMetadataUploadTest
{
    private static final String BUCKET = "lie-channelwin";

    private OSSClient ossClient;

    @BeforeEach
    void setUp() throws Exception
    {
        ossClient = mock(OSSClient.class);
        doReturn(new PutObjectResult()).when(ossClient)
                .putObject(eq(BUCKET), anyString(), any(InputStream.class), any(ObjectMetadata.class));
        doReturn(new PutObjectResult()).when(ossClient)
                .putObject(eq(BUCKET), anyString(), any(InputStream.class));
        doReturn(new URL("https://lie-channelwin.oss-cn-hangzhou.aliyuncs.com/upload/x.jpg?Expires=1"))
                .when(ossClient).generatePresignedUrl(any());
    }

    private MockMultipartFile image()
    {
        return new MockMultipartFile("file", "onsite.jpg", "image/jpeg", "JPEGDATA".getBytes());
    }

    @Test
    void uploadWithOwnerWritesMemberAndBizTypeIntoTheSamePutObject() throws Exception
    {
        JSONObject uploaded = PhotoOwnerTestBridge.uploadWithOwner(
                image(), ossClient, BUCKET, "81", "image_free");

        assertTrue(uploaded.getString("fileName").matches("upload/[0-9a-f]{32}\\.jpg"),
                uploaded.getString("fileName"));

        ArgumentCaptor<ObjectMetadata> metadata = ArgumentCaptor.forClass(ObjectMetadata.class);
        verify(ossClient).putObject(eq(BUCKET), anyString(), any(InputStream.class), metadata.capture());
        assertEquals("81", metadata.getValue().getUserMetadata().get("member-id"));
        assertEquals("image_free", metadata.getValue().getUserMetadata().get("biz-type"));
    }

    @Test
    void objectWriteFailureIsNotReportedAsSuccess() throws Exception
    {
        doThrow(new ClientException("oss write failed")).when(ossClient)
                .putObject(eq(BUCKET), anyString(), any(InputStream.class), any(ObjectMetadata.class));
        doThrow(new ClientException("oss write failed")).when(ossClient)
                .putObject(eq(BUCKET), anyString(), any(InputStream.class));

        assertThrows(RuntimeException.class, () ->
                PhotoOwnerTestBridge.uploadWithOwner(image(), ossClient, BUCKET, "81", "image_free"));
    }
}
