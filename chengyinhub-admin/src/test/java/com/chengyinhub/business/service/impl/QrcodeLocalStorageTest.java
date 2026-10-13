package com.chengyinhub.business.service.impl;

import com.chengyinhub.common.config.RuoYiConfig;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class QrcodeLocalStorageTest {
    @TempDir Path directory;

    @Test
    void localModeCreatesDecodablePngAndRejectsInvalidConfiguration() throws Exception {
        String previousProfile = RuoYiConfig.getProfile();
        RuoYiConfig config = new RuoYiConfig();
        try {
            config.setProfile(directory.toString());
            QrcodeServiceImpl service = new QrcodeServiceImpl();
            assertEquals("oss", ReflectionTestUtils.getField(service, "storage"));
            ReflectionTestUtils.setField(service, "storage", "local");
            assertThrows(IllegalArgumentException.class, () -> service.generateQrcode("coupon", 300, 300));
            ReflectionTestUtils.setField(service, "publicBaseUrl", "https://example.com/prod-api/");
            String content = "coupon-local-storage-roundtrip-20260928";
            String url = service.generateQrcode(content, 300, 300);
            String prefix = "https://example.com/prod-api/profile/";
            assertTrue(url.startsWith(prefix + "qrcode/"));
            BufferedImage png = ImageIO.read(directory.resolve(url.substring(prefix.length())).toFile());
            assertEquals(300, png.getWidth());
            RGBLuminanceSource source = new RGBLuminanceSource(300, 300,
                    png.getRGB(0, 0, 300, 300, null, 0, 300));
            assertEquals(content, new MultiFormatReader().decode(
                    new BinaryBitmap(new HybridBinarizer(source))).getText());
            ReflectionTestUtils.setField(service, "storage", "typo");
            assertThrows(IllegalArgumentException.class, () -> service.generateQrcode(content, 300, 300));
        } finally {
            config.setProfile(previousProfile);
        }
    }
}
