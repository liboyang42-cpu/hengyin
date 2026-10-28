package com.chengyinhub.business.service.ai.impl;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.Locale;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.service.ai.CardCutout;
import com.chengyinhub.business.service.ai.CardCutoutStorage;
import com.chengyinhub.common.config.RuoYiConfig;
import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;

import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;

/**
 * 抠图结果转存:第三方临时地址 → 下载 → 校验 → 落我们自己的桶。
 *
 * <p>为什么必须转存(施工文档 §2.3):供应商回来的地址只活 30 分钟,直接存进 {@code cutout_url}
 * 等于过一夜整册子都是坏图,而且还把第三方域暴露给小程序。
 *
 * <p>下载是**我们服务器**主动发起的一次取回,地址来自第三方返回值 —— 所以这道地址闸与铸卡的
 * 来源闸同一条口径:先挡在发请求之前,认不出就不发。日志里只打主机名:结果地址本身是带签名的
 * 临时凭证,整个 URL 进日志等于把凭证抄进日志文件。
 *
 * <p>全程 fail-open:任何一步不成都返回 {@code null},调用方按「没有抠图」走照片卡。
 */
@Component
public class OssCardCutoutStorage implements CardCutoutStorage {

    private static final Logger log = LoggerFactory.getLogger(OssCardCutoutStorage.class);
    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.service.impl.AppMediaAssetService mediaAssets;

    /** 抠图对象的业务类型标记(与 photo 上传同一条 metadata 通道)。 */
    static final String BIZ_TYPE = "object_card_cutout";

    /** 12MB:商品分割按原图大小(≤ 2000 见方)回四通道 PNG,大物件一张就可能过 5MB;再大就是拿错了东西。 */
    static final int MAX_PNG_BYTES = 12 * 1024 * 1024;

    /** 转存给商品分割看的整形照片,与抠图结果分开标,将来清理时一眼分得开。 */
    static final String SEG_INPUT_BIZ_TYPE = "object_card_seg_input";

    /** 商品分割的输入限制(施工文档 §2.3):≤ 3MB,宽高都严格小于 2000;超了就缩到长边 1600。 */
    static final int SEG_INPUT_MAX_BYTES = 3 * 1024 * 1024;
    static final int SEG_INPUT_MAX_SIDE = 2000;
    static final int SEG_INPUT_TARGET_SIDE = 1600;

    /** 抠图结果与送进去的照片同大(≤ 2000 见方):文件头声明更大的一律不解,防一张小 PNG 解出几个 GB。 */
    static final int CUTOUT_MAX_SIDE = 2048;
    /** 透明度低于这个值算背景:抠图边缘的半透明羽化不该把裁边撑出去。 */
    private static final int ALPHA_MIN = 16;

    /** 读原图的上限:手机原图十来 MB,再大就不是一张正常照片了,不往内存里读。 */
    static final int PHOTO_MAX_BYTES = 20 * 1024 * 1024;

    /** 照片总像素上限(5000 万):隔行采样照样要扫完整张,cancel(true) 又停不下解码,超了一个像素都不解。 */
    static final long PHOTO_MAX_PIXELS = 50_000_000L;

    /** PNG 规范签名八字节(\x89 PNG \r\n \x1A \n):后四字节里的 0x1A 0x0A 也是签名,只看 \x89PNG 会放过别的东西。 */
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    /** 阿里云视觉智能把结果写进自己账号下的 OSS 桶,域名一律 *.aliyuncs.com;确切主机等真 key 实调再收口。 */
    private static final String RESULT_HOST_SUFFIX = ".aliyuncs.com";

    /** 单次下载的连接/读取超时。当场出卡的 4 秒总预算在铸卡外层卡死,这里只保证一次挂起的下载不会拖到底。 */
    @Value("${ai.cutout.timeoutMs:4000}")
    int downloadTimeoutMs;

    @Override
    public CardCutout transfer(String tempPngUrl, long ownerId) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        String url = StringUtils.trimToEmpty(tempPngUrl);
        try {
            if (!isAliyunResultAddress(url)) {
                log.warn("[藏品卡] 抠图结果地址不在允许域内,不发起下载: host={}", hostOf(url));
                return null;
            }
            byte[] png = download(url, MAX_PNG_BYTES);
            if (png == null || png.length == 0) {
                return null;
            }
            if (png.length > MAX_PNG_BYTES) {
                log.warn("[藏品卡] 抠图结果超过 {} 字节上限, 实际 {},丢弃", MAX_PNG_BYTES, png.length);
                return null;
            }
            if (!isPng(png)) {
                log.warn("[藏品卡] 抠图结果不是 PNG(文件头不符), head={} size={}", hexHeadOf(png), png.length);
                return null;
            }
            Trimmed trimmed = trimToObject(png);
            if (trimmed == null) {
                log.warn("[藏品卡] 抠图结果里没有物品或尺寸超限, size={}", png.length);
                return null;
            }
            String stored = storeUnlessAbandoned(trimmed.png, "object-card-cutout.png", "image/png", ownerId, BIZ_TYPE);
            if (stored == null) {
                log.warn("[藏品卡] 抠图结果没能转存,卡面退回照片档");
                return null;
            }
            return new CardCutout(stored, trimmed.box);
        } catch (RuntimeException e) {
            log.error("[藏品卡] 抠图转存异常, exceptionType={}", e.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    public String segmentInputOf(String photoUrl, long ownerId) {
        String url = StringUtils.trimToEmpty(photoUrl);
        try {
            boolean remote = url.startsWith("https://") || url.startsWith("http://");
            byte[] photo = remote ? download(url, PHOTO_MAX_BYTES) : readStationPhoto(url);
            if (photo == null || photo.length == 0 || photo.length > PHOTO_MAX_BYTES) {
                log.warn("[藏品卡] 照片取不回或超过 {} 字节,这次不抠图", PHOTO_MAX_BYTES);
                return null;
            }
            byte[] fitted = fitForSegment(photo);
            if (fitted == null) {
                log.warn("[藏品卡] 照片读不出或缩不进接口限制,这次不抠图, size={}", photo.length);
                return null;
            }
            // 合规的原图:阿里云自己去取,不多存一份。站内相对路径它取不到,哪怕合规也得转存。
            if (remote && fitted == photo) {
                return url;
            }
            return storeUnlessAbandoned(fitted, "object-card-seg-input.jpg", "image/jpeg", ownerId, SEG_INPUT_BIZ_TYPE);
        } catch (RuntimeException e) {
            log.error("[藏品卡] 照片整形异常, exceptionType={}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** 站内上传的照片在本机磁盘(ruoyi.profile 下,/profile/** 即其映射);带 .. 的一律不读。 */
    private static byte[] readStationPhoto(String url) {
        String prefix = Constants.RESOURCE_PREFIX + "/";
        String profile = RuoYiConfig.getProfile();
        if (StringUtils.isBlank(profile) || !url.startsWith(prefix) || url.contains("..")) {
            return null;
        }
        File file = new File(profile, url.substring(prefix.length()));
        if (!file.isFile() || file.length() > PHOTO_MAX_BYTES) {
            return null;
        }
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            log.warn("[藏品卡] 站内照片读取失败, exceptionType={}", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 按商品分割的输入限制整形:合规(JPEG/PNG/BMP、≤ 3MB、宽高都 &lt; 2000)原样返回同一个数组;
     * 否则长边缩到 1600 转成 JPEG。读不出、缩完还超 3MB → {@code null}。
     *
     * <p>先读图头拿宽高,解码时按整数倍隔行采样:一张 8000px 的原图不会先整张解进内存再缩。
     *
     * <p>ponytail: 不读 EXIF 方向 —— 缩过的图会丢掉旋转标记,贴纸可能横着。小程序拍照走 compressed,
     * 实际很少进这条缩图路;真机上看到横躺的贴纸再补 EXIF 解析。
     */
    static byte[] fitForSegment(byte[] photo) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(photo))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if ((long) width * height > PHOTO_MAX_PIXELS) {
                    return null;
                }
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                boolean formatOk = format.equals("jpeg") || format.equals("png") || format.equals("bmp");
                if (formatOk && photo.length <= SEG_INPUT_MAX_BYTES
                        && width < SEG_INPUT_MAX_SIDE && height < SEG_INPUT_MAX_SIDE) {
                    return photo;
                }
                int step = Math.max(1, Math.max(width, height) / SEG_INPUT_TARGET_SIDE);
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                BufferedImage source = reader.read(0, param);
                byte[] jpeg = toJpeg(source);
                return jpeg != null && jpeg.length <= SEG_INPUT_MAX_BYTES ? jpeg : null;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** 长边缩到 1600(本来更小就不放大),透明处垫白底 —— 直接画进 RGB 会变成黑底。 */
    private static byte[] toJpeg(BufferedImage source) throws IOException {
        double scale = Math.min(1.0, (double) SEG_INPUT_TARGET_SIDE / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        return ImageIO.write(target, "jpg", out) ? out.toByteArray() : null;
    }

    /**
     * 传输缝:取回响应字节,最多「上限 + 1」。生产走 hutool,单测覆写这一缝,不碰外网。
     *
     * <p>★ 必须 {@code executeAsync()}:hutool 同步 {@code execute()} 会先把整个响应读进内存再交出流,
     * 后面的截断只是在截一个已在内存里的数组(2026-09-23 实测:40MB 响应被整个读入,堆涨约 112MB)。
     * 异步模式交出的是连接上的原始流,读到上限就关连接,上限才真的挡在内存前面。
     * 重定向显式不跟随:302 可能跳到主机闸之外,不依赖 hutool 的全局默认值。
     */
    byte[] download(String url, int maxBytes) {
        try (HttpResponse resp = HttpRequest.get(url).timeout(downloadTimeoutMs)
                .setMaxRedirectCount(0).executeAsync()) {
            if (!resp.isOk()) {
                log.warn("[藏品卡] 抠图结果取回失败: host={} http={}", hostOf(url), resp.getStatus());
                return null;
            }
            // 声明的长度已经超限就一个字节都不读;分块传输没有长度,只能靠下面边读边截。
            if (resp.contentLength() > maxBytes) {
                log.warn("[藏品卡] 响应声明 {} 字节,超过 {} 上限,不下载", resp.contentLength(), maxBytes);
                return null;
            }
            return readAtMost(resp.bodyStream(), maxBytes + 1);
        } catch (Exception e) {
            if (ExceptionUtil.isCausedBy(e, SocketTimeoutException.class)) {
                log.warn("[藏品卡] 取回超时: host={}", hostOf(url));
            } else {
                log.error("[藏品卡] 取回异常: host={} exceptionType={}", hostOf(url), e.getClass().getSimpleName());
            }
            return null;
        }
    }

    /**
     * 商品分割按原图大小回透明 PNG:裁掉透明边,记下物品在原图里的位置(x,y,w,h 比例,四位小数)。
     * 宽高先从文件头读,超过 {@link #CUTOUT_MAX_SIDE} 不解码。没有透明通道、整张透明、解不开 → {@code null}。
     */
    static Trimmed trimToObject(byte[] png) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(png))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > CUTOUT_MAX_SIDE || height > CUTOUT_MAX_SIDE) {
                    return null;
                }
                BufferedImage image = reader.read(0);
                if (!image.getColorModel().hasAlpha()) {
                    return null;
                }
                int x0 = width, y0 = height, x1 = -1, y1 = -1;
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        if ((image.getRGB(x, y) >>> 24) > ALPHA_MIN) {
                            if (x < x0) x0 = x;
                            if (x > x1) x1 = x;
                            if (y < y0) y0 = y;
                            if (y > y1) y1 = y;
                        }
                    }
                }
                if (x1 < 0) {
                    return null;
                }
                int w = x1 - x0 + 1, h = y1 - y0 + 1;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                if (!ImageIO.write(image.getSubimage(x0, y0, w, h), "png", out)) {
                    return null;
                }
                String box = String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f",
                        x0 / (double) width, y0 / (double) height, w / (double) width, h / (double) height);
                return new Trimmed(out.toByteArray(), box);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static final class Trimmed {
        final byte[] png;
        final String box;

        Trimmed(byte[] png, String box) {
            this.png = png;
            this.box = box;
        }
    }

    /**
     * 铸卡那边到点不等了会 {@code cancel(true)}:socket 读不理中断,线程读完回到这里时中断标记还在。
     * 这时再上传,就是一个没有任何卡引用的孤儿对象。
     */
    private String storeUnlessAbandoned(byte[] bytes, String fileName, String contentType, long ownerId,
            String bizType) {
        if (Thread.currentThread().isInterrupted()) {
            log.warn("[藏品卡] {} 铸卡已经不等了,不再入桶", bizType);
            return null;
        }
        return storeObject(bytes, fileName, contentType, ownerId, bizType);
    }

    /**
     * 落桶缝:服务端产出的字节变成自家对象。归属 member 与业务类型写进对象元数据,
     * 将来要按人核对或清理时,这是唯一能查回的线索。
     */
    String storeObject(byte[] bytes, String fileName, String contentType, long ownerId, String bizType) {
        try {
            if(!SEG_INPUT_BIZ_TYPE.equals(bizType) && mediaAssets==null)return null;
            JSONObject uploaded = FileOssUploadUtils.uploadPendingWithOwner(
                    new ServerMultipartFile(bytes, fileName, contentType), String.valueOf(ownerId), bizType);
            if(uploaded==null)return null;
            String key=uploaded.getString("fileName");
            if(SEG_INPUT_BIZ_TYPE.equals(bizType))return FileOssUploadUtils.generateShortLivedPresignedUrl(key,300);
            java.util.Map<String,Object> status=mediaAssets.stage(ownerId,key);
            if(status==null || !(status.get("assetId") instanceof Number))return null;
            return "media-asset:"+status.get("assetId");
        } catch (IOException | RuntimeException e) {
            // 无法保存审核事实时不发布生成图；调用方可继续使用已审核原照片。
            log.error("[藏品卡] {} 入桶失败, exceptionType={}", bizType, e.getClass().getSimpleName());
            return null;
        }
    }

    /** Vendor audio becomes a private immutable object and a real HOLD receipt, never the vendor URL. */
    public java.util.Map<String,Object> stageGeneratedAudio(Long ownerId, String vendorUrl) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        if(ownerId==null || ownerId<=0 || mediaAssets==null || !isAliyunResultAddress(vendorUrl))
            throw new com.chengyinhub.common.exception.ServiceException("语音媒体来源暂不可用");
        try {
            URI uri=new URI(vendorUrl);
            if(uri.getPort()!=-1 && uri.getPort()!=443 || uri.getFragment()!=null)throw new IOException("invalid origin");
            java.net.InetAddress[] addresses=resolveMediaHost(uri.getHost());
            if(addresses.length==0)throw new IOException("missing origin");
            for(java.net.InetAddress address:addresses) {
                if(!publicMediaAddress(address))throw new IOException("invalid origin");
            }
            byte[] bytes=download(vendorUrl,10*1024*1024);
            if(bytes==null || bytes.length<3 || bytes.length>10*1024*1024
                    || !(bytes[0]=='I' && bytes[1]=='D' && bytes[2]=='3'
                        || (bytes[0]&255)==255 && (bytes[1]&224)==224))throw new IOException("invalid audio");
            JSONObject uploaded=FileOssUploadUtils.uploadPendingWithOwner(
                    new ServerMultipartFile(bytes,"npc-reply.mp3","audio/mpeg"),String.valueOf(ownerId),"npc_reply_audio");
            if(uploaded==null)throw new IOException("missing receipt");
            return mediaAssets.stage(ownerId,uploaded.getString("fileName"));
        } catch(Exception e) {
            throw new com.chengyinhub.common.exception.ServiceException("语音媒体暂不可用，文字回答仍可查看");
        }
    }
    java.net.InetAddress[] resolveMediaHost(String host) throws java.net.UnknownHostException {return java.net.InetAddress.getAllByName(host);}
    static boolean publicMediaAddress(java.net.InetAddress address) {
        if(address==null || address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress())return false;
        byte[] bytes=address.getAddress();
        if(bytes.length==16 && (bytes[0]&254)==252)return false;
        return !(bytes.length==4 && ((bytes[0]&255)==0 || ((bytes[0]&255)==100 && (bytes[1]&192)==64)));
    }

    static boolean isPng(byte[] bytes) {
        if (bytes == null || bytes.length < PNG_SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (bytes[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    /** 只放行 https + 阿里云结果子域。SSRF 的入口从来不是我们自己产出的地址,而是返回值里被人换掉的那一句。 */
    static boolean isAliyunResultAddress(String url) {
        if (StringUtils.isBlank(url)) {
            return false;
        }
        try {
            URI uri = new URI(url.trim());
            String host = uri.getHost();
            String lower = host == null ? null : host.toLowerCase();
            // userinfo 必须挡:https://合法域@127.0.0.1/ 的真实主机是后面那个。
            // 空子域(.aliyuncs.com)在 URI 解析这一步就已经拿不到 host,不必再判一次。
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null
                    && lower != null && lower.length() > RESULT_HOST_SUFFIX.length()
                    && lower.endsWith(RESULT_HOST_SUFFIX);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static String hostOf(String url) {
        try {
            String host = url == null ? null : new URI(url.trim()).getHost();
            return StringUtils.isBlank(host) ? "-" : host;
        } catch (URISyntaxException e) {
            return "-";
        }
    }

    private static String hexHeadOf(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < bytes.length && i < 8; i++) {
            hex.append(String.format("%02X", bytes[i]));
        }
        return hex.toString();
    }

    private static byte[] readAtMost(InputStream in, int max) throws IOException {
        if (in == null) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (total < max) {
            if(System.nanoTime()>deadline)throw new IOException("download deadline exceeded");
            int read = in.read(buffer, 0, Math.min(buffer.length, max - total));
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
            total += read;
        }
        return out.toByteArray();
    }

    /** 服务端产出的字节包成 MultipartFile;文件名是代码里的常量,不让任何外部字符串进入对象键。 */
    private static final class ServerMultipartFile implements MultipartFile {
        private final byte[] bytes;
        private final String fileName;
        private final String contentType;

        ServerMultipartFile(byte[] bytes, String fileName, String contentType) {
            this.bytes = bytes;
            this.fileName = fileName;
            this.contentType = contentType;
        }

        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return fileName; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File dest) throws IOException {
            java.nio.file.Files.write(dest.toPath(), bytes);
        }
    }
}
