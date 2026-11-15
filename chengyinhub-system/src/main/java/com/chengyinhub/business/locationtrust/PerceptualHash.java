package com.chengyinhub.business.locationtrust;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * 64-bit difference hash (dHash) of a photo: shrink to 9×8 grey, one bit per "left pixel brighter than right".
 * Re-encoded, resized or lightly edited copies of the same photo land within a few bits of each other.
 *
 * <p>Decoding is bounded: images over {@link #MAX_PIXELS} are refused before the full decode, and large photos
 * are read with source subsampling so a 12 MP JPEG decodes at roughly 512 px.
 */
public final class PerceptualHash {
    static final long MAX_PIXELS = 80_000_000L;
    private static final int TARGET_EDGE = 512;

    private PerceptualHash() {
    }

    /** @return the dHash, or null when the bytes are not a decodable image (e.g. HEIC without a reader) */
    public static Long dHash(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || (long) w * h > MAX_PIXELS) return null;
                ImageReadParam param = reader.getDefaultReadParam();
                int step = Math.max(1, Math.min(w, h) / TARGET_EDGE);
                if (step > 1) param.setSourceSubsampling(step, step, 0, 0);
                return dHash(reader.read(0, param));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static Long dHash(BufferedImage image) {
        if (image == null) return null;
        BufferedImage small = new BufferedImage(9, 8, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = small.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, 0, 0, 9, 8, null);
        } finally {
            g.dispose();
        }
        long hash = 0L;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int left = small.getRaster().getSample(x, y, 0);
                int right = small.getRaster().getSample(x + 1, y, 0);
                hash = (hash << 1) | (left > right ? 1L : 0L);
            }
        }
        return hash;
    }

    public static int hamming(long a, long b) {
        return Long.bitCount(a ^ b);
    }
}
