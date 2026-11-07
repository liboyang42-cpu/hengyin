package com.chengyinhub.business.service.safety;

import com.chengyinhub.business.domain.ContentSensitiveImage;
import com.chengyinhub.business.domain.ContentSensitiveWord;
import com.chengyinhub.business.mapper.ContentSensitiveMapper;
import com.chengyinhub.business.service.impl.SensitiveContentLibraryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Round5D:词库加载、刷新、加载失败保留旧词库;已知违规图片完全相同与近似副本。 */
class SensitiveContentLibraryImplTest {

    private ContentSensitiveMapper mapper;
    private SensitiveContentLibraryImpl library;
    private long now = 1_800_000_000_000L;

    @BeforeEach
    void setUp() {
        mapper = mock(ContentSensitiveMapper.class);
        library = new SensitiveContentLibraryImpl();
        ReflectionTestUtils.setField(library, "mapper", mapper);
        setClock();
        when(mapper.selectEnabledAllowPhrases()).thenReturn(Collections.<String>emptyList());
        when(mapper.selectEnabledImages()).thenReturn(Collections.<ContentSensitiveImage>emptyList());
    }

    private void setClock() {
        ReflectionTestUtils.setField(library, "clock", Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC));
    }

    private static ContentSensitiveWord word(String w) {
        ContentSensitiveWord x = new ContentSensitiveWord();
        x.setWord(w);
        x.setCategory("OTHER");
        x.setAction("BLOCK");
        return x;
    }

    @Test
    void loadsFromTableAndRefreshesWhenTheVersionChanges() {
        when(mapper.selectLibraryVersion()).thenReturn("v1");
        when(mapper.selectEnabledWords()).thenReturn(Collections.singletonList(word("测敏甲乙")));
        assertNotNull(library.matchText("测敏甲乙"));
        assertNull(library.matchText("测敏丁戊"));

        when(mapper.selectLibraryVersion()).thenReturn("v2");
        when(mapper.selectEnabledWords()).thenReturn(Collections.singletonList(word("测敏丁戊")));
        // 30 秒内不再查版本
        assertNull(library.matchText("测敏丁戊"));
        now += 31_000L;
        setClock();
        assertNotNull(library.matchText("测敏丁戊"));
        assertNull(library.matchText("测敏甲乙"));
    }

    @Test
    void failedReloadKeepsTheLastGoodCopy() {
        when(mapper.selectLibraryVersion()).thenReturn("v1");
        when(mapper.selectEnabledWords()).thenReturn(Collections.singletonList(word("测敏甲乙")));
        assertNotNull(library.matchText("测敏甲乙"));

        when(mapper.selectLibraryVersion()).thenReturn("v2");
        when(mapper.selectEnabledWords()).thenThrow(new IllegalStateException("db down"));
        now += 31_000L;
        setClock();
        assertNotNull(library.matchText("测敏甲乙"), "加载失败不能把检查悄悄关掉");
        assertEquals("v1", library.currentVersion());
    }

    @Test
    void firstLoadFailureDoesNotThrowAndIsRetriedOnNextUse() {
        when(mapper.selectLibraryVersion()).thenThrow(new IllegalStateException("db down"));
        assertNull(library.matchText("测敏甲乙"));
        reset(mapper);
        when(mapper.selectLibraryVersion()).thenReturn("v1");
        when(mapper.selectEnabledWords()).thenReturn(Collections.singletonList(word("测敏甲乙")));
        when(mapper.selectEnabledAllowPhrases()).thenReturn(Collections.<String>emptyList());
        when(mapper.selectEnabledImages()).thenReturn(Collections.<ContentSensitiveImage>emptyList());
        assertNotNull(library.matchText("测敏甲乙"), "没有快照时每次使用都重试加载");
    }

    @Test
    void knownImageMatchesExactCopyAndNearDuplicateButNotADifferentImage() throws Exception {
        byte[] original = png(pattern(64, 64, false));
        ImageFingerprint fp = ImageFingerprint.of(original);
        ContentSensitiveImage bad = new ContentSensitiveImage();
        bad.setSha256(fp.getSha256());
        bad.setDhash(fp.getDhash());
        bad.setCategory("PORN");
        when(mapper.selectLibraryVersion()).thenReturn("v1");
        when(mapper.selectEnabledWords()).thenReturn(Collections.<ContentSensitiveWord>emptyList());
        when(mapper.selectEnabledImages()).thenReturn(Collections.singletonList(bad));

        assertEquals("PORN", library.matchImage(original), "完全相同(SHA-256)");
        byte[] resizedJpeg = jpeg(scale(pattern(64, 64, false), 200, 200));
        assertNotEquals(fp.getSha256(), ImageFingerprint.sha256(resizedJpeg));
        assertEquals("PORN", library.matchImage(resizedJpeg), "放大 + 转 JPEG 的副本靠 dHash 认出");
        assertNull(library.matchImage(png(pattern(64, 64, true))), "另一张图不误伤");
    }

    // 左亮右暗的竖向渐变 + 色块;inverted=true 时左右翻转,dHash 差距大。
    static BufferedImage pattern(int w, int h, boolean inverted) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int v = (int) (255.0 * (inverted ? x : (w - 1 - x)) / (w - 1));
                int v2 = (y / 8) % 2 == 0 ? v : Math.max(0, v - 60);
                img.setRGB(x, y, new Color(v2, v2, v2).getRGB());
            }
        }
        return img;
    }

    static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    static byte[] png(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    static byte[] jpeg(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }
}
