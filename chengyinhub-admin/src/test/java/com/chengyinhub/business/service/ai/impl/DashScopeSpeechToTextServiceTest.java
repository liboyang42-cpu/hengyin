package com.chengyinhub.business.service.ai.impl;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.ai.AiHttpAdmission;
import org.springframework.test.util.ReflectionTestUtils;
import com.chengyinhub.common.config.AiVoiceConfig;
import com.sun.net.httpserver.HttpServer;
import cn.hutool.core.io.IoUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashScopeSpeechToTextServiceTest
{
    @Test
    void asr开关未开启时不可用()
    {
        AiVoiceConfig config = new AiVoiceConfig();
        config.setApiKey("sk-test");
        config.setWorkspaceId("workspace-test");

        DashScopeSpeechToTextService service = new DashScopeSpeechToTextService(
                config, "http://127.0.0.1:1/asr");

        assertFalse(service.available());
    }

    @Test
    void asr模型名缺失时不可用()
    {
        AiVoiceConfig config = usableConfig();
        config.setAsrModel(" ");

        DashScopeSpeechToTextService service = new DashScopeSpeechToTextService(
                config, "http://127.0.0.1:1/asr");

        assertFalse(service.available());
    }

    @Test
    void asr缺少key时不可用()
    {
        AiVoiceConfig config = usableConfig();
        config.setApiKey(" ");
        assertFalse(new DashScopeSpeechToTextService(config, "http://127.0.0.1:1/asr").available());
    }

    @Test
    void 北京区仅配置key时也可用共享域名()
    {
        AiVoiceConfig config = usableConfig();
        config.setWorkspaceId(null);
        config.setRegion("cn-beijing");

        assertEquals("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
                config.asrUrl());
        assertTrue(new DashScopeSpeechToTextService(config, null).available());
    }

    @Test
    void 非北京区缺少workspace时拒绝生成北京域名()
    {
        AiVoiceConfig config = usableConfig();
        config.setWorkspaceId(null);
        config.setRegion("ap-southeast-1");

        assertFalse(new DashScopeSpeechToTextService(config, null).available());
        assertThrows(IllegalStateException.class, config::asrUrl);
    }

    @Test
    void mp3转写发送百炼请求并返回最终文本() throws Exception
    {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> sse = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/asr", exchange -> {
            requestBody.set(IoUtil.readUtf8(exchange.getRequestBody()));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            sse.set(exchange.getRequestHeaders().getFirst("X-DashScope-SSE"));
            byte[] response = "{\"output\":{\"text\":\"营业到几点？\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try
        {
            AiVoiceConfig config = usableConfig();
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/asr";
            DashScopeSpeechToTextService service = admittedService(config, endpoint);

            String text = service.transcribe("ID3-test".getBytes(StandardCharsets.UTF_8), "mp3");

            assertEquals("营业到几点？", text);
            assertEquals("Bearer sk-test", authorization.get());
            assertEquals("application/json", contentType.get());
            assertEquals("disable", sse.get());
            JSONObject sent = JSON.parseObject(requestBody.get());
            assertEquals("fun-asr-flash-2026-06-15", sent.getString("model"));
            assertEquals("mp3", sent.getJSONObject("parameters").getString("format"));
            JSONObject message = sent.getJSONObject("input").getJSONArray("messages").getJSONObject(0);
            assertEquals("user", message.getString("role"));
            JSONObject content = message.getJSONArray("content").getJSONObject(0);
            assertEquals("input_audio", content.getString("type"));
            String data = content.getJSONObject("input_audio").getString("data");
            assertEquals("data:audio/mpeg;base64,SUQzLXRlc3Q=", data);
        }
        finally
        {
            server.stop(0);
        }
    }

    @Test
    void 百炼业务错误必须抛出而不是伪装成没听清() throws Exception
    {
        HttpServer server = fixedResponse(200, "{\"code\":\"InvalidParameter\",\"message\":\"bad audio\"}");
        try
        {
            DashScopeSpeechToTextService service = admittedService(usableConfig(), endpoint(server));

            assertThrows(AiServiceException.class,
                    () -> service.transcribe(new byte[]{'x'}, "mp3"));
        }
        finally
        {
            server.stop(0);
        }
    }

    @Test
    void 没有识别出文字时返回null() throws Exception
    {
        HttpServer server = fixedResponse(200, "{\"output\":{\"text\":\"   \"}}");
        try
        {
            DashScopeSpeechToTextService service = admittedService(usableConfig(), endpoint(server));

            assertNull(service.transcribe(new byte[]{'x'}, "wav"));
        }
        finally
        {
            server.stop(0);
        }
    }

    @Test
    void 非白名单音频格式在发请求前拒绝()
    {
        DashScopeSpeechToTextService service = new DashScopeSpeechToTextService(
                usableConfig(), "http://127.0.0.1:1/asr");

        assertThrows(IllegalArgumentException.class,
                () -> service.transcribe(new byte[]{'x'}, "flac"));
    }

    @ParameterizedTest
    @CsvSource({
            "mp3,audio/mpeg",
            "wav,audio/wav",
            "m4a,audio/mp4",
            "aac,audio/aac"
    })
    void 声明支持的格式都使用对应DataUri(String format, String mimeType) throws Exception
    {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/asr", exchange -> {
            requestBody.set(IoUtil.readUtf8(exchange.getRequestBody()));
            byte[] response = "{\"output\":{\"text\":\"收到\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try
        {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/asr";
            DashScopeSpeechToTextService service = admittedService(usableConfig(), endpoint);

            assertEquals("收到", service.transcribe(new byte[]{'x'}, format));

            JSONObject sent = JSON.parseObject(requestBody.get());
            assertEquals(format, sent.getJSONObject("parameters").getString("format"));
            String data = sent.getJSONObject("input").getJSONArray("messages").getJSONObject(0)
                    .getJSONArray("content").getJSONObject(0).getJSONObject("input_audio").getString("data");
            assertEquals("data:" + mimeType + ";base64,eA==", data);
        }
        finally
        {
            server.stop(0);
        }
    }

    private DashScopeSpeechToTextService admittedService(AiVoiceConfig config, String endpoint)
    {
        DashScopeSpeechToTextService service = new DashScopeSpeechToTextService(config, endpoint);
        ReflectionTestUtils.setField(service, "admission", new AiHttpAdmission(java.util.List.of(endpoint)));
        return service;
    }

    private AiVoiceConfig usableConfig()
    {
        AiVoiceConfig config = new AiVoiceConfig();
        config.setAsrEnabled(true);
        config.setApiKey("sk-test");
        config.setWorkspaceId("workspace-test");
        config.setAsrModel("fun-asr-flash-2026-06-15");
        config.setTimeoutMs(3000);
        return config;
    }

    private HttpServer fixedResponse(int status, String body) throws Exception
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/asr", exchange -> {
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private String endpoint(HttpServer server)
    {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/asr";
    }
}
