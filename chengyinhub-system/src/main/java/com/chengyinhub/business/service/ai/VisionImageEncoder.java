package com.chengyinhub.business.service.ai;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Base64;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chengyinhub.common.config.RuoYiConfig;
import com.chengyinhub.common.constant.Constants;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;

/**
 * 把玩家提交的图片转成 data URL(base64)再喂给视觉模型。
 *
 * <p><b>为什么不再直接发 URL(2026-09-18 真 key 生产实测,不是推测):</b>
 * <ul>
 *   <li>纯 base64 data URL:一次就过,模型正确判定并回干净 JSON;</li>
 *   <li>站内公网 URL(https://www.chengyinhub.com/prod-api/profile/...,两个不同合法图都试过):
 *       400 {"message":".messages[1].image[0]: You have uploaded an unsupported image..."};</li>
 *   <li>外部 URL(维基百科图):400 {"message":"...Failed to download image from ..."}。</li>
 * </ul>
 * 同一个站内 URL 从外网机器 curl 是 HTTP 200 / content-type image/jpeg / 魔数 ffd8 的真 JPEG
 * —— 模型侧取 URL 不可靠,base64 可靠,所以 photoCheck 这条业务路固定发 data URL。
 * <b>不要再改回「直接发 URL」</b>:上线后每次拍照审核都会 400 → 一律走降级 → 真审核从来不生效,
 * 而且不报错、没告警 —— 正是这个仓库反复踩的「不炸所以发现不了」。
 *
 * <p>图片本来就在本机磁盘(ruoyi.profile=/www/wwwroot/uploadPath,站内 /profile/** 即其映射):
 * 优先把站内 URL/路径解析成本地文件直接读字节,不再绕一圈 HTTP 下载自己,顺带绕开
 * {@link HttpVisionImageProbe} 那条 Range 探针。本地没有(OSS/外站)才回落到下载,下载后同样转 base64。
 *
 * <p>两条路都过 {@link VisionImageGuard}(格式/32MB/8192px),超限在发请求前拒掉转降级,
 * 不发给模型等它报错。MIME 一律按真实字节的魔数判,绝不按扩展名猜 —— 2026-09-18 实测踩到过
 * 扩展名是 .jpg、内容却是 ffmpeg 产的怪 JPEG 的情况,判不出格式的按不支持走降级。
 *
 * @author chengyinhub
 */
public class VisionImageEncoder
{
    private static final Logger log = LoggerFactory.getLogger(VisionImageEncoder.class);

    /** 站内上传回执的路径前缀;磁盘根是 ruoyi.profile。 */
    private static final String STATION_PREFIX = Constants.RESOURCE_PREFIX + "/";

    private static final int DOWNLOAD_TIMEOUT_MS = 10_000;

    /** 魔数只看头部十几个字节;data URL 也只解码这一段判真实类型,不解 32MB 全量。 */
    private static final int MAGIC_BYTES = 16;
    private static final int MAGIC_BASE64_CHARS = ((MAGIC_BYTES / 3) + 1) * 4;

    private final VisionImageGuard guard;

    /** 出网闸:返回 true 才允许对 URL 发起请求。生产=拒本机/内网字面量;测试放开才能打本地测试服务器。 */
    private final Predicate<String> requestAllowed;

    public VisionImageEncoder()
    {
        this(new VisionImageGuard(), HttpVisionImageProbe::notInternalTarget);
    }

    public VisionImageEncoder(VisionImageGuard guard)
    {
        this(guard, HttpVisionImageProbe::notInternalTarget);
    }

    VisionImageEncoder(VisionImageGuard guard, Predicate<String> requestAllowed)
    {
        this.guard = guard;
        this.requestAllowed = requestAllowed;
    }

    /**
     * 编码:站内路径优先读本地文件,远程回落下载,统一转 data URL。
     *
     * @return 成功给 data URL,失败给玩家可读的降级理由;两者必居其一
     */
    public Encoded encode(String imageUrl)
    {
        String url = imageUrl == null ? "" : imageUrl.trim();
        if (url.isEmpty())
        {
            return Encoded.rejected("照片地址为空");
        }
        if (url.startsWith("data:"))
        {
            return normalizeDataUrl(url);
        }
        try
        {
            byte[] bytes = readStationFile(url);
            if (bytes == null)
            {
                if (!url.startsWith("https://") && !url.startsWith("http://"))
                {
                    throw new Reject("照片读取失败,请重拍");
                }
                bytes = download(url);
            }
            return toDataUrl(bytes);
        }
        catch (Reject e)
        {
            return Encoded.rejected(e.getMessage());
        }
    }

    /** 已带 data URL 的输入:按魔数归一化 MIME(声明值与真实字节不符时以字节为准),再过预检。 */
    private Encoded normalizeDataUrl(String dataUrl)
    {
        int comma = dataUrl.indexOf(',');
        if (comma < 0)
        {
            return Encoded.rejected("照片数据不完整");
        }
        String meta = dataUrl.substring(5, comma);
        if (!meta.contains("base64"))
        {
            return Encoded.rejected("照片只支持 base64 编码");
        }
        String payload = dataUrl.substring(comma + 1);
        String mime = detectMime(decodeHead(payload));
        if (mime == null)
        {
            return Encoded.rejected(VisionImageGuard.REASON_UNSUPPORTED_FORMAT);
        }
        String normalized = "data:" + mime + ";base64," + payload;
        String rejected = guard.rejectReason(normalized);
        return rejected == null ? Encoded.ok(normalized) : Encoded.rejected(rejected);
    }

    /**
     * 站内路径 → 本地文件。解析不到(非站内路径 / 文件不存在 / 未配置 profile)返回 null 交给下载回落;
     * 站内路径但越界(..)直接拒,绝不拿出去请求。
     */
    private static byte[] readStationFile(String imageUrl) throws Reject
    {
        String profile = RuoYiConfig.getProfile();
        if (profile == null || profile.trim().isEmpty())
        {
            return null;
        }
        String path = imageUrl;
        int query = path.indexOf('?');
        if (query >= 0)
        {
            path = path.substring(0, query);
        }
        int fragment = path.indexOf('#');
        if (fragment >= 0)
        {
            path = path.substring(0, fragment);
        }
        int idx = path.indexOf(STATION_PREFIX);
        if (idx < 0)
        {
            return null;
        }
        String relative = path.substring(idx + STATION_PREFIX.length());
        if (relative.contains(".."))
        {
            throw new Reject("照片地址不合法");
        }
        File file = new File(profile, relative);
        if (!file.isFile())
        {
            return null;
        }
        if (file.length() > VisionImageGuard.MAX_BYTES)
        {
            throw new Reject(VisionImageGuard.REASON_TOO_LARGE);
        }
        try
        {
            return Files.readAllBytes(file.toPath());
        }
        catch (IOException e)
        {
            log.warn("[VisionImageEncoder] local-read-failed file={}", file, e);
            throw new Reject("照片读取失败,请重拍");
        }
    }

    /**
     * OSS / 外站回落:下载整图;读满上限+1 就停,超限在读入内存前判拒。
     *
     * <p>★ 必须 {@code executeAsync()}:hutool 同步 {@code execute()} 会先把整个响应读进内存再交出流,
     * 后面的截断只是在截一个已在内存里的数组(2026-09-23 实测:40MB 响应被整个读入,堆涨约 112MB)。
     * 异步模式交出的是连接上的原始流,读到上限就关连接,上限才真的挡在内存前面。
     */
    private byte[] download(String url) throws Reject
    {
        // 新开的「我们服务器替客户端抓 URL」这条路:先按既有口径拒掉本机/内网字面量,
        // 且不跟随重定向(避免 302 跳到内网绕开上面的字面量检查;代价是重定向的外站图走降级)。
        if (!requestAllowed.test(url))
        {
            log.warn("[VisionImageEncoder] internal-target-rejected url={}", url);
            throw new Reject("照片读取失败,请重拍");
        }
        try (HttpResponse resp = HttpRequest.get(url)
                .timeout(DOWNLOAD_TIMEOUT_MS)
                .setMaxRedirectCount(0)
                .executeAsync())
        {
            if (!resp.isOk())
            {
                throw new Reject("照片读取失败,请重拍");
            }
            // 声明的长度已经超限就一个字节都不读;分块传输没有长度,只能靠下面边读边截。
            if (resp.contentLength() > VisionImageGuard.MAX_BYTES)
            {
                throw new Reject(VisionImageGuard.REASON_TOO_LARGE);
            }
            byte[] bytes = readUpTo(resp.bodyStream(), (int) VisionImageGuard.MAX_BYTES + 1);
            if (bytes.length > VisionImageGuard.MAX_BYTES)
            {
                throw new Reject(VisionImageGuard.REASON_TOO_LARGE);
            }
            return bytes;
        }
        catch (Reject e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.warn("[VisionImageEncoder] download-failed url={}", url, e);
            throw new Reject("照片读取失败,请重拍");
        }
    }

    /** 真实字节 → data URL;格式按魔数判,尺寸/大小交给既有 VisionImageGuard 收口。 */
    private Encoded toDataUrl(byte[] bytes) throws Reject
    {
        if (bytes == null || bytes.length == 0)
        {
            throw new Reject("照片读取失败,请重拍");
        }
        if (bytes.length > VisionImageGuard.MAX_BYTES)
        {
            throw new Reject(VisionImageGuard.REASON_TOO_LARGE);
        }
        String mime = detectMime(bytes);
        if (mime == null)
        {
            throw new Reject(VisionImageGuard.REASON_UNSUPPORTED_FORMAT);
        }
        String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
        String rejected = guard.rejectReason(dataUrl);
        if (rejected != null)
        {
            throw new Reject(rejected);
        }
        return Encoded.ok(dataUrl);
    }

    /**
     * 支持的四种格式的魔数:JPEG(ffd8ff)/ PNG / GIF87a|GIF89a / WebP(RIFF....WEBP)。
     * 判不出来返回 null = 不支持,绝不按扩展名猜。
     */
    static String detectMime(byte[] bytes)
    {
        if (bytes == null)
        {
            return null;
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF)
        {
            return "image/jpeg";
        }
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                && bytes[3] == 'G' && (bytes[4] & 0xFF) == 0x0D && (bytes[5] & 0xFF) == 0x0A
                && (bytes[6] & 0xFF) == 0x1A && (bytes[7] & 0xFF) == 0x0A)
        {
            return "image/png";
        }
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == '8' && (bytes[4] == '7' || bytes[4] == '9') && bytes[5] == 'a')
        {
            return "image/gif";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B'
                && bytes[11] == 'P')
        {
            return "image/webp";
        }
        return null;
    }

    private static byte[] decodeHead(String payload)
    {
        int take = Math.min(payload.length(), MAGIC_BASE64_CHARS);
        take -= take % 4;
        if (take <= 0)
        {
            return new byte[0];
        }
        try
        {
            return Base64.getDecoder().decode(payload.substring(0, take));
        }
        catch (IllegalArgumentException e)
        {
            return new byte[0];
        }
    }

    private static byte[] readUpTo(InputStream in, int max) throws IOException
    {
        if (in == null)
        {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        while (total < max)
        {
            int read = in.read(buffer, 0, Math.min(buffer.length, max - total));
            if (read < 0)
            {
                break;
            }
            out.write(buffer, 0, read);
            total += read;
        }
        return out.toByteArray();
    }

    /** 编码结果:成功给 data URL,失败给降级理由。 */
    public static final class Encoded
    {
        private final String dataUrl;
        private final String rejectReason;

        private Encoded(String dataUrl, String rejectReason)
        {
            this.dataUrl = dataUrl;
            this.rejectReason = rejectReason;
        }

        public static Encoded ok(String dataUrl)
        {
            return new Encoded(dataUrl, null);
        }

        public static Encoded rejected(String rejectReason)
        {
            return new Encoded(null, rejectReason);
        }

        public boolean isRejected()
        {
            return dataUrl == null;
        }

        public String getDataUrl()
        {
            return dataUrl;
        }

        public String getRejectReason()
        {
            return rejectReason;
        }
    }

    /** 内部拒收信号:带一句给玩家看的降级理由。 */
    private static final class Reject extends Exception
    {
        private static final long serialVersionUID = 1L;

        Reject(String reason)
        {
            super(reason);
        }
    }
}
