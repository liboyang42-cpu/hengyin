package com.chengyinhub.business.service.ai;

import com.chengyinhub.common.config.RuoYiConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片编码契约:站内路径直接读本机文件,远程回落下载,统一 base64 data URL;
 * MIME 一律按魔数判,判不出就拒(走降级),绝不按扩展名猜、也绝不把原 URL 发给模型。
 *
 * <p>2026-09-18 真 key 实测:URL 直发模型不可靠(站内 400 unsupported image / 外站 400 failed to download),
 * base64 一次就过 —— 这里的断言就是防止有人把 URL 路改回去。
 */
class VisionImageEncoderTest {

    private final VisionImageEncoder encoder = new VisionImageEncoder();
    private Path profileDir;
    private String originalProfile;

    @BeforeEach
    void setUp() throws Exception {
        profileDir = Files.createTempDirectory("vision-encoder");
        originalProfile = RuoYiConfig.getProfile();
        new RuoYiConfig().setProfile(profileDir.toString());
        assertTrue(profileDir.resolve("upload").toFile().mkdirs());
    }

    @AfterEach
    void tearDown() {
        new RuoYiConfig().setProfile(originalProfile);
        deleteRecursively(profileDir.toFile());
    }

    @Test
    void 站内路径读本地文件_扩展名jpg内容是png也按魔数判() throws Exception {
        byte[] png = png(2, 2);
        write("upload/pic.jpg", png);

        VisionImageEncoder.Encoded encoded = encoder.encode("/profile/upload/pic.jpg");

        assertFalse(encoded.isRejected(), encoded.getRejectReason());
        assertTrue(encoded.getDataUrl().startsWith("data:image/png;base64,"),
                "MIME 必须按真实字节判,不能按扩展名猜:" + encoded.getDataUrl().substring(0, 30));
        assertEquals(Base64.getEncoder().encodeToString(png),
                encoded.getDataUrl().substring("data:image/png;base64,".length()));
    }

    @Test
    void 站内绝对URL同样命中本地文件_不绕HTTP下载自己() throws Exception {
        write("upload/pic.jpg", jpeg(2, 2));

        VisionImageEncoder.Encoded encoded =
                encoder.encode("https://www.chengyinhub.com/prod-api/profile/upload/pic.jpg");

        assertFalse(encoded.isRejected(), encoded.getRejectReason());
        assertTrue(encoded.getDataUrl().startsWith("data:image/jpeg;base64,"));
    }

    @Test
    void 负控_扩展名jpg但内容不是支持的图片_判不支持() throws Exception {
        write("upload/fake.jpg", "这不是任何图片格式".getBytes("UTF-8"));

        VisionImageEncoder.Encoded encoded = encoder.encode("/profile/upload/fake.jpg");

        assertTrue(encoded.isRejected(), "魔数判不出格式必须拒,绝不能发给模型等它报错");
        assertTrue(encoded.getRejectReason().contains("格式不支持"), encoded.getRejectReason());
    }

    @Test
    void 负控_超过32MB在读取后发出前拒掉() throws Exception {
        File big = write("upload/big.jpg", jpeg(2, 2));
        try (RandomAccessFile raf = new RandomAccessFile(big, "rw")) {
            raf.setLength(VisionImageGuard.MAX_BYTES + 1);
        }

        VisionImageEncoder.Encoded encoded = encoder.encode("/profile/upload/big.jpg");

        assertTrue(encoded.isRejected(), "超限必须在发请求前拒");
        assertTrue(encoded.getRejectReason().contains("32MB"), encoded.getRejectReason());
    }

    @Test
    void 负控_站内路径穿越直接拒不可读本机其他文件() {
        VisionImageEncoder.Encoded encoded = encoder.encode("/profile/../application.yml");

        assertTrue(encoded.isRejected());
        assertTrue(encoded.getRejectReason().contains("不合法"), encoded.getRejectReason());
    }

    @Test
    void 负控_站内路径文件不存在且非HTTP地址_拒() {
        VisionImageEncoder.Encoded encoded = encoder.encode("/profile/upload/missing.jpg");

        assertTrue(encoded.isRejected());
    }

    @Test
    void 远程回落下载后转base64_MIME仍按魔数判() throws Exception {
        byte[] png = png(2, 2);
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/remote/pic.jpg", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, png.length);
            exchange.getResponseBody().write(png);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/remote/pic.jpg";
            // 127.0.0.1 在生产的出网闸里会被拒,测试构造器放开,专测下载→base64 这条链
            VisionImageEncoder openNetEncoder =
                    new VisionImageEncoder(new VisionImageGuard(), ignored -> true);

            VisionImageEncoder.Encoded encoded = openNetEncoder.encode(url);

            assertFalse(encoded.isRejected(), encoded.getRejectReason());
            assertTrue(encoded.getDataUrl().startsWith("data:image/png;base64,"),
                    "URL 扩展名是 .jpg,内容 PNG —— 必须按魔数判");
            assertEquals(1, hits.get(), "站外地址应回落下载,且只下一次");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 负控_默认出网闸拒本机内网地址且不发请求() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/remote/pic.jpg", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/remote/pic.jpg";

            VisionImageEncoder.Encoded encoded = encoder.encode(url);

            assertTrue(encoded.isRejected(), "本机/内网字面量不该被我们服务器去抓");
            assertEquals(0, hits.get(), "被出网闸拒掉就不该发出请求");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 负控_远程下载失败给降级理由不把URL发出去() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/missing.jpg", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/missing.jpg";
            VisionImageEncoder openNetEncoder =
                    new VisionImageEncoder(new VisionImageGuard(), ignored -> true);

            VisionImageEncoder.Encoded encoded = openNetEncoder.encode(url);

            assertTrue(encoded.isRejected(), "下载不到必须降级");
            assertTrue(encoded.getRejectReason().contains("读取失败"), encoded.getRejectReason());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dataURL按魔数归一化MIME_声明与字节不符以字节为准() throws Exception {
        byte[] png = png(2, 2);
        String declaredJpeg = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(png);

        VisionImageEncoder.Encoded encoded = encoder.encode(declaredJpeg);

        assertFalse(encoded.isRejected(), encoded.getRejectReason());
        assertTrue(encoded.getDataUrl().startsWith("data:image/png;base64,"),
                "声明 image/jpeg 但字节是 PNG,必须按字节改判");
    }

    @Test
    void 负控_dataURL魔数判不出格式拒() {
        VisionImageEncoder.Encoded encoded = encoder.encode("data:image/bmp;base64,Qk0AAAAA");

        assertTrue(encoded.isRejected());
        assertTrue(encoded.getRejectReason().contains("格式不支持"), encoded.getRejectReason());
    }

    private File write(String relative, byte[] bytes) throws Exception {
        Path target = profileDir.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        return target.toFile();
    }

    private static byte[] jpeg(int width, int height) throws Exception {
        return render(width, height, "jpeg");
    }

    private static byte[] png(int width, int height) throws Exception {
        return render(width, height, "png");
    }

    private static byte[] render(int width, int height, String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
