package com.chengyinhub.web.controller.common;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.chengyinhub.common.config.RuoYiConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * P1-FILE M-2:GET /common/download/resource 的错误合同必须稳定。
 *
 * <p>旧实现 catch (Exception) 后只 log.error,不写响应体/状态:文件缺失或参数非法时返回
 * 200 + 已设置的 octet-stream 空/部分响应,前端保存空文件,且异常文本可能带绝对路径。
 * 本测试把「稳定 JSON 错误合同 + 不泄露绝对路径/堆栈」写成负控:先红(旧实现返回空 body),
 * 修复后绿。
 */
class CommonControllerResourceDownloadContractTest
{
    private String originalProfile;

    @BeforeEach
    void rememberProfile()
    {
        originalProfile = RuoYiConfig.getProfile();
    }

    @AfterEach
    void restoreProfile()
    {
        new RuoYiConfig().setProfile(originalProfile);
    }

    @Test
    void missingAllowedFile_returnsStableJsonErrorWithoutLeakingPath(@TempDir Path tempDir) throws Exception
    {
        new RuoYiConfig().setProfile(tempDir.toString());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new CommonController()).build();

        MockHttpServletResponse res = mvc.perform(get("/common/download/resource")
                .param("resource", "/profile/upload/does-not-exist_ghost.xlsx"))
                .andReturn().getResponse();

        // 稳定错误合同:非 200 空 octet-stream;是稳定非 2xx + application/json 错误体
        assertEquals(404, res.getStatus(), "缺失文件必须返回稳定非 2xx 状态码");
        assertTrue(res.getContentType() != null && res.getContentType().startsWith(MediaType.APPLICATION_JSON_VALUE),
                "缺失文件必须返回 JSON 错误体,不能是空 octet-stream 200,实际: " + res.getContentType());
        String body = res.getContentAsString();
        assertTrue(body.contains("404"), "错误体应带稳定错误码,实际: " + body);
        assertFalse(body.contains("does-not-exist_ghost.xlsx"), "错误体不得回显资源路径: " + body);
        assertFalse(body.contains(tempDir.toString()), "错误体不得泄露本地绝对路径: " + body);
        assertFalse(body.contains("FileNotFoundException"), "错误体不得泄露堆栈/异常类名: " + body);
        assertFalse(body.contains("java.io"), "错误体不得泄露包名/堆栈: " + body);
    }

    @Test
    void traversalResource_returnsStableJsonError(@TempDir Path tempDir) throws Exception
    {
        new RuoYiConfig().setProfile(tempDir.toString());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new CommonController()).build();

        MockHttpServletResponse res = mvc.perform(get("/common/download/resource")
                .param("resource", "../../etc/passwd"))
                .andReturn().getResponse();

        assertTrue(res.getStatus() == 400 || res.getStatus() == 404,
                "穿越参数必须返回稳定非 2xx 状态码,实际: " + res.getStatus());
        assertTrue(res.getContentType() != null && res.getContentType().startsWith(MediaType.APPLICATION_JSON_VALUE),
                "穿越参数必须返回 JSON 错误体,不能是空 octet-stream 200,实际: " + res.getContentType());
        String body = res.getContentAsString();
        assertTrue(body.contains("400") || body.contains("404"), "错误体应带稳定错误码,实际: " + body);
        assertFalse(body.contains("passwd"), "错误体不得回显资源值: " + body);
    }

    @Test
    void existingResource_stillStreamsBytes(@TempDir Path tempDir) throws Exception
    {
        new RuoYiConfig().setProfile(tempDir.toString());
        Path dir = tempDir.resolve("upload");
        Files.createDirectories(dir);
        byte[] payload = "REPORT-BYTE-PAYLOAD".getBytes(StandardCharsets.UTF_8);
        Files.write(dir.resolve("report.xlsx"), payload);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new CommonController()).build();
        MockHttpServletResponse res = mvc.perform(get("/common/download/resource")
                .param("resource", "/profile/upload/report.xlsx"))
                .andReturn().getResponse();

        assertEquals(200, res.getStatus(), "存在的资源必须正常返回文件字节");
        assertArrayEquals(payload, res.getContentAsByteArray(), "文件字节必须原样返回");
        assertTrue(res.getContentType() == null || !MediaType.APPLICATION_JSON_VALUE.equals(res.getContentType()),
                "成功下载不应是 JSON 错误体");
    }
}
