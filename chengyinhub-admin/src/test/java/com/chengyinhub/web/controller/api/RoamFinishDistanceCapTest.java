package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.badge.RuleEvaluator;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.mapper.GrowthBadgeMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.IGrowthService;
import java.util.Collections;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static com.chengyinhub.web.controller.api.ApiRoamController.clampDistanceM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 漫游结算里程不能整段采信客户端:上限 = 开始至今秒数 × 瞬移速度上限(35m/s)。 */
class RoamFinishDistanceCapTest
{
    private static final long NOW = 1_800_000_000_000L;

    @Test
    void clampKeepsPlausibleDistanceAndCapsForgedOne()
    {
        Date tenMinutesAgo = new Date(NOW - 600_000L);
        assertEquals(1200, clampDistanceM(1200, tenMinutesAgo, NOW), "10 分钟走 1.2km 原样记");
        assertEquals(21000, clampDistanceM(21000, tenMinutesAgo, NOW), "恰在上限内");
        assertEquals(21000, clampDistanceM(999_999, tenMinutesAgo, NOW), "伪造的大里程按 600s×35m/s 封顶");
        assertEquals(21000, clampDistanceM(Integer.MAX_VALUE, tenMinutesAgo, NOW));
    }

    @Test
    void clampFailsClosedOnMissingOrBadInput()
    {
        Date start = new Date(NOW - 600_000L);
        assertEquals(0, clampDistanceM(null, start, NOW));
        assertEquals(0, clampDistanceM(-5, start, NOW));
        assertEquals(0, clampDistanceM(0, start, NOW));
        assertEquals(0, clampDistanceM(500, null, NOW), "缺开始时间不采信");
        assertEquals(0, clampDistanceM(500, new Date(NOW + 60_000L), NOW), "开始时间在未来不采信");
    }

    @Test
    void finishPersistsClampedDistance()
    {
        ApiRoamController controller = spy(new ApiRoamController());
        RoamMapper roamMapper = mock(RoamMapper.class);
        ReflectionTestUtils.setField(controller, "roamMapper", roamMapper);
        ReflectionTestUtils.setField(controller, "growthService", mock(IGrowthService.class));
        ReflectionTestUtils.setField(controller, "playerBadgeMapper", mock(PlayerBadgeMapper.class));
        ReflectionTestUtils.setField(controller, "growthBadgeMapper", mock(GrowthBadgeMapper.class));
        ReflectionTestUtils.setField(controller, "ruleEvaluator", new RuleEvaluator());
        doReturn(77L).when(controller).getAppUserId();

        RoamSession session = new RoamSession();
        session.setId(9L);
        session.setMemberId(77L);
        session.setStatus(0);
        session.setStartedAt(new Date(System.currentTimeMillis() - 60_000L));
        when(roamMapper.selectOwnedSessionForUpdate(9L, 77L)).thenReturn(session);
        when(roamMapper.selectFoundPoisBySession(9L)).thenReturn(Collections.emptyList());
        when(roamMapper.updateSessionFinish(org.mockito.ArgumentMatchers.any(RoamSession.class))).thenReturn(0);

        controller.finish("9", "", 500_000);

        ArgumentCaptor<RoamSession> saved = ArgumentCaptor.forClass(RoamSession.class);
        verify(roamMapper).updateSessionFinish(saved.capture());
        int distance = saved.getValue().getDistanceM();
        // 开始约 60s:上限约 2100m(留出测试执行耗时的余量),绝不是客户端填的 500km
        assertTrue(distance >= 2000 && distance <= 2200, "distance=" + distance);
    }
}
