package com.chengyinhub.business.service.ai;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #398 F3:供应商响应体可能回显 prompt / 检索事实 / 密钥,日志与对外异常都不得携带响应体或原始异常信息。
 */
class OpenAiCompatibleLlmClientLogRedactionTest {

    private static final String SECRET = "sk-leaked-SECRET-echo-PROMPT";

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger logger;
    private HttpServer server;

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
        if (server != null) {
            server.stop(0);
        }
    }

    private OpenAiCompatibleLlmClient clientReturning(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new OpenAiCompatibleLlmClient("text", baseUrl,
                "sk-test-key", "qwen-plus", true, 3000, new AiHttpAdmission(java.util.List.of(baseUrl)));
    }

    private void assertNoLeak(RuntimeException thrown) {
        assertFalse(thrown.getMessage().contains(SECRET), "对外异常信息不得回显响应体");
        assertTrue(thrown.getCause() == null, "对外异常不得挂原始 cause");
        assertEquals(0, thrown.getSuppressed().length);
        assertFalse(thrown instanceof AiProviderNotSentException, "received/provider errors must never refund as NOT_SENT");
        assertFalse(logs.list.isEmpty(), "失败仍需留运维日志");
        for (ILoggingEvent event : logs.list) {
            assertFalse(event.getFormattedMessage().contains(SECRET), "日志正文不得包含响应体");
            assertTrue(event.getThrowableProxy() == null, "日志不得附带原始异常栈(其 message 可含响应字节)");
        }
    }

    @Test
    void 非2xx响应体不进日志也不进异常() throws Exception {
        OpenAiCompatibleLlmClient client = clientReturning(500, "{\"error\":\"" + SECRET + "\"}");
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> client.chat("sys", "user"));
        assertNoLeak(thrown);
        assertTrue(thrown.getMessage().contains("HTTP=500"));
    }

    @Test
    void 解析失败时异常信息不回显响应字节() throws Exception {
        OpenAiCompatibleLlmClient client = clientReturning(200, "not-json " + SECRET);
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> client.chat("sys", "user"));
        assertNoLeak(thrown);
        assertTrue(thrown.getMessage().contains("phase=parse"));
    }
}
