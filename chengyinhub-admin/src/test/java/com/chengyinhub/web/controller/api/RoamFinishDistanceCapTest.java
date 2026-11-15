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
    void finishRetainsFirstLegAndFreezesStartCityWithoutChangingAwardRules() {
        for (boolean geocodeFails : new boolean[]{false, true}) {
            ApiRoamController controller = spy(new ApiRoamController());
            RoamMapper mapper = mock(RoamMapper.class);
            com.chengyinhub.business.service.IMapGeocodeService geocode = mock(com.chengyinhub.business.service.IMapGeocodeService.class);
            ReflectionTestUtils.setField(controller, "roamMapper", mapper);
            ReflectionTestUtils.setField(controller, "mapGeocodeService", geocode);
            ReflectionTestUtils.setField(controller, "npcFeatureFlags", mock(NpcFeatureFlags.class));
            ReflectionTestUtils.setField(controller, "growthBadgeMapper", mock(GrowthBadgeMapper.class));
            ReflectionTestUtils.setField(controller, "roamBadgeDeliveryService", mock(com.chengyinhub.business.service.impl.RoamBadgeDeliveryService.class));
            doReturn(77L).when(controller).getAppUserId();
            RoamSession session = new RoamSession();
            session.setId(9L); session.setMemberId(77L); session.setStatus(0);
            session.setStartedAt(new Date(System.currentTimeMillis() - 120_000));
            when(mapper.selectOwnedSessionForUpdate(9L, 77L)).thenReturn(session);
            when(mapper.selectFoundPoisBySession(9L)).thenReturn(Collections.emptyList());
            when(mapper.updateSessionFinish(org.mockito.ArgumentMatchers.any())).thenReturn(1);
            when(mapper.saveFinishResult(org.mockito.ArgumentMatchers.eq(9L), org.mockito.ArgumentMatchers.eq(77L), org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
            if (geocodeFails) when(geocode.resolveCity(121.4, 31.2)).thenThrow(new IllegalStateException("unavailable"));
            else when(geocode.resolveCity(121.4, 31.2)).thenReturn("上海");
            var response = controller.finish("9", "", 1240, "[[31.2,121.4],[31.211,121.4]]");
            assertEquals(200, response.get("code"));
            assertEquals(1240, session.getDistanceM());
            verify(geocode).resolveCity(121.4, 31.2);
            ArgumentCaptor<String> result = ArgumentCaptor.forClass(String.class);
            verify(mapper).saveFinishResult(org.mockito.ArgumentMatchers.eq(9L), org.mockito.ArgumentMatchers.eq(77L), result.capture());
            var saved = com.alibaba.fastjson2.JSON.parseObject(result.getValue());
            assertEquals(1240, saved.getInteger("distanceM"));
            assertEquals(geocodeFails ? null : "上海", saved.getString("zone"));
            verify(mapper).saveSessionTrack(9L, 77L, "[[31.2,121.4],[31.211,121.4]]");
        }
        assertEquals(31.2, com.chengyinhub.business.service.support.RoamTrackSimplifier.startPoint("[[31.2,121.4]]")[0]);
        assertEquals(null, com.chengyinhub.business.service.support.RoamTrackSimplifier.startPoint("[[91,121.4]]"));
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
