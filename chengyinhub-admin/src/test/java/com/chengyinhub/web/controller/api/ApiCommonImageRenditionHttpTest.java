package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.utils.file.FileOssUploadUtils;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 图片按槽位出图的 HTTP 形状:路径即档位与格式,原签名查询串原样透传;验签失败只回 404,不暴露原因。 */
class ApiCommonImageRenditionHttpTest
{
    private static final String FILE = "0123456789abcdef0123456789abcdef.jpg";
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApiCommonController()).build();

    @Test
    void signedOriginalRedirectsToSignedRenditionWithCacheableHeaders() throws Exception
    {
        try (MockedStatic<FileOssUploadUtils> oss = mockStatic(FileOssUploadUtils.class)) {
            oss.when(() -> FileOssUploadUtils.imageRenditionUrl("upload/" + FILE, "4102444800", "AK", "a+b/c=", 750, true))
                    .thenReturn("https://bucket.oss/upload/" + FILE + "?x-oss-process=r&Signature=s");
            mvc.perform(get(java.net.URI.create("/api/common/img/750/webp/upload/" + FILE
                            + "?Expires=4102444800&OSSAccessKeyId=AK&Signature=a%2Bb%2Fc%3D")))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "https://bucket.oss/upload/" + FILE + "?x-oss-process=r&Signature=s"))
                    .andExpect(header().string("Cache-Control", "public, max-age=86400"));
        }
    }

    @Test
    void invalidSignatureOrSizeIs404() throws Exception
    {
        try (MockedStatic<FileOssUploadUtils> oss = mockStatic(FileOssUploadUtils.class)) {
            oss.when(() -> FileOssUploadUtils.imageRenditionUrl(anyString(), anyString(), anyString(), anyString(), anyInt(), anyBoolean()))
                    .thenThrow(new IllegalArgumentException("图片链接签名无效"));
            mvc.perform(get("/api/common/img/750/orig/upload/" + FILE + "?Expires=1&OSSAccessKeyId=AK&Signature=x"))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void nonImageOrUnknownFormatPathsDoNotReachTheSigner() throws Exception
    {
        try (MockedStatic<FileOssUploadUtils> oss = mockStatic(FileOssUploadUtils.class)) {
            mvc.perform(get("/api/common/img/750/avif/upload/" + FILE + "?Expires=1&OSSAccessKeyId=AK&Signature=x"))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/common/img/750/orig/upload/0123456789abcdef0123456789abcdef.mp4?Expires=1&OSSAccessKeyId=AK&Signature=x"))
                    .andExpect(status().isNotFound());
            oss.verify(() -> FileOssUploadUtils.imageRenditionUrl(anyString(), anyString(), anyString(), anyString(), anyInt(), anyBoolean()), never());
            oss.verify(() -> FileOssUploadUtils.imageRenditionUrl(eq("x"), anyString(), anyString(), anyString(), anyInt(), anyBoolean()), never());
        }
    }
}
