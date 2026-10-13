package com.chengyinhub.business.service.ai.impl;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.chengyinhub.common.config.RuoYiConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 送商品分割之前的照片整形(施工文档 §2.3):接口只吃 JPEG/PNG/BMP、≤ 3MB、宽高都 < 2000、
 * 而且得是阿里云取得到的绝对地址。
 *
 * <p>超限的照片直接送过去,接口报错 → 这张卡永远没有贴纸,且只在日志里留一行 warn,没人会发现。
 * 所以:合规的原图原样交地址;超限的缩到长边 1600 转存一张再交新地址;站内相对路径阿里云取不到,
 * 同样转存。读不出、缩不了一律当没有结果,卡照样铸。
 */
class CardCutoutInputTest {

    private static final long OWNER_ID = 7L;
    private static final String OSS_PHOTO = "https://cy.oss-cn-shanghai.aliyuncs.com/upload/a.jpg?Expires=1&Signature=s";
    private static final String STORED = "https://cy.oss-cn-shanghai.aliyuncs.com/upload/seg-input.jpg?Signature=t";

    @TempDir
    Path profileDir;
    private String profileBefore;

    @BeforeEach
    void rememberProfile() {
        profileBefore = RuoYiConfig.getProfile();
    }

    /** RuoYiConfig.profile 是全局静态:改了不还原,同一个 JVM 里后面跑的测试都会读到临时目录。 */
    @AfterEach
    void restoreProfile() {
        new RuoYiConfig().setProfile(profileBefore);
    }

    // ==================== 尺寸与大小的判定 ====================

    @Test
    void 合规的照片原样交出_不重新编码() throws IOException {
        byte[] photo = jpeg(1200, 900);

        assertSame(photo, OssCardCutoutStorage.fitForSegment(photo));
    }

    /** 接口要求严格小于 2000:边长正好 2000 也得缩。负控靶子:把「<」写成「<=」这条就红。 */
    @Test
    void 边长正好2000也要缩到长边1600() throws IOException {
        byte[] fitted = OssCardCutoutStorage.fitForSegment(jpeg(2000, 1000));

        assertSize(fitted, 1600, 800);
    }

    @Test
    void 竖拍大图按长边缩_宽高比不变() throws IOException {
        byte[] fitted = OssCardCutoutStorage.fitForSegment(jpeg(3000, 4000));

        assertSize(fitted, 1200, 1600);
    }

    /** 尺寸合规但超过 3MB(噪点多的 PNG 很容易):同样缩,缩完必须落回 3MB 以内。 */
    @Test
    void 尺寸合规但超过3MB也要缩() throws IOException {
        byte[] photo = noisyPng(1900, 1900);
        assertTrue(photo.length > OssCardCutoutStorage.SEG_INPUT_MAX_BYTES, "样本本身必须超 3MB:" + photo.length);

        byte[] fitted = OssCardCutoutStorage.fitForSegment(photo);

        assertSize(fitted, 1600, 1600);
        assertTrue(fitted.length <= OssCardCutoutStorage.SEG_INPUT_MAX_BYTES, "缩完还超:" + fitted.length);
    }

    /** 接口不收 GIF:尺寸再小也要转成 JPEG。 */
    @Test
    void 接口不收的格式转成jpeg() throws IOException {
        byte[] gif = encode(new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB), "gif");

        byte[] fitted = OssCardCutoutStorage.fitForSegment(gif);

        assertSize(fitted, 300, 200);
        assertEquals((byte) 0xFF, fitted[0], "应当是 JPEG");
        assertEquals((byte) 0xD8, fitted[1], "应当是 JPEG");
    }

    /**
     * 总像素上限:隔行采样照样要把整张扫一遍,几千万像素的图能把铸卡线程拖住很久,
     * 而 cancel(true) 停不下解码,会连带拖住别人的铸卡。先看文件头,超过上限一个像素都不解。
     * 样本是一张真能解开的 8000×6400(5120 万像素)1 位黑白 PNG:没有上限时它会被正常缩成 JPEG。
     */
    @Test
    void 负控_总像素超上限的照片不解码_没有结果() throws IOException {
        byte[] huge = encode(new BufferedImage(8000, 6400, BufferedImage.TYPE_BYTE_BINARY), "png");

        assertNull(OssCardCutoutStorage.fitForSegment(huge), "五千万像素以上的照片被整张解码了");
    }

    @Test
    void 读不出的字节没有结果() {
        assertNull(OssCardCutoutStorage.fitForSegment("not an image".getBytes()));
    }

    // ==================== 交给接口的地址 ====================

    @Test
    void 自家桶里的合规照片_直接交原地址_不转存() throws IOException {
        FakeStorage storage = new FakeStorage();
        storage.remote = jpeg(1200, 900);

        assertEquals(OSS_PHOTO, storage.segmentInputOf(OSS_PHOTO, OWNER_ID));
        assertTrue(storage.stored.isEmpty(), "合规就别多存一份");
    }

    @Test
    void 自家桶里的超限照片_缩完转存_交新地址() throws IOException {
        FakeStorage storage = new FakeStorage();
        storage.remote = jpeg(4000, 3000);

        assertEquals(STORED, storage.segmentInputOf(OSS_PHOTO, OWNER_ID));
        assertEquals(1, storage.stored.size());
        assertSize(storage.stored.get(0), 1600, 1200);
    }

    /** 站内相对路径阿里云取不到:哪怕尺寸合规,也要从本机磁盘读出来转存成桶里的地址。 */
    @Test
    void 站内相对路径_从本机读出转存_交桶里的地址() throws IOException {
        new RuoYiConfig().setProfile(profileDir.toString());
        Files.createDirectories(profileDir.resolve("upload/2026/09/23"));
        Files.write(profileDir.resolve("upload/2026/09/23/p.jpg"), jpeg(800, 600));
        FakeStorage storage = new FakeStorage();

        assertEquals(STORED, storage.segmentInputOf("/profile/upload/2026/09/23/p.jpg", OWNER_ID));
        assertTrue(storage.requestedUrls.isEmpty(), "站内的图读本机磁盘,不绕 HTTP 下载自己");
        assertSize(storage.stored.get(0), 800, 600);
    }

    @Test
    void 负控_站内路径带点点_不读不存() {
        new RuoYiConfig().setProfile(profileDir.toString());
        FakeStorage storage = new FakeStorage();

        assertNull(storage.segmentInputOf("/profile/upload/../../etc/passwd", OWNER_ID));
        assertTrue(storage.stored.isEmpty());
    }

    @Test
    void 照片取不回或读不出_没有结果_不转存() {
        FakeStorage storage = new FakeStorage();
        storage.remote = null;
        assertNull(storage.segmentInputOf(OSS_PHOTO, OWNER_ID));

        storage.remote = "<html>502</html>".getBytes();
        assertNull(storage.segmentInputOf(OSS_PHOTO, OWNER_ID));
        assertTrue(storage.stored.isEmpty());
    }

    /** 整形转存同样是一次上传:铸卡已经不等了,就别再往桶里放一张没人用的图。 */
    @Test
    void 已被放弃时缩完的照片不再转存() throws IOException {
        FakeStorage storage = new FakeStorage();
        storage.remote = jpeg(4000, 3000);
        Thread.currentThread().interrupt();
        try {
            assertNull(storage.segmentInputOf(OSS_PHOTO, OWNER_ID));
        } finally {
            Thread.interrupted();
        }
        assertTrue(storage.stored.isEmpty(), "被放弃的任务进了桶:" + storage.stored.size());
    }

    /** 照片下载的上限是原图上限(手机原图十来 MB),不是 5MB 的抠图结果上限。 */
    @Test
    void 下载照片用的是原图上限() {
        FakeStorage storage = new FakeStorage();
        storage.remote = null;

        storage.segmentInputOf(OSS_PHOTO, OWNER_ID);

        assertEquals(OssCardCutoutStorage.PHOTO_MAX_BYTES, (int) storage.requestedLimits.get(0));
    }

    // ==================== 脚手架 ====================

    private static void assertSize(byte[] image, int width, int height) throws IOException {
        assertNotNull(image, "应当缩出一张图");
        BufferedImage read = ImageIO.read(new ByteArrayInputStream(image));
        assertNotNull(read, "缩出来的字节读不回图");
        assertEquals(width + "x" + height, read.getWidth() + "x" + read.getHeight());
    }

    private static byte[] jpeg(int width, int height) throws IOException {
        return encode(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpg");
    }

    private static byte[] noisyPng(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        return encode(image, "png");
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, out), "本机 ImageIO 写不了 " + format);
        return out.toByteArray();
    }

    /** 下载与落桶两条缝换掉:不打外网、不碰 OSS。 */
    private static final class FakeStorage extends OssCardCutoutStorage {
        byte[] remote;
        final List<String> requestedUrls = new ArrayList<>();
        final List<Integer> requestedLimits = new ArrayList<>();
        final List<byte[]> stored = new ArrayList<>();

        @Override
        byte[] download(String url, int maxBytes) {
            requestedUrls.add(url);
            requestedLimits.add(maxBytes);
            return remote;
        }

        @Override
        String storeObject(byte[] bytes, String fileName, String contentType, long ownerId, String bizType) {
            stored.add(bytes);
            return STORED;
        }
    }
}
