package com.chengyinhub.business.service.ai.impl;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抠图结果的真实下载(不桩传输层,本地起一个 HTTP 服务)。
 *
 * <p>钉的是一件只有真走网络才看得见的事:5MB 上限必须在**读进内存之前**生效。
 * 2026-09-23 监工实测:hutool 同步 {@code execute()} 会先把整个响应读完再交出流,
 * 40MB 的响应服务端写出 40MB / 40MB、堆涨约 112MB,后面的截断只是截一个已在内存里的数组。
 * 生产 Java 进程最大堆 1GB,一个异常大的响应就能把它撑爆。
 */
class CardCutoutDownloadTest {

    private static final long HUGE = 64L * 1024 * 1024;
    /** 读到上限就断开时,服务端最多多写出 TCP 两端缓冲那么多;远小于 64MB 就说明没被整个读进来。 */
    private static final long WRITTEN_CEILING = 32L * 1024 * 1024;

    private HttpServer server;
    private final AtomicLong written = new AtomicLong();
    private OssCardCutoutStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sized", ex -> stream(ex, HUGE, HUGE));
        server.createContext("/chunked", ex -> stream(ex, 0, HUGE));   // 0 = 分块,不给长度
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", "/sized");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.start();
        storage = new OssCardCutoutStorage();
        ReflectionTestUtils.setField(storage, "downloadTimeoutMs", 10000);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 声明了超大长度_不被整个读进内存() {
        assertCapped(storage.download(url("/sized"), OssCardCutoutStorage.MAX_PNG_BYTES));
    }

    /** 分块响应没有长度可看,只能边读边截 —— 这是上限真正要守的那条路。 */
    @Test
    void 分块传输的超大响应_读到上限就断开() {
        assertCapped(storage.download(url("/chunked"), OssCardCutoutStorage.MAX_PNG_BYTES));
    }

    /** 重定向可能跳到白名单外的主机,绕过转存前的主机闸:显式不跟随,不依赖 hutool 的全局默认值。 */
    @Test
    void 不跟随重定向() {
        assertNull(storage.download(url("/redirect"), OssCardCutoutStorage.MAX_PNG_BYTES));
        assertTrue(written.get() == 0, "跟随了重定向去读 /sized");
    }

    /** 先钉内存(服务端写出多少),再钉交出去的字节不超过「上限 + 1」—— 超限判拒是 transfer 的事。 */
    private void assertCapped(byte[] got) {
        assertTrue(written.get() < WRITTEN_CEILING, "服务端写出了 " + mb(written.get()) + ",响应被整个读进了内存");
        assertTrue(got == null || got.length <= OssCardCutoutStorage.MAX_PNG_BYTES + 1,
                "交出去 " + (got == null ? 0 : got.length) + " 字节,超过了上限 + 1");
    }

    private void stream(com.sun.net.httpserver.HttpExchange ex, long declared, long total) {
        byte[] chunk = new byte[64 * 1024];
        chunk[0] = (byte) 0x89;
        try {
            ex.sendResponseHeaders(200, declared);
            try (OutputStream os = ex.getResponseBody()) {
                for (long n = 0; n < total; n += chunk.length) {
                    os.write(chunk);
                    written.addAndGet(chunk.length);
                }
            }
        } catch (Exception clientGone) {
            // 客户端读到上限就断开:这正是期望的结果
        } finally {
            ex.close();
        }
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static String mb(long bytes) {
        return (bytes / 1024 / 1024) + "MB";
    }
}
