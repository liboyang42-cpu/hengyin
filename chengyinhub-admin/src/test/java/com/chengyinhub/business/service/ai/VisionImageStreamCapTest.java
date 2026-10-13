package com.chengyinhub.business.service.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拍照审核两处远程取图的真实下载(不桩传输层,本地起一个 HTTP 服务)。
 *
 * <p>钉的是一件只有真走网络才看得见的事:上限必须在**读进内存之前**生效。
 * 2026-09-23 实测:hutool 同步 {@code execute()} 会先把整个响应读完再交出流,
 * 40MB 的响应服务端写出 40MB / 40MB、堆涨约 112MB,后面的截断只是截一个已在内存里的数组。
 * 生产 Java 进程最大堆 1GB。所以这里钉「服务端实际写出量」,而不是只看返回值。
 */
class VisionImageStreamCapTest {

    private static final long HUGE = 96L * 1024 * 1024;
    /** 读到上限就断开时,服务端最多多写出 TCP 两端缓冲那么多;远小于 96MB 就说明没被整个读进来。 */
    private static final long WRITTEN_CEILING = 16L * 1024 * 1024;

    private HttpServer server;
    private final AtomicLong written = new AtomicLong();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 两条都无视 Range 头,直接 200 全量:探针要防的正是这种服务端
        server.createContext("/sized", ex -> stream(ex, HUGE));
        server.createContext("/chunked", ex -> stream(ex, 0));   // 0 = 分块,不给长度
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // ---- VisionImageEncoder.download:整图下载,上限 32MB ----

    /** 声明长度已超 32MB:一个字节都不该读,服务端最多写出 TCP 缓冲那么多。 */
    @Test
    void 下载_声明超大长度_读之前就拒() {
        VisionImageEncoder.Encoded encoded = openNetEncoder().encode(url("/sized"));

        assertTrue(encoded.isRejected());
        assertEquals(VisionImageGuard.REASON_TOO_LARGE, encoded.getRejectReason());
        assertWrittenBelow(WRITTEN_CEILING);
    }

    /** 分块没有长度可看,只能边读边截:读到 32MB+1 就断,服务端写不到 96MB。 */
    @Test
    void 下载_分块超大响应_读到上限就断开() {
        VisionImageEncoder.Encoded encoded = openNetEncoder().encode(url("/chunked"));

        assertTrue(encoded.isRejected());
        assertEquals(VisionImageGuard.REASON_TOO_LARGE, encoded.getRejectReason());
        assertWrittenBelow(VisionImageGuard.MAX_BYTES + WRITTEN_CEILING);
    }

    // ---- HttpVisionImageProbe:只要头部 128KiB ----

    @Test
    void 探针_服务端忽略Range回200全量_只读头部就停() {
        VisionImageProbe.ImageHead head = new HttpVisionImageProbe().fetchHead(url("/sized"));

        assertNotNull(head);
        assertEquals(HUGE, head.getContentLength(), "200 全量时整图大小取 Content-Length");
        assertEquals(HttpVisionImageProbe.HEAD_BYTES, head.getHeadBytes().length);
        assertWrittenBelow(WRITTEN_CEILING);
    }

    @Test
    void 探针_分块全量_只读头部就停() {
        VisionImageProbe.ImageHead head = new HttpVisionImageProbe().fetchHead(url("/chunked"));

        assertNotNull(head);
        assertEquals(HttpVisionImageProbe.HEAD_BYTES, head.getHeadBytes().length);
        assertWrittenBelow(WRITTEN_CEILING);
    }

    private void assertWrittenBelow(long ceiling) {
        assertTrue(written.get() < ceiling,
                "服务端写出了 " + (written.get() / 1024 / 1024) + "MB,响应被整个读进了内存");
    }

    /** 127.0.0.1 在生产出网闸里会被拒,测试构造器放开,专测下载这条链。 */
    private static VisionImageEncoder openNetEncoder() {
        return new VisionImageEncoder(new VisionImageGuard(), ignored -> true);
    }

    private void stream(HttpExchange ex, long declared) {
        byte[] chunk = new byte[64 * 1024];
        chunk[0] = (byte) 0x89;   // PNG 魔数开头,像张真图
        try {
            ex.sendResponseHeaders(200, declared);
            try (OutputStream os = ex.getResponseBody()) {
                for (long n = 0; n < HUGE; n += chunk.length) {
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
}
