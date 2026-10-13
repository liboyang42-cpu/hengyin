package com.chengyinhub.web.controller.common;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.chengyinhub.common.config.RuoYiConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * P1-FILE H-3/M-2:旧 {@code fileName/delete} 合同曾让任意已认证用户按已知文件名读取/删除
 * download 目录下任意文件。合同废止后，任何输入均须 fail-closed：稳定、不泄露存在性、绝不读盘、绝不删盘。
 */
class CommonControllerDownloadContractTest
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
    void legacyFileNameContractDoesNotReadOrDeleteFixture(@TempDir Path tempDir) throws Exception
    {
        new RuoYiConfig().setProfile(tempDir.toString());
        Path downloadDir = tempDir.resolve("download");
        Files.createDirectories(downloadDir);
        byte[] secret = "TOP-SECRET-EXPORT-BYTES".getBytes(StandardCharsets.UTF_8);
        Path fixture = downloadDir.resolve("11111111-2222-3333-4444-555555555555_report.xlsx");
        Files.write(fixture, secret);

        MockHttpServletResponse res = mvc().perform(get("/common/download")
                        .param("fileName", fixture.getFileName().toString())
                        .param("delete", "true"))
                .andReturn().getResponse();

        assertEquals(403, res.getStatus(), "旧 fileName 合同必须稳定拒绝");
        assertFalse(java.util.Arrays.equals(secret, res.getContentAsByteArray()),
                "响应体不得包含被请求文件的内容");
        assertNull(res.getHeader("download-filename"), "不得设置下载文件名头");
        assertTrue(Files.exists(fixture), "被请求文件不得被删除");
        assertArrayEquals(secret, Files.readAllBytes(fixture), "被请求文件内容不得被改动");
    }

    @Test
    void missingIllegalAndEmptyRequestsRejectIdenticallyWithoutDeletingFixture(@TempDir Path tempDir) throws Exception
    {
        new RuoYiConfig().setProfile(tempDir.toString());
        Path downloadDir = tempDir.resolve("download");
        Files.createDirectories(downloadDir);
        byte[] secret = "TOP-SECRET-EXPORT-BYTES".getBytes(StandardCharsets.UTF_8);
        Path fixture = downloadDir.resolve("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee_data.xlsx");
        Files.write(fixture, secret);

        MockHttpServletResponse existing = mvc().perform(get("/common/download")
                .param("fileName", fixture.getFileName().toString()).param("delete", "true"))
                .andReturn().getResponse();
        MockHttpServletResponse missing = mvc().perform(get("/common/download")
                .param("fileName", "does-not-exist_ghost.xlsx").param("delete", "true"))
                .andReturn().getResponse();
        MockHttpServletResponse traversal = mvc().perform(get("/common/download")
                .param("fileName", "../../etc/passwd").param("delete", "true"))
                .andReturn().getResponse();
        MockHttpServletResponse empty = mvc().perform(get("/common/download"))
                .andReturn().getResponse();

        assertEquals(403, existing.getStatus());
        assertEquals(existing.getStatus(), missing.getStatus());
        assertEquals(existing.getStatus(), traversal.getStatus());
        assertEquals(existing.getStatus(), empty.getStatus());
        assertEquals(existing.getContentAsString(), missing.getContentAsString());
        assertEquals(existing.getContentAsString(), traversal.getContentAsString());
        assertEquals(existing.getContentAsString(), empty.getContentAsString());
        assertTrue(Files.exists(fixture), "任何拒绝路径都不得删除 fixture");
        assertArrayEquals(secret, Files.readAllBytes(fixture), "任何拒绝路径都不得改动 fixture");
    }

    private MockMvc mvc()
    {
        return MockMvcBuilders.standaloneSetup(new CommonController()).build();
    }
}
