package com.chengyinhub.business.growthloops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.core.redis.RedisCache;

class MiniProgramUrlLinkServiceTest
{
    @Test
    void validatesPageAndQuery()
    {
        assertEquals("pages/club/detail/index", MiniProgramUrlLinkService.normalizePage("/pages/club/detail/index"));
        assertEquals("subpackageP3/pages/growthcenter/index/index", MiniProgramUrlLinkService.normalizePage("subpackageP3/pages/growthcenter/index/index"));
        assertThrows(IllegalArgumentException.class, () -> MiniProgramUrlLinkService.normalizePage("https://evil.example/pages/a"));
        assertThrows(IllegalArgumentException.class, () -> MiniProgramUrlLinkService.normalizePage("pages/../x"));
        assertThrows(IllegalArgumentException.class, () -> MiniProgramUrlLinkService.normalizePage("other/x"));
        assertEquals("id=3&tab=a", MiniProgramUrlLinkService.normalizeQuery("?id=3&tab=a"));
        assertThrows(IllegalArgumentException.class, () -> MiniProgramUrlLinkService.normalizeQuery("id=<script>"));
    }

    @Test
    void honestUnavailableWithoutToken()
    {
        IWxContentSecurityService tokens = mock(IWxContentSecurityService.class);
        when(tokens.getAccessToken()).thenReturn("");
        Map<String, Object> out = new MiniProgramUrlLinkService((u, b) -> fail("no call"), tokens, null).link("pages/club/detail/index", "id=1");
        assertEquals(false, out.get("available"));
        assertEquals("WECHAT_NOT_CONFIGURED", out.get("reason"));
    }

    @Test
    void generatesReleaseLinkAndCachesIt()
    {
        IWxContentSecurityService tokens = mock(IWxContentSecurityService.class);
        when(tokens.getAccessToken()).thenReturn("TOKEN");
        RedisCache redis = mock(RedisCache.class);
        List<String> bodies = new ArrayList<>();
        MiniProgramUrlLinkService s = new MiniProgramUrlLinkService((u, b) -> {
            assertTrue(u.endsWith("access_token=TOKEN"));
            bodies.add(b);
            return "{\"errcode\":0,\"url_link\":\"https://wxaurl.cn/abc\"}";
        }, tokens, redis);
        Map<String, Object> out = s.link("/pages/club/detail/index", "id=12");
        assertEquals(true, out.get("available"));
        assertEquals("https://wxaurl.cn/abc", out.get("url"));
        assertTrue(bodies.get(0).contains("\"env_version\":\"release\""));
        assertTrue(bodies.get(0).contains("\"path\":\"pages/club/detail/index\""));
        verify(redis).setCacheObject(eq(MiniProgramUrlLinkService.CACHE_PREFIX + "pages/club/detail/index?id=12"),
                eq("https://wxaurl.cn/abc"), eq(7), eq(TimeUnit.DAYS));
        when(redis.getCacheObject(anyString())).thenReturn("https://wxaurl.cn/cached");
        assertEquals("https://wxaurl.cn/cached", s.link("pages/club/detail/index", "id=12").get("url"));
        assertEquals(1, bodies.size());
    }

    @Test
    void wechatRefusalAndNetworkFailureAreReportedNotThrown()
    {
        IWxContentSecurityService tokens = mock(IWxContentSecurityService.class);
        when(tokens.getAccessToken()).thenReturn("TOKEN");
        assertEquals("WECHAT_REFUSED", new MiniProgramUrlLinkService((u, b) -> "{\"errcode\":40165}", tokens, null)
                .link("pages/a/index", null).get("reason"));
        assertEquals("WECHAT_UNREACHABLE", new MiniProgramUrlLinkService((u, b) -> { throw new java.io.IOException(); }, tokens, null)
                .link("pages/a/index", null).get("reason"));
    }
}
