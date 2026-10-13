package com.chengyinhub.web.controller.api;

import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.web.controller.common.CommonController;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminFileUploadServiceTest
{
    @Test
    void ossRejectDoesNotInvokePutObjectBoundary() throws Exception
    {
        AtomicInteger puts = new AtomicInteger();
        AdminFileUploadService service = new AdminFileUploadService(file -> {
            puts.incrementAndGet();
            return ossResult();
        });
        MockMvc mvc = controller(service);

        mvc.perform(multipart("/common/uploadOSS").file(new MockMultipartFile("file", "avatar.png", "image/png",
                        "<html><script>alert(1)</script></html>".getBytes("UTF-8"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        assertEquals(0, puts.get());
    }

    @Test
    void ossLegalImageKeepsAjaxResultContract() throws Exception
    {
        AtomicInteger puts = new AtomicInteger();
        AdminFileUploadService service = new AdminFileUploadService(file -> {
            puts.incrementAndGet();
            return ossResult();
        });
        MockMvc mvc = controller(service);

        mvc.perform(multipart("/common/uploadOSS").file(new MockMultipartFile("file", "avatar.png", "image/png",
                        image("png"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.fileName").value("upload/safe.png"))
                .andExpect(jsonPath("$.url").value("https://media.example.test/upload/safe.png"));

        assertEquals(1, puts.get());
    }

    @Test
    void ossStorageFailureDoesNotLeakInfrastructureDetails() throws Exception
    {
        AdminFileUploadService service = new AdminFileUploadService(file -> {
            throw new IOException("bucket=private-bucket endpoint=oss-secret request-id=abc /private/key");
        });
        MockMvc mvc = controller(service);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CommonController.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try
        {
            mvc.perform(multipart("/common/uploadOSS").file(new MockMultipartFile("file", "avatar.png", "image/png",
                            image("png"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.msg").value("文件上传失败"));
        }
        finally
        {
            logger.detachAppender(appender);
        }

        // 日志只记安全类别/文件数：既不带异常原因链，也不含 bucket/endpoint/request-id/敏感路径。
        assertFalse(appender.list.isEmpty());
        for (ch.qos.logback.classic.spi.ILoggingEvent event : appender.list)
        {
            String rendered = event.getFormattedMessage();
            assertEquals(null, event.getThrowableProxy());
            assertFalse(rendered.contains("bucket"));
            assertFalse(rendered.contains("endpoint"));
            assertFalse(rendered.contains("request-id"));
            assertFalse(rendered.contains("private-bucket"));
            assertFalse(rendered.contains("/private/key"));
        }
    }

    @Test
    void sizeGateCountsRealBytesInsteadOfReportedSize() throws Exception
    {
        AdminFileUploadPolicy policy = new AdminFileUploadPolicy();
        long limit = 50L * 1024 * 1024;

        // 恰好 50MiB：真实读取计数通过并返回真实字节数。
        assertEquals(limit, policy.assertReadableSizeWithinLimit(streamingFile("data.pdf", limit, limit), limit));

        // 50MiB+1：真实流超限即拒，且是流式读取到上限即停。
        assertThrows(AdminFileUploadService.RejectedException.class,
                () -> policy.assertReadableSizeWithinLimit(streamingFile("data.pdf", limit + 1, limit + 1), limit));

        // getSize 谎报为 0，真实流却超限：证明以真实计数为准，不信 getSize。
        assertThrows(AdminFileUploadService.RejectedException.class,
                () -> policy.assertReadableSizeWithinLimit(streamingFile("data.pdf", limit + 1, 0L), limit));

        // 截断：getSize 声称远大于真实到达字节，落盘前即拒。
        assertThrows(AdminFileUploadService.RejectedException.class,
                () -> policy.assertReadableSizeWithinLimit(streamingFile("data.pdf", 16L, limit), limit));
    }

    @Test
    void batchRejectsWhenRealBytesExceedFiftyMebibytesBeforeAnyWrite() throws Exception
    {
        AdminFileUploadPolicy policy = new AdminFileUploadPolicy();
        long twentyMiB = 20L * 1024 * 1024;
        java.util.List<MultipartFile> files = java.util.Arrays.asList(
                streamingFile("a.pdf", twentyMiB, twentyMiB),
                streamingFile("b.pdf", twentyMiB, twentyMiB),
                streamingFile("c.pdf", twentyMiB, twentyMiB));

        // 3×20MiB=60MiB 超总量 50MiB，在任何内容校验/落盘前就按真实累计拒绝。
        assertThrows(AdminFileUploadService.RejectedException.class, () -> policy.prepareLocalBatch(files));
    }

    private static MultipartFile streamingFile(String name, long streamLength, long declaredSize)
    {
        return new MultipartFile()
        {
            @Override
            public String getName()
            {
                return "file";
            }

            @Override
            public String getOriginalFilename()
            {
                return name;
            }

            @Override
            public String getContentType()
            {
                return "application/octet-stream";
            }

            @Override
            public boolean isEmpty()
            {
                return streamLength == 0;
            }

            @Override
            public long getSize()
            {
                return declaredSize;
            }

            @Override
            public byte[] getBytes()
            {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.io.InputStream getInputStream()
            {
                return new java.io.InputStream()
                {
                    private long remaining = streamLength;

                    @Override
                    public int read()
                    {
                        if (remaining <= 0)
                        {
                            return -1;
                        }
                        remaining--;
                        return 0;
                    }

                    @Override
                    public int read(byte[] buffer, int off, int len)
                    {
                        if (remaining <= 0)
                        {
                            return -1;
                        }
                        int produced = (int) Math.min(len, remaining);
                        remaining -= produced;
                        return produced;
                    }
                };
            }

            @Override
            public void transferTo(java.io.File destination)
            {
                throw new UnsupportedOperationException();
            }
        };
    }

    private MockMvc controller(AdminFileUploadService service)
    {
        CommonController controller = new CommonController();
        ReflectionTestUtils.setField(controller, "adminFileUploadService", service);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private JSONObject ossResult()
    {
        JSONObject result = new JSONObject();
        result.put("fileName", "upload/safe.png");
        result.put("url", "https://media.example.test/upload/safe.png");
        return result;
    }

    private byte[] image(String format) throws Exception
    {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, format, bytes);
        return bytes.toByteArray();
    }
}
