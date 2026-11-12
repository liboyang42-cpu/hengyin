package com.chengyinhub.business.service.support;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * 外部视频链接的规范化与去重键。
 *
 * <p><b>为什么必须有</b>:同一段原视频会被剪成 TikTok / Reels / YouTube Shorts 多个贴,
 * 也可能在同一平台被重复上传。去重和"同一原视频跨平台合计封顶"都依赖一个稳定的
 * 规范化键;靠原始 URL 字符串比较会把 {@code ?si=} 这类跟踪参数当成不同视频。
 *
 * <p><b>边界(明确,不掩饰)</b>:
 * <ul>
 *   <li>不允许 {@code http}、不允许 userinfo({@code user:pass@})、不允许非白名单域名 —— 这些
 *       要么不安全,要么无法判定是哪个平台。</li>
 *   <li>短链({@code vm.tiktok.com/xxx})在无网络访问的前提下无法解析成视频 id,
 *       故规范化为 {@code tiktok:short:<code>} 并标记 {@code shortLink=true}。
 *       它只能对"完全相同的短链"去重,不能与长链自动合并 —— 这是能力上限,不是疏忽。</li>
 *   <li>本类不做任何网络请求:平台播放数据必须来自外部证据录入,不能由我们"查"出来。</li>
 * </ul>
 *
 * @author chengyinhub
 */
public final class VideoPlatformLinks
{
    public static final String PLATFORM_TIKTOK = "tiktok";
    public static final String PLATFORM_REELS = "reels";
    public static final String PLATFORM_YOUTUBE = "youtube";

    private static final int MAX_URL_LENGTH = 512;

    private final String platform;
    private final String canonicalVideoKey;
    private final boolean shortLink;

    private VideoPlatformLinks(String platform, String canonicalVideoKey, boolean shortLink) {
        this.platform = platform;
        this.canonicalVideoKey = canonicalVideoKey;
        this.shortLink = shortLink;
    }

    public String getPlatform() { return platform; }
    public String getCanonicalVideoKey() { return canonicalVideoKey; }
    public boolean isShortLink() { return shortLink; }

    /** 规范化失败的原因码。 */
    public static final class InvalidLinkException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String reasonCode;

        InvalidLinkException(String reasonCode, String message) {
            super(message);
            this.reasonCode = reasonCode;
        }

        public String getReasonCode() { return reasonCode; }
    }

    /**
     * 规范化一个外部视频链接。
     *
     * @throws InvalidLinkException 无法判定平台或缺少视频标识时抛出,<b>不猜测</b>
     */
    public static VideoPlatformLinks canonicalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new InvalidLinkException("LINK_REQUIRED", "缺少视频链接");
        }
        String trimmed = rawUrl.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new InvalidLinkException("LINK_TOO_LONG", "视频链接过长");
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException malformed) {
            throw new InvalidLinkException("LINK_MALFORMED", "视频链接格式不合法");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new InvalidLinkException("LINK_SCHEME_NOT_ALLOWED", "只接受 https 视频链接");
        }
        if (uri.getUserInfo() != null) {
            throw new InvalidLinkException("LINK_USERINFO_NOT_ALLOWED", "视频链接不允许携带账号信息");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new InvalidLinkException("LINK_MALFORMED", "视频链接缺少主机名");
        }
        host = host.toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();
        String query = uri.getQuery();

        if (host.equals("tiktok.com") || host.equals("www.tiktok.com") || host.equals("m.tiktok.com")) {
            String videoId = tiktokVideoId(path);
            if (videoId != null) {
                return new VideoPlatformLinks(PLATFORM_TIKTOK, PLATFORM_TIKTOK + ":" + videoId, false);
            }
            throw new InvalidLinkException("LINK_VIDEO_ID_MISSING", "TikTok 链接缺少视频 id");
        }
        if (host.equals("vm.tiktok.com") || host.equals("vt.tiktok.com")) {
            String code = firstPathSegment(path);
            if (code == null) {
                throw new InvalidLinkException("LINK_VIDEO_ID_MISSING", "TikTok 短链缺少标识");
            }
            return new VideoPlatformLinks(PLATFORM_TIKTOK, PLATFORM_TIKTOK + ":short:" + code, true);
        }

        if (host.equals("youtube.com") || host.equals("www.youtube.com") || host.equals("m.youtube.com")) {
            String videoId = youtubeVideoId(path, query);
            if (videoId != null) {
                return new VideoPlatformLinks(PLATFORM_YOUTUBE, PLATFORM_YOUTUBE + ":" + videoId, false);
            }
            throw new InvalidLinkException("LINK_VIDEO_ID_MISSING", "YouTube 链接缺少视频 id");
        }
        if (host.equals("youtu.be")) {
            String videoId = firstPathSegment(path);
            if (videoId != null) {
                return new VideoPlatformLinks(PLATFORM_YOUTUBE, PLATFORM_YOUTUBE + ":" + videoId, false);
            }
            throw new InvalidLinkException("LINK_VIDEO_ID_MISSING", "YouTube 短链缺少视频 id");
        }

        if (host.equals("instagram.com") || host.equals("www.instagram.com")) {
            String code = instagramShortcode(path);
            if (code != null) {
                return new VideoPlatformLinks(PLATFORM_REELS, PLATFORM_REELS + ":" + code, false);
            }
            throw new InvalidLinkException("LINK_VIDEO_ID_MISSING", "Instagram 链接缺少 Reel 标识");
        }

        throw new InvalidLinkException("LINK_PLATFORM_UNSUPPORTED",
                "不支持的平台:只接受 TikTok / Instagram Reels / YouTube");
    }

    /**
     * 同组键规范化:作者声明的"同一原视频"分组。
     *
     * <p>留空时退化为"本条规范化视频键自成一组"。作者若把同一原视频拆成不同组,
     * 只能靠人工审核合并 —— 代码无法仅凭链接证明两个贴是同一条素材。
     */
    public static String normalizeOriginGroupKey(String declaredGroupKey, String canonicalVideoKey) {
        if (declaredGroupKey == null || declaredGroupKey.isBlank()) {
            return canonicalVideoKey;
        }
        String normalized = declaredGroupKey.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 64) {
            throw new InvalidLinkException("GROUP_KEY_TOO_LONG", "原视频分组键过长");
        }
        if (!normalized.matches("[a-z0-9][a-z0-9_-]*")) {
            throw new InvalidLinkException("GROUP_KEY_MALFORMED", "原视频分组键只允许小写字母、数字、下划线、连字符");
        }
        return normalized;
    }

    /** 平台名规范化。作者声明的平台必须与链接一致,不接受"平台写错"蒙过封顶。 */
    public static String normalizePlatform(String declaredPlatform) {
        if (declaredPlatform == null || declaredPlatform.isBlank()) {
            throw new InvalidLinkException("PLATFORM_REQUIRED", "缺少平台");
        }
        String normalized = declaredPlatform.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals(PLATFORM_TIKTOK) && !normalized.equals(PLATFORM_REELS)
                && !normalized.equals(PLATFORM_YOUTUBE)) {
            throw new InvalidLinkException("PLATFORM_UNSUPPORTED", "不支持的平台");
        }
        return normalized;
    }

    // ------------------------------------------------------------------ 平台私有解析

    /** {@code /@user/video/<id>} ⇒ id。其他形态返回 null,由调用方拒绝。 */
    private static String tiktokVideoId(String path) {
        String[] segments = path.split("/");
        for (int i = 0; i + 1 < segments.length; i++) {
            if ("video".equals(segments[i]) && isIdentifier(segments[i + 1])) {
                return segments[i + 1];
            }
        }
        return null;
    }

    private static String youtubeVideoId(String path, String query) {
        String[] segments = path.split("/");
        if (segments.length >= 3 && "shorts".equals(segments[1]) && isIdentifier(segments[2])) {
            return segments[2];
        }
        if (query != null) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && "v".equals(pair.substring(0, eq))) {
                    String value = pair.substring(eq + 1);
                    if (isIdentifier(value)) return value;
                }
            }
        }
        return null;
    }

    /** {@code /reel/<code>}、{@code /reels/<code>}、{@code /p/<code>}。 */
    private static String instagramShortcode(String path) {
        String[] segments = path.split("/");
        for (int i = 0; i + 1 < segments.length; i++) {
            String head = segments[i];
            if (("reel".equals(head) || "reels".equals(head) || "p".equals(head))
                    && isIdentifier(segments[i + 1])) {
                return segments[i + 1];
            }
        }
        return null;
    }

    private static String firstPathSegment(String path) {
        for (String segment : path.split("/")) {
            if (!segment.isEmpty()) {
                return isIdentifier(segment) ? segment : null;
            }
        }
        return null;
    }

    /** 平台 id 只允许字母数字与 {@code _ -};含其他字符一律不认,避免把路径片段当 id。 */
    private static boolean isIdentifier(String value) {
        if (value == null || value.isEmpty() || value.length() > 64) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }
}
