package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 视频链接规范化:去重键与"同一原视频"分组的基础。
 *
 * <p>这些断言守的是<b>反重复领取</b>:跟踪参数、"m."子域、短链写法都不能变成"另一条视频"。
 */
class VideoPlatformLinksTest {

    @Test
    void tiktokTrackingParamsDoNotChangeDedupeKey() {
        VideoPlatformLinks a = VideoPlatformLinks.canonicalize(
                "https://www.tiktok.com/@creator/video/7300000000000000001?is_from_webapp=1&si=x");
        VideoPlatformLinks b = VideoPlatformLinks.canonicalize(
                "https://m.tiktok.com/@creator/video/7300000000000000001");

        assertEquals("tiktok", a.getPlatform());
        assertEquals("tiktok:7300000000000000001", a.getCanonicalVideoKey());
        assertEquals(a.getCanonicalVideoKey(), b.getCanonicalVideoKey());
        assertFalse(a.isShortLink());
    }

    @Test
    void youtubeShortsAndWatchUrlsShareTheSameVideoKey() {
        VideoPlatformLinks shorts = VideoPlatformLinks.canonicalize(
                "https://www.youtube.com/shorts/AbCdEf12345");
        VideoPlatformLinks watch = VideoPlatformLinks.canonicalize(
                "https://www.youtube.com/watch?v=AbCdEf12345&t=30s");
        VideoPlatformLinks shortDomain = VideoPlatformLinks.canonicalize("https://youtu.be/AbCdEf12345");

        assertEquals("youtube:AbCdEf12345", shorts.getCanonicalVideoKey());
        assertEquals(shorts.getCanonicalVideoKey(), watch.getCanonicalVideoKey());
        assertEquals(shorts.getCanonicalVideoKey(), shortDomain.getCanonicalVideoKey());
    }

    @Test
    void instagramReelShortcodeIsExtracted() {
        VideoPlatformLinks reel = VideoPlatformLinks.canonicalize(
                "https://www.instagram.com/reel/Cx1y2Z3/?igsh=whatever");

        assertEquals("reels", reel.getPlatform());
        assertEquals("reels:Cx1y2Z3", reel.getCanonicalVideoKey());
    }

    @Test
    void tiktokShortLinkIsMarkedAndCannotPretendToBeLongForm() {
        VideoPlatformLinks shortLink = VideoPlatformLinks.canonicalize("https://vm.tiktok.com/ZMabc123/");

        assertTrue(shortLink.isShortLink(), "短链无法离线解析成视频 id,必须显式标记");
        assertEquals("tiktok:short:ZMabc123", shortLink.getCanonicalVideoKey());
    }

    @Test
    void rejectsInsecureAndCredentialedLinks() {
        assertEquals("LINK_SCHEME_NOT_ALLOWED", reason("http://www.tiktok.com/@a/video/1"));
        assertEquals("LINK_USERINFO_NOT_ALLOWED", reason("https://u:p@www.tiktok.com/@a/video/1"));
    }

    @Test
    void rejectsUnknownPlatformInsteadOfGuessing() {
        assertEquals("LINK_PLATFORM_UNSUPPORTED", reason("https://vimeo.com/123456"));
        assertEquals("LINK_VIDEO_ID_MISSING", reason("https://www.tiktok.com/@creator"));
    }

    @Test
    void originGroupKeyDefaultsToTheVideoItself() {
        assertEquals("tiktok:7300000000000000001",
                VideoPlatformLinks.normalizeOriginGroupKey(null, "tiktok:7300000000000000001"));
        assertEquals("my-repost-1",
                VideoPlatformLinks.normalizeOriginGroupKey(" My-Repost-1 ", "tiktok:7300000000000000001"));
    }

    @Test
    void originGroupKeyRejectsUnsafeCharacters() {
        assertThrows(VideoPlatformLinks.InvalidLinkException.class,
                () -> VideoPlatformLinks.normalizeOriginGroupKey("bad key!", "tiktok:1"));
    }

    @Test
    void declaredPlatformMustBeSupported() {
        assertEquals("tiktok", VideoPlatformLinks.normalizePlatform(" TikTok "));
        assertThrows(VideoPlatformLinks.InvalidLinkException.class,
                () -> VideoPlatformLinks.normalizePlatform("myspace"));
    }

    private static String reason(String url) {
        VideoPlatformLinks.InvalidLinkException failure = assertThrows(
                VideoPlatformLinks.InvalidLinkException.class,
                () -> VideoPlatformLinks.canonicalize(url));
        return failure.getReasonCode();
    }
}
