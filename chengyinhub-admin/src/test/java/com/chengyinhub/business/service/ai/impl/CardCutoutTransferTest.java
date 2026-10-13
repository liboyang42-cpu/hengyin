package com.chengyinhub.business.service.ai.impl;

import java.io.IOException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.service.ai.CardCutout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抠图结果转存(转台藏品卡施工文档 §2.3 §4.1):阿里云回来的临时地址 → 下载 → 校验 → 进我们的桶。
 *
 * <p>这里钉的三件事出事时都看不出来:
 * ① 供应商的结果地址只活 30 分钟,存进 {@code cutout_url} 的必须是自家地址 —— 存了外链,
 * 第二天整册子都是坏图,而且等于让小程序去请求第三方域;
 * ② 下载是**我们服务器**发起的一次取回,地址来自第三方返回值,所以「别人递进来的地址不能下」
 * 这道闸必须落在发请求之前(与铸卡来源闸同一口径);
 * ③ 校验看字节本身:只认 PNG 八字节签名、只收 5MB 以内。不是透明 PNG 就别往卡面上贴。
 *
 * <p>下载与落桶两条缝在子类里换成替身:单测不打外网、不碰 OSS,但 transfer 的流程照走。
 */
class CardCutoutTransferTest {

    private static final long OWNER_ID = 7L;
    private static final int MAX_BYTES = OssCardCutoutStorage.MAX_PNG_BYTES;
    private static final byte[] PNG_HEAD = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    /** 真实返回值就长这样(带签名查询串),形状一致即可。 */
    private static final String RESULT_URL = "https://imageseg-result-cn-shanghai.oss-cn-shanghai.aliyuncs.com"
            + "/proxy/2026-09-23/seg.png?Expires=1790000000&Signature=abc";
    private static final String OUR_URL = "https://cdn.chengyinhub.com/upload/object_card_cutout/seg.png";

    // ==================== 正常链路 ====================

    @Test
    void 下载校验后转存我们的桶_返回自家地址() {
        FakeStorage storage = new FakeStorage();

        CardCutout got = storage.transfer(RESULT_URL, OWNER_ID);

        assertEquals(OUR_URL, got.getUrl());
        assertEquals(Arrays.asList(RESULT_URL), storage.requestedUrls, "下的必须是被校验过的那个地址");
        assertEquals(Arrays.asList(OWNER_ID), storage.storedOwners, "卡主要跟着进对象元数据,否则将来按人清理无从查起");
    }

    // ==================== 裁边与位置 ====================

    /**
     * 商品分割按原图大小回一张透明 PNG(不用 crop 形式):这样才知道物品在原照片的哪一块。
     * 存进桶的是裁掉透明边的那一块,位置按原图的比例记下(x,y,w,h)——
     * 当场那一屏靠它让背景散掉时物品留在原位;不裁,藏品册里每件东西都缩在一大片透明里。
     */
    @Test
    void 裁掉透明边再落桶_并记下物品在原图里的位置() throws IOException {
        FakeStorage storage = new FakeStorage();

        CardCutout got = storage.transfer(RESULT_URL, OWNER_ID);

        assertEquals("0.1000,0.2500,0.5000,0.5000", got.getBox());
        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(storage.stored.get(0)));
        assertEquals("50x40", stored.getWidth() + "x" + stored.getHeight(), "落桶的应当是裁掉透明边的那一块");
        assertEquals(255, stored.getRGB(0, 0) >>> 24, "裁完左上角就该是物品本身");
    }

    /**
     * 商品分割改成按原图大小回透明 PNG 之后,接近 2000px 的大物件一张就可能过 5MB(老上限是按裁好的小图定的)。
     * 过了上限就整张丢掉 = 这张卡没有贴纸,所以上限要按原图大小的结果放宽。
     */
    @Test
    void 原图大小的抠图结果过了5MB也照收() {
        FakeStorage storage = new FakeStorage();
        storage.next = java.util.Arrays.copyOf(objectOnTransparent(), 8 * 1024 * 1024);

        CardCutout got = storage.transfer(RESULT_URL, OWNER_ID);

        assertEquals(OUR_URL, got == null ? null : got.getUrl(), "8MB 的原图大小抠图被当成超限丢掉了");
    }

    @Test
    void 全透明的结果是没抠出东西_不落桶() throws IOException {
        FakeStorage storage = new FakeStorage();
        storage.next = png(new BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB));

        assertNull(storage.transfer(RESULT_URL, OWNER_ID));
        assertTrue(storage.stored.isEmpty());
    }

    /**
     * 字节小不代表解出来小:一张 5MB 以内的 PNG 可以声明几万像素见方,整张解进内存就是几个 GB。
     * 宽高先从文件头读,超过输入上限(长边 2048)一个像素都不解。
     */
    @Test
    void 负控_声明尺寸超上限的结果不解码不落桶() throws IOException {
        FakeStorage storage = new FakeStorage();
        BufferedImage huge = new BufferedImage(3000, 2, BufferedImage.TYPE_INT_ARGB);
        huge.setRGB(5, 1, 0xFF336699);   // 有一个不透明像素:没有尺寸闸就会被当成一件物品裁出来
        storage.next = png(huge);

        assertNull(storage.transfer(RESULT_URL, OWNER_ID));
        assertTrue(storage.stored.isEmpty());
    }

    // ==================== 字节校验 ====================

    /** 负控靶子:删掉文件头校验,这条就会绿着把 JPEG 当透明 PNG 存进卡面。 */
    @Test
    void 负控_jpeg文件头不当作抠图结果落桶() {
        FakeStorage storage = new FakeStorage();
        storage.next = withHead(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 'J', 'F', 'I', 'F'});

        assertNull(storage.transfer(RESULT_URL, OWNER_ID));
        assertTrue(storage.stored.isEmpty(), "不是 PNG 就不能进桶:" + storage.stored.size());
    }

    /** 只认前四字节 \x89PNG 不够:0x1A 0x0A 也是签名的一部分,缺了就是「一眼像 PNG」的别的东西。 */
    @Test
    void 负控_png签名不完整就拒() {
        FakeStorage storage = new FakeStorage();
        storage.next = withHead(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', '\r', '\n'});

        assertNull(storage.transfer(RESULT_URL, OWNER_ID));
        assertTrue(storage.stored.isEmpty());
    }

    @Test
    void 超过上限的结果不落桶() {
        FakeStorage storage = new FakeStorage();
        storage.next = pngAt(MAX_BYTES + 1);

        assertNull(storage.transfer(RESULT_URL, OWNER_ID));
        assertTrue(storage.stored.isEmpty());
    }

    /** 上限是「≤ 5MB」:整 5MB 要收,边界写成严格小于就会挡掉可用结果。 */
    @Test
    void 刚好五兆仍然收() {
        FakeStorage storage = new FakeStorage();
        // 真 PNG 后面补零补到正好五兆:IEND 之后的字节解码器不读,大小闸看的是整包
        storage.next = java.util.Arrays.copyOf(objectOnTransparent(), MAX_BYTES);

        assertEquals(OUR_URL, storage.transfer(RESULT_URL, OWNER_ID).getUrl());
    }

    @Test
    void 空返回体没有结果() {
        FakeStorage storage = new FakeStorage();
        storage.next = new byte[0];

        assertNull(storage.transfer(RESULT_URL, OWNER_ID));
        assertTrue(storage.stored.isEmpty());
    }

    // ==================== 地址闸:挡在发请求之前 ====================

    @Test
    void 负控_明文http与外域主机一次请求都不发() {
        for (String url : Arrays.asList(
                "http://imageseg-result-cn-shanghai.oss-cn-shanghai.aliyuncs.com/x.png",
                "https://attacker.example.com/x.png",
                "https://imageseg.cn-shanghai.aliyuncs.com.evil.com/x.png",
                "ftp://bucket.oss-cn-shanghai.aliyuncs.com/x.png")) {
            assertBlocked(url);
        }
    }

    /** 经典伪装:user-info 里塞合法域名,真实主机是内网地址;结果桶一律带子域,裸主域不算。 */
    @Test
    void 负控_域名前缀伪装与userinfo内网地址都拒() {
        for (String url : Arrays.asList(
                "https://x.oss-cn-shanghai.aliyuncs.com@127.0.0.1/y.png",
                "https://aliyuncs.com/y.png",
                "https://.aliyuncs.com/y.png")) {
            assertBlocked(url);
        }
    }

    @Test
    void 负控_空地址与畸形地址没有结果() {
        FakeStorage blank = new FakeStorage();

        assertNull(blank.transfer(null, OWNER_ID));
        assertNull(blank.transfer("   ", OWNER_ID));
        assertNull(blank.transfer("not a url", OWNER_ID));
        assertTrue(blank.requestedUrls.isEmpty(), "认不出的地址不该变成一次请求");
    }

    // ==================== 失败一律「没有结果」,不抛出 ====================

    @Test
    void 下载取不到字节就不落桶() {
        FakeStorage storage = new FakeStorage();
        storage.next = null;

        assertNull(storage.transfer(RESULT_URL, OWNER_ID));
        assertTrue(storage.stored.isEmpty());
    }

    /** 超时、DNS 挂、连接被拒……在铸卡这条链路上都只能等于「没有抠图」,不能把卡一起带走。 */
    @Test
    void 下载炸了不往外扔() {
        FakeStorage storage = new FakeStorage();
        storage.downloadFailure = new IllegalStateException("连接超时");

        assertNull(storage.transfer(RESULT_URL, OWNER_ID));
    }

    /**
     * 铸卡那边到点不等了会 {@code cancel(true)}:socket 读不理中断,线程读完回到这里时中断标记还在。
     * 这时再进桶,就是一个没有任何卡引用的孤儿对象 —— 攒多了只能靠人去桶里翻。
     */
    @Test
    void 已被放弃的抠图不再落桶() {
        FakeStorage storage = new FakeStorage();
        Thread.currentThread().interrupt();
        try {
            assertNull(storage.transfer(RESULT_URL, OWNER_ID));
        } finally {
            Thread.interrupted();
        }
        assertTrue(storage.stored.isEmpty(), "被放弃的任务进了桶:" + storage.stored.size());
    }

    /** 真实实现把 IOException 咽在落桶这一步里(换回来的是 null),能逃到这里的只有运行期异常。 */
    @Test
    void 落桶炸了不往外扔() {
        FakeStorage storage = new FakeStorage();
        storage.storeFailure = new IllegalStateException("桶配置读不到");

        assertNull(storage.transfer(RESULT_URL, OWNER_ID));
    }

    // ==================== 归属 ====================

    /**
     * 进桶时带上卡主与业务类型(落到对象元数据 x-oss-meta-member-id / x-oss-meta-biz-type)。
     * 漏了不报错,只会让「按人核对与清理」将来无从查起。
     */
    @Test
    void 转存带上卡主与抠图业务类型() {
        FakeStorage storage = new FakeStorage();

        storage.transfer(RESULT_URL, OWNER_ID);

        assertEquals(Arrays.asList(OssCardCutoutStorage.BIZ_TYPE), storage.storedBizTypes);
        assertEquals(Arrays.asList("image/png"), storage.storedContentTypes);
    }

    // ==================== 脚手架 ====================

    private void assertBlocked(String url) {
        FakeStorage storage = new FakeStorage();

        assertNull(storage.transfer(url, OWNER_ID), "该挡住的地址:" + url);
        assertTrue(storage.requestedUrls.isEmpty(), "闸门必须落在发请求之前:" + url);
    }

    /** 下载与落桶两条缝换掉:不打外网、不碰 OSS,transfer 本体照走。 */
    private static final class FakeStorage extends OssCardCutoutStorage {
        byte[] next = objectOnTransparent();
        RuntimeException downloadFailure;
        RuntimeException storeFailure;
        final List<String> requestedUrls = new ArrayList<>();
        final List<byte[]> stored = new ArrayList<>();
        final List<Long> storedOwners = new ArrayList<>();
        final List<String> storedBizTypes = new ArrayList<>();
        final List<String> storedContentTypes = new ArrayList<>();

        @Override
        byte[] download(String url, int maxBytes) {
            requestedUrls.add(url);
            if (downloadFailure != null) {
                throw downloadFailure;
            }
            return next;
        }

        @Override
        String storeObject(byte[] bytes, String fileName, String contentType, long ownerId, String bizType) {
            if (storeFailure != null) {
                throw storeFailure;
            }
            stored.add(bytes);
            storedOwners.add(ownerId);
            storedBizTypes.add(bizType);
            storedContentTypes.add(contentType);
            return OUR_URL;
        }
    }

    /** 100×80 的透明底,(10,20) 起一块 50×40 的不透明物品 —— 和商品分割按原图大小回来的形状一样。 */
    private static byte[] objectOnTransparent() {
        BufferedImage image = new BufferedImage(100, 80, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(40, 120, 200, 255));
        g.fillRect(10, 20, 50, 40);
        g.dispose();
        try {
            return png(image);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static byte[] pngAt(int length) {
        byte[] body = new byte[length];
        System.arraycopy(PNG_HEAD, 0, body, 0, PNG_HEAD.length);
        return body;
    }

    /** 给定文件头 + 一段填充:校验只看头,后面的字节不参与判定。 */
    private static byte[] withHead(byte[] head) {
        byte[] body = new byte[64];
        System.arraycopy(head, 0, body, 0, head.length);
        return body;
    }
}
