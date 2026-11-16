package com.chengyinhub.business.growthloops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.core.redis.RedisCache;

/** ONB-6:公开落地页的小程序 URL Link。匿名可调,只认白名单内容页与规范 id,新组合受每小时预算约束。 */
class ShareUrlLinkServiceTest
{
    private static MiniProgramUrlLinkService wechat(String reply, int[] calls)
    {
        IWxContentSecurityService tokens = mock(IWxContentSecurityService.class);
        when(tokens.getAccessToken()).thenReturn("TOKEN");
        return new MiniProgramUrlLinkService((u, b) -> { calls[0]++; return reply; }, tokens, null);
    }

    @Test
    void allowlistAndCanonicalIdOnly()
    {
        ShareUrlLinkService s = new ShareUrlLinkService(wechat("{}", new int[1]), null, 600);
        for (String path : new String[] { null, "", "/door", "/pages/order/index", "pages/club/detail/index", "/pages/club/detail/index/../x" })
            assertThrows(IllegalArgumentException.class, () -> s.link(path, "1"), String.valueOf(path));
        for (String id : new String[] { null, "", "0", "007", "-1", "1.5", "1e3", "abc", "1234567890123456789" })
            assertThrows(IllegalArgumentException.class, () -> s.link("/pages/club/detail/index", id), String.valueOf(id));
    }

    @Test
    void mapsAasaPathToTheMiniProgramPageAndItsIdKey()
    {
        int[] calls = new int[1];
        StringBuilder sent = new StringBuilder();
        IWxContentSecurityService tokens = mock(IWxContentSecurityService.class);
        when(tokens.getAccessToken()).thenReturn("TOKEN");
        MiniProgramUrlLinkService links = new MiniProgramUrlLinkService((u, b) -> {
            calls[0]++; sent.append(b);
            return "{\"errcode\":0,\"url_link\":\"https://wxaurl.cn/x\"}";
        }, tokens, null);
        Map<String, Object> out = new ShareUrlLinkService(links, null, 600).link("/subpackageRoam/poi-detail/index", "42");
        assertEquals(true, out.get("available"));
        assertEquals("https://wxaurl.cn/x", out.get("url"));
        assertTrue(sent.toString().contains("\"path\":\"subpackageRoam/poi-detail/index\""));
        assertTrue(sent.toString().contains("\"query\":\"poiId=42\""), "小程序据点页读 poiId");
        new ShareUrlLinkService(links, null, 600).link("/pages/club/detail/index", "12");
        assertTrue(sent.toString().contains("\"query\":\"id=12\""));
        assertEquals(2, calls[0]);
    }

    @Test
    void wechatFailureIsAnHonestUnavailableState()
    {
        Map<String, Object> out = new ShareUrlLinkService(wechat("{\"errcode\":40165}", new int[1]), null, 600)
                .link("/pages/topic/index/index", "3");
        assertEquals(false, out.get("available"));
        assertEquals("WECHAT_REFUSED", out.get("reason"));
    }

    @Test
    void freshCombinationsBeyondTheHourlyBudgetDoNotCallWechat()
    {
        int[] calls = new int[1];
        RedisCache redis = mock(RedisCache.class);
        when(redis.setIfAbsent(startsWith(ShareUrlLinkService.SEEN_PREFIX), any(), eq(7), eq(TimeUnit.DAYS))).thenReturn(true);
        when(redis.incrementWithinWindow(ShareUrlLinkService.BUDGET_KEY, 1, TimeUnit.HOURS)).thenReturn(3L);
        Map<String, Object> out = new ShareUrlLinkService(wechat("{\"errcode\":0,\"url_link\":\"https://wxaurl.cn/x\"}", calls), redis, 2)
                .link("/pages/activity/detail/index", "9");
        assertEquals(false, out.get("available"));
        assertEquals("BUSY", out.get("reason"));
        assertEquals(0, calls[0]);
        verify(redis).deleteObject(ShareUrlLinkService.SEEN_PREFIX + "pages/activity/detail/index?9");
    }

    @Test
    void seenCombinationsDoNotSpendBudget()
    {
        RedisCache redis = mock(RedisCache.class);
        when(redis.setIfAbsent(anyString(), any(), anyInt(), any())).thenReturn(false);
        int[] calls = new int[1];
        new ShareUrlLinkService(wechat("{\"errcode\":0,\"url_link\":\"https://wxaurl.cn/x\"}", calls), redis, 0)
                .link("/pages/square/detail/index", "5");
        verify(redis, never()).incrementWithinWindow(anyString(), anyInt(), any());
        assertEquals(1, calls[0]);
    }
}
