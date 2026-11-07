package com.chengyinhub.business.service.safety;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;

/**
 * 已知违规图片指纹(Round5D):SHA-256 认完全相同的文件,dHash(差值感知哈希,64 位)认缩放/压缩/轻微改色后的副本。
 * 只能认出「已登记图片的副本」,新图仍靠上传时微信 img_sec_check 与异步检测。
 */
public final class ImageFingerprint {

    /** dHash 汉明距离不超过此值视为同一张图的副本。 */
    public static final int NEAR_DUPLICATE_DISTANCE = 6;

    private final String sha256;
    private final Long dhash;

    private ImageFingerprint(String sha256, Long dhash) {
        this.sha256 = sha256;
        this.dhash = dhash;
    }

    public String getSha256() { return sha256; }
    /** ImageIO 读不了的格式(如 webp)为 null,只能按 SHA-256 认。 */
    public Long getDhash() { return dhash; }

    public static ImageFingerprint of(byte[] bytes) {
        if (bytes == null) return null;
        return new ImageFingerprint(sha256(bytes), dhash(bytes));
    }

    public static String sha256(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static Long dhash(byte[] bytes) {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(bytes));
            if (src == null) return null;
            BufferedImage small = new BufferedImage(9, 8, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D g = small.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, 9, 8, null);
            g.dispose();
            long hash = 0L;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int left = small.getRaster().getSample(x, y, 0);
                    int right = small.getRaster().getSample(x + 1, y, 0);
                    hash = (hash << 1) | (left > right ? 1L : 0L);
                }
            }
            return hash;
        } catch (Exception unreadable) {
            return null;
        }
    }

    public static int distance(long a, long b) {
        return Long.bitCount(a ^ b);
    }
}
