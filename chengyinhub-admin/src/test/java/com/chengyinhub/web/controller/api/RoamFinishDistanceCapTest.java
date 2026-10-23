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
