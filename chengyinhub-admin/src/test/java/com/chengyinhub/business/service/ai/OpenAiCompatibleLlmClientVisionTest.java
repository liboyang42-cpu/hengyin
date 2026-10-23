package com.chengyinhub.business.service.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import cn.hutool.core.io.IoUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * chatVision 的 HTTP 契约:对真实 socket 发一次请求,验证 OpenAI vision 请求体与鉴权头。
 * 生产上没有 DashScope key,这是唯一能把「请求长什么样」钉死的地方。
 */
class OpenAiCompatibleLlmClientVisionTest {

    @Test
    void 视觉请求体是OpenAI数组格式且鉴权头正确() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(IoUtil.readUtf8(exchange.getRequestBody()));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            JSONObject message = new JSONObject();
            message.put("content", "{\"pass\":true,\"confidence\":88,\"reason\":\"拍到了\"}");
            JSONObject choice = new JSONObject();
            choice.put("message", message);
            JSONArray choices = new JSONArray();
            choices.add(choice);
            JSONObject root = new JSONObject();
            root.put("choices", choices);
            byte[] response = root.toJSONString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                    "vision", baseUrl, "sk-test-key", "qwen-vl-max", true, 3000, new AiHttpAdmission(java.util.List.of(baseUrl)));

            String answer = client.chatVision("你是照片审核员", "https://cdn.example.com/window.jpg", "要拍到窗外");

            assertEquals("{\"pass\":true,\"confidence\":88,\"reason\":\"拍到了\"}", answer,
                    "HTTP 出口必须原样取回 choices[0].message.content");

            JSONObject sent = JSON.parseObject(requestBody.get());
            assertEquals("qwen-vl-max", sent.getString("model"));
            assertEquals("Bearer sk-test-key", authorization.get());

            JSONArray content = sent.getJSONArray("messages").getJSONObject(1).getJSONArray("content");
            assertEquals("image_url", content.getJSONObject(0).getString("type"));
            assertEquals("https://cdn.example.com/window.jpg",
                    content.getJSONObject(0).getJSONObject("image_url").getString("url"));
            assertEquals("text", content.getJSONObject(1).getString("type"));
            assertEquals("要拍到窗外", content.getJSONObject(1).getString("text"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 负控_未配置key时不发请求直接抛() {
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                "vision", "http://127.0.0.1:1", "", "qwen-vl-max", true, 500);

        assertThrows(IllegalStateException.class,
                () -> client.chatVision("你是照片审核员", "https://cdn.example.com/window.jpg", "要拍到窗外"));
    }
}
