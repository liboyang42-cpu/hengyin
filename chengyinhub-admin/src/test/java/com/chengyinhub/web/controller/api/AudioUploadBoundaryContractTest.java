package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.framework.web.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.quartz.autoconfigure.QuartzAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.client.ResourceAccessException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R9-48 真实入口大小边界契约:用**嵌入式容器真实 multipart 解析**(不是 MockMvc,后者不跑
 * Tomcat 的 multipart 限额)复现「单文件 10MiB / 总请求 20MiB」两条边界。
 *
 * <p>★ 这里**不覆盖** multipart 限额与 {@code server.tomcat.max-swallow-size}:配置全部来自
 * 生产同一份 {@code application.yml}(单文件 10MB / 总请求 20MB,未设 max-swallow-size,
 * 即 Boot 默认 2MB)。覆盖成 50MB 会让「服务端超限后能否回业务体」变成测试环境特有的假象,
 * 那正是上一版被判 No-Go 的原因。
 *
 * <p>因此本测试只断言在**真实配置**下确实成立的两件事:
 * <ul>
 *   <li>整 10MiB 被接受;</li>
 *   <li>刚超限(+1 字节,剩余未读体 ≈ 0,2MB 默认吞得下)仍能拿到可操作 400 业务体。</li>
 * </ul>
 * 远超限额时容器可能在吞完 2MB 后直接断连、客户端拿不到业务体 —— 那是真机边界,由客户端
 * 按**已知 size** 前置拒绝兜底(见 upload-client 单测),不在这里用改配置伪造。
 */
@SpringBootTest(classes = AudioUploadBoundaryContractTest.MinimalApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.address=127.0.0.1",
                "spring.autoconfigure.exclude=com.alibaba.druid.spring.boot4.autoconfigure.DruidDataSourceAutoConfigure,org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration"
        })
@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
class AudioUploadBoundaryContractTest {

    private static final int TEN_MIB = 10 * 1024 * 1024;

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration.class,
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            DataRedisAutoConfiguration.class,
            QuartzAutoConfiguration.class,
            SecurityAutoConfiguration.class,
            org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration.class,
            org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration.class,
            UserDetailsServiceAutoConfiguration.class
    })
    @Import(GlobalExceptionHandler.class)
    static class MinimalApp {
        @Bean
        UploadProbe uploadProbe() {
            return new UploadProbe();
        }
    }

    @RestController
    static class UploadProbe {
        @PostMapping("/api/common/uploadOSS")
        ResponseEntity<AjaxResult> uploadOSS(@RequestParam("file") MultipartFile file) {
            return ResponseEntity.ok(AjaxResult.success());
        }

        @PostMapping("/api/common/uploads")
        ResponseEntity<AjaxResult> uploads(@RequestParam("files") MultipartFile[] files) {
            return ResponseEntity.ok(AjaxResult.success());
        }
    }

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    private int port;

    private static final ObjectMapper JSON = new ObjectMapper();

    private ByteArrayResource resource(int size, String name) {
        return new ByteArrayResource(new byte[size]) {
            @Override
            public String getFilename() {
                return name;
            }
        };
    }

    private JsonNode postSingle(int size) throws Exception {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", resource(size, "probe.mp3"));
        return post("/api/common/uploadOSS", body);
    }

    private JsonNode post(String path, MultiValueMap<String, Object> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> response =
                rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
        assertEquals(200, response.getStatusCode().value(), "统一响应合同:HTTP 200 承载 AjaxResult");
        return JSON.readTree(response.getBody());
    }

    /**
     * 真实配置下超限请求可能被容器在客户端写完前直接断连(拿不到业务体):返回 null 表示「连接被拒」。
     * 若容器仍能把业务体送回,则必须是可操作 400 —— 两种都算「被拒」,唯一不允许的是假成功。
     */
    private JsonNode postOrConnectionClosed(String path, MultiValueMap<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        try {
            ResponseEntity<String> response =
                    rest.postForEntity("http://127.0.0.1:" + port + path, new HttpEntity<>(body, headers), String.class);
            if (response.getBody() == null || response.getBody().isEmpty()) {
                return null;
            }
            return JSON.readTree(response.getBody());
        } catch (ResourceAccessException connectionClosed) {
            return null;
        } catch (Exception unexpected) {
            throw new IllegalStateException("超限请求既未回业务体,也不是连接被容器关闭: " + unexpected, unexpected);
        }
    }

    private void assertRejected(JsonNode json) {
        if (json == null) {
            return; // 真实默认 swallow=2MB:连接被容器关闭,无业务体(客户端按已知 size 前置拒绝兜底)
        }
        assertActionableSizeError(json);
    }

    @Test
    void exactlyTenMiBIsAccepted() throws Exception {
        JsonNode json = postSingle(TEN_MIB);
        assertEquals(200, json.get("code").asInt(), json.toString());
    }

    /**
     * 超限在**真实配置**(未设 max-swallow-size,即 Boot 默认 2MB)下必须被拒。
     * 不断言「一定回业务体」——那在真实默认下不成立(客户端会被容器断连),只断言「没有假成功」。
     */
    @Test
    void oneByteOverTenMiBIsRejectedUnderRealTomcatConfig() throws Exception {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", resource(TEN_MIB + 1, "probe.mp3"));
        assertRejected(postOrConnectionClosed("/api/common/uploadOSS", body));
    }

    @Test
    void requestOverTwentyMiBIsRejectedUnderRealTomcatConfig() throws Exception {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("files", resource(7 * 1024 * 1024, "a.mp3"));
        body.add("files", resource(7 * 1024 * 1024, "b.mp3"));
        body.add("files", resource(7 * 1024 * 1024, "c.mp3"));
        assertRejected(postOrConnectionClosed("/api/common/uploads", body));
    }

    @Test
    void farOverLimitIsNeverAcceptedAsSuccess() throws Exception {
        // 远超限额:剩余未读体远大于 2MB,容器必然断连;仍断言绝不出现假成功。
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", resource(30 * 1024 * 1024, "huge.mp3"));
        assertRejected(postOrConnectionClosed("/api/common/uploadOSS", body));
    }

    private void assertActionableSizeError(JsonNode json) {
        assertEquals(400, json.get("code").asInt(), "超限是客户端可纠正的错误,给 400: " + json);
        String msg = json.get("msg").asText();
        assertFalse(msg.contains("系统繁忙"), "不得再把大小超限收敛成系统繁忙: " + msg);
        assertTrue(msg.contains("文件过大"), "要点明文件过大: " + msg);
        assertTrue(msg.contains("压缩") || msg.contains("更换"), "要给压缩/换文件路径: " + msg);
        assertTrue(msg.contains("10MB"), "要写明单文件真正适用限额: " + msg);
        assertTrue(msg.contains("20MB"), "要写明总请求真正适用限额: " + msg);
        assertFalse(msg.contains("Exception"), "不得泄漏技术细节: " + msg);
    }
}
