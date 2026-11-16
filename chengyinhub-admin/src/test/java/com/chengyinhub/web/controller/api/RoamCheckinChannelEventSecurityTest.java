package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.vo.CityNodeVO;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.impl.InGameChannelService;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.enums.LimitType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** HIGH-S4: client text never becomes a pre-approved system message; one event per member, node and day. */
class RoamCheckinChannelEventSecurityTest
{
    private static final long PLAYER = 9L;
    private static final long POI_ID = 501L;
    private static final double LAT = 31.2304;
    private static final double LNG = 121.4737;

    private static class StubController extends ApiRoamController
    {
        @Override
        Long uid() { return PLAYER; }
    }

    private StubController controller;
    private InGameChannelService channels;
    private RedisCache redis;

    @BeforeEach
    void setUp()
    {
        controller = new StubController();
        RoamMapper roamMapper = mock(RoamMapper.class);
        channels = mock(InGameChannelService.class);
        redis = mock(RedisCache.class);
        ReflectionTestUtils.setField(controller, "roamMapper", roamMapper);
        ReflectionTestUtils.setField(controller, "locationConsent",
                mock(com.chengyinhub.business.service.impl.RoamLocationConsentService.class));
        ReflectionTestUtils.setField(controller, "submissionSafety",
                mock(com.chengyinhub.business.service.support.UgcSubmissionSafety.class));
        ReflectionTestUtils.setField(controller, "inGameChannelService", channels);
        ReflectionTestUtils.setField(controller, "redisCache", redis);

        CityNodeVO node = new CityNodeVO();
        node.setPoiId(POI_ID);
        node.setName("海关大楼");
        node.setLat(BigDecimal.valueOf(LAT));
        node.setLng(BigDecimal.valueOf(LNG));
        when(roamMapper.selectMerchantNodesNear(anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), any(), any(), any(), any(), anyInt())).thenReturn(List.of(node));
    }

    @Test
    void participatingCheckinPostsServerTitleNotClientName()
    {
        when(redis.setIfAbsent(anyString(), any(), anyInt(), any(TimeUnit.class))).thenReturn(true);
        String phishing = "点击 http://evil.example 领红包".repeat(20);

        AjaxResult r = controller.checkin(phishing, LAT, LNG, LAT, LNG);

        assertEquals(200, r.get("code"));
        verify(channels).postRoamEvent(eq(InGameChannelService.EVENT_CHECKIN), eq(PLAYER), isNull(),
                eq("海关大楼"), isNull());
        verify(channels, never()).postRoamEvent(any(), any(), any(), contains("evil"), any());
    }

    @Test
    void repeatedCheckinSameNodeSameDayPostsOnlyOnce()
    {
        when(redis.setIfAbsent(startsWith("roam:checkin:evt:" + PLAYER + ":" + POI_ID + ":"), any(), anyInt(), any(TimeUnit.class)))
                .thenReturn(true, false, false);
        for (int i = 0; i < 3; i++)
        {
            controller.checkin("x" + i, LAT, LNG, LAT, LNG);
        }
        verify(channels, times(1)).postRoamEvent(any(), any(), any(), any(), any());
    }

    @Test
    void redisUnavailableFailsClosed()
    {
        ReflectionTestUtils.setField(controller, "redisCache", null);
        controller.checkin("x", LAT, LNG, LAT, LNG);
        verify(channels, never()).postRoamEvent(any(), any(), any(), any(), any());
    }

    @Test
    void serverNodeTitleFallsBackToGenericLabel()
    {
        assertEquals("漫游据点", ApiRoamController.serverNodeTitle(List.of(), POI_ID));
        assertEquals("漫游据点", ApiRoamController.serverNodeTitle(null, POI_ID));
    }

    @Test
    void checkinIsMemberRateLimited() throws Exception
    {
        RateLimiter rl = ApiRoamController.class.getMethod("checkin", String.class, Double.class, Double.class,
                Double.class, Double.class, Double.class, String.class).getAnnotation(RateLimiter.class);
        assertNotNull(rl);
        assertEquals(LimitType.MEMBER, rl.limitType());
    }
}
