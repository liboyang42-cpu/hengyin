package com.chengyinhub.business.service.ai;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 视觉图片预检契约:超限(格式 / 32MB / 单边 8192px)在发请求前判拒;
 * 探不到的事实一律放行 —— 预检绝不能制造假阴性把合法照片判死。
 */
class VisionImageGuardTest {

    private final VisionImageGuard guard = new VisionImageGuard();

    @Test
    void 合法小图放行() throws Exception {
        assertNull(guard.rejectReason(dataUrl(png(1, 1))));
        assertNull(guard.rejectReason(dataUrl(png(8192, 1))), "正好 8192 是合法的");
        assertNull(guard.rejectReason("https://cdn.example.com/photo.jpg"));
        assertNull(guard.rejectReason("https://cdn.example.com/photo?sign=abc"), "无扩展名不猜格式");
    }

    @Test
    void 负控_不支持的格式判拒() {
        assertNotNull(guard.rejectReason("data:image/bmp;base64,Qk0AAAAA"));
        assertNotNull(guard.rejectReason("data:image/svg+xml;base64,PHN2Zz48L3N2Zz4="));
        assertNotNull(guard.rejectReason("https://cdn.example.com/photo.bmp"));
        assertNotNull(guard.rejectReason("https://cdn.example.com/photo.tiff"));
    }

    @Test
    void 负控_单边超过8192像素判拒() throws Exception {
        assertNotNull(guard.rejectReason(dataUrl(png(8193, 1))), "8193 超过单边上限必须拒");
        assertNotNull(guard.rejectReason(dataUrl(png(1, 9000))));
    }

    @Test
    void 负控_远程图超过32MB判拒() {
        VisionImageGuard guarded = new VisionImageGuard(url ->
                new VisionImageProbe.ImageHead(33L * 1024 * 1024, "image/jpeg", new byte[0]));
        assertNotNull(guarded.rejectReason("https://cdn.example.com/photo"));
    }

    @Test
    void 负控_远程图单边超8192判拒() throws Exception {
        byte[] image = png(9000, 2);
        VisionImageGuard guarded = new VisionImageGuard(url ->
                new VisionImageProbe.ImageHead(image.length, "image/png", image));
        assertNotNull(guarded.rejectReason("https://cdn.example.com/photo"));
    }

    @Test
    void 负控_远程类型不是图片判拒() {
        VisionImageGuard guarded = new VisionImageGuard(url ->
                new VisionImageProbe.ImageHead(1024, "text/html", new byte[0]));
        assertNotNull(guarded.rejectReason("https://cdn.example.com/photo"));
    }

    @Test
    void 探针失败按未知放行不制造假阴性() {
        VisionImageGuard guarded = new VisionImageGuard(url -> null);
        assertNull(guarded.rejectReason("https://cdn.example.com/photo"));
    }

    @Test
    void 负控_非base64的dataURL判拒() {
        assertNotNull(guard.rejectReason("data:image/png;utf8,not-base64"));
    }

    private static String dataUrl(byte[] bytes) {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] png(int width, int height) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
