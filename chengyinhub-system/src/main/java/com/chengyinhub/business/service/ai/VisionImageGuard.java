package com.chengyinhub.business.service.ai;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * 视觉调用的图片预检:supplier 的限制是 JPEG/PNG/GIF/WebP、单图 ≤32 MiB、单边 ≤8192 px。
 * 超限在发请求前就拒掉(调用方转降级),不要发出去等供应商报错。
 *
 * <p>能确定的事实才拒;探不到的一律放行 —— 本类不制造假阴性(把合法照片判死 = 节点走死)。
 * data URL 可全量校验(photoCheck 现在只喂 data URL,走的就是这一支);
 * 远程 URL 是通用入口,靠 {@link VisionImageProbe} 的 Range 探针拿大小/类型/头部字节,
 * WebP 无内置 ImageIO reader,读不出宽高就按未知放行。
 *
 * <p>⚠️ 本类只做图片限制预检,<b>不是 SSRF 安全闸</b>:探针不解析 DNS、不校验重定向。
 * 谁把 URL 发出去由调用方负责(photoCheck 已不再把 URL 交给模型,只发 data URL);
 * 要防 SSRF 得在网络层单独收口,别把这里当防线。
 *
 * @author chengyinhub
 */
public class VisionImageGuard
{
    static final long MAX_BYTES = 32L * 1024 * 1024;
    static final int MAX_SIDE = 8192;

    /** 超限/格式拒绝理由集中在这里:编码侧与预检侧用同一句,避免文案漂移。 */
    static final String REASON_UNSUPPORTED_FORMAT = "照片格式不支持(只收 JPEG/PNG/GIF/WebP)";
    static final String REASON_TOO_LARGE = "照片超过 32MB,请压缩后再拍";
    private static final int HEAD_BYTES = 128 * 1024;
    private static final int HEAD_BASE64_CHARS = ((HEAD_BYTES / 3) + 1) * 4;

    private static final Set<String> ALLOWED_MIME = new HashSet<>(Arrays.asList(
            "image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp"));
    private static final Set<String> ALLOWED_FORMATS = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp"));

    private final VisionImageProbe probe;

    public VisionImageGuard()
    {
        this(new HttpVisionImageProbe());
    }

    public VisionImageGuard(VisionImageProbe probe)
    {
        this.probe = probe;
    }

    /**
     * 发请求前的图片预检。
     *
     * @return null=通过;非 null=拒绝原因(给玩家看的降级理由)
     */
    public String rejectReason(String imageUrl)
    {
        String url = imageUrl == null ? "" : imageUrl.trim();
        if (url.startsWith("data:"))
        {
            return checkDataUrl(url);
        }
        String format = extension(url);
        if (format != null && !ALLOWED_FORMATS.contains(format))
        {
            return REASON_UNSUPPORTED_FORMAT;
        }
        if (probe != null && (url.startsWith("https://") || url.startsWith("http://")))
        {
            VisionImageProbe.ImageHead head = probe.probe(url);
            if (head != null)
            {
                return checkRemote(head);
            }
        }
        return null;
    }

    private String checkDataUrl(String dataUrl)
    {
        int comma = dataUrl.indexOf(',');
        if (comma < 0)
        {
            return "照片数据不完整";
        }
        String meta = dataUrl.substring(5, comma);
        if (!meta.contains("base64"))
        {
            return "照片只支持 base64 编码";
        }
        String mime = meta.split(";")[0].trim().toLowerCase();
        if (!ALLOWED_MIME.contains(mime))
        {
            return REASON_UNSUPPORTED_FORMAT;
        }
        String payload = dataUrl.substring(comma + 1);
        long padding = payload.endsWith("==") ? 2L : (payload.endsWith("=") ? 1L : 0L);
        if (payload.length() / 4 * 3 - padding > MAX_BYTES)
        {
            return REASON_TOO_LARGE;
        }
        return checkDimensions(decodeHead(payload));
    }

    private String checkRemote(VisionImageProbe.ImageHead head)
    {
        if (head.getContentLength() > MAX_BYTES)
        {
            return REASON_TOO_LARGE;
        }
        String type = head.getContentType();
        if (type != null && !type.isEmpty())
        {
            String mime = type.split(";")[0].trim().toLowerCase();
            if (!ALLOWED_MIME.contains(mime))
            {
                return REASON_UNSUPPORTED_FORMAT;
            }
        }
        return checkDimensions(head.getHeadBytes());
    }

    private String checkDimensions(byte[] head)
    {
        int[] size = dimensions(head);
        if (size != null && (size[0] > MAX_SIDE || size[1] > MAX_SIDE))
        {
            return "照片单边超过 8192 像素,请压缩后再拍";
        }
        return null;
    }

    private byte[] decodeHead(String payload)
    {
        int take = Math.min(payload.length(), HEAD_BASE64_CHARS);
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

    /**
     * 从头部字节读宽高,不解像素。Java 8 自带 ImageIO 认 JPEG/PNG/GIF;
     * 读不出(WebP / 头部被截断)返回 null,按未知放行。
     */
    private int[] dimensions(byte[] head)
    {
        if (head == null || head.length == 0)
        {
            return null;
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(head)))
        {
            if (input == null)
            {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext())
            {
                return null;
            }
            ImageReader reader = readers.next();
            try
            {
                reader.setInput(input, true, true);
                return new int[] { reader.getWidth(0), reader.getHeight(0) };
            }
            finally
            {
                reader.dispose();
            }
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static String extension(String url)
    {
        String path = url;
        int query = path.indexOf('?');
        if (query >= 0)
        {
            path = path.substring(0, query);
        }
        int hash = path.indexOf('#');
        if (hash >= 0)
        {
            path = path.substring(0, hash);
        }
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot < 0 || dot < slash || dot == path.length() - 1)
        {
            return null;
        }
        String ext = path.substring(dot + 1).toLowerCase();
        return ext.length() <= 5 ? ext : null;
    }
}
