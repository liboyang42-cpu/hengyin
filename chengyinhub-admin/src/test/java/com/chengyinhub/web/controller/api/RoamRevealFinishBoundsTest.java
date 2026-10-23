package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.badge.RuleEvaluator;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.mapper.GrowthBadgeMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.impl.RoamLocationConsentService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-23:/reveal 的格子与 /finish 的里程都是客户端自报,服务端必须有上界。
 * 负控:删掉 reveal 的预算截断 ⇒ 刚开局 500 格全数入库,第二条红;
 *      恢复 finish 原样采信 distanceM ⇒ 天文里程落库,第四条红。
 */
class RoamRevealFinishBoundsTest {

    private static final Long MEMBER = 77L;

    private RoamMapper roamMapper;
    private ApiRoamController controller;
    private RoamSession session;

    @BeforeEach
    void setUp() {
        roamMapper = mock(RoamMapper.class);
        controller = spy(new ApiRoamController());
        doReturn(MEMBER).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "roamMapper", roamMapper);
        ReflectionTestUtils.setField(controller, "locationConsent", mock(RoamLocationConsentService.class));
        ReflectionTestUtils.setField(controller, "growthService", mock(IGrowthService.class));
        ReflectionTestUtils.setField(controller, "playerBadgeMapper", mock(PlayerBadgeMapper.class));
        ReflectionTestUtils.setField(controller, "growthBadgeMapper", mock(GrowthBadgeMapper.class));
        ReflectionTestUtils.setField(controller, "ruleEvaluator", new RuleEvaluator());
        ReflectionTestUtils.setField(controller, "roamBadgeDeliveryService",
                mock(com.chengyinhub.business.service.impl.RoamBadgeDeliveryService.class));
        ReflectionTestUtils.setField(controller, "npcFeatureFlags", mock(NpcFeatureFlags.class));

        session = new RoamSession();
        session.setId(9L);
        session.setMemberId(MEMBER);
        session.setStatus(0);
        when(roamMapper.selectOwnedSessionForUpdate(9L, MEMBER)).thenReturn(session);
    }

    private static String tiles(int n) {
        // 合法 geohash7:前缀固定,后两位遍历字母表,保证 n ≤ 1024 时互不相同
        String alphabet = "0123456789bcdefghjkmnpqrstuvwxyz";
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add("wtw3s" + alphabet.charAt(i / 32) + alphabet.charAt(i % 32));
        return String.join(",", out);
    }

    @Test
    void revealDropsMalformedTiles() {
        assertTrue(ApiRoamController.isGeohash7("wtw3sjq"));
        assertFalse(ApiRoamController.isGeohash7("wtw3sj"), "6 位精度不同,不是迷雾格");
        assertFalse(ApiRoamController.isGeohash7("wtw3sjqq"), "8 位精度不同,不是迷雾格");
        assertFalse(ApiRoamController.isGeohash7("wtw3sja"), "a 不在 geohash 字母表");
        assertFalse(ApiRoamController.isGeohash7("WTW3SJQ"), "大写不是同一个格子键");

        session.setStartedAt(new Date());
        AjaxResult result = controller.reveal("9", "zzzzzzzzzzzz,abc,wtw3sja");
        assertEquals("没有要点亮的格子", result.get(AjaxResult.MSG_TAG));
        verify(roamMapper, never()).batchInsertTile(any(), any(), anyList(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void revealCapsNewTilesBySessionDuration() {
        session.setStartedAt(new Date());                    // 刚开局:只有基础额度
        when(roamMapper.countTilesBySession(9L)).thenReturn(150);
        when(roamMapper.batchInsertTile(eq(MEMBER), eq(9L), anyList(), any(Date.class))).thenReturn(50);

        AjaxResult result = controller.reveal("9", tiles(500));

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(roamMapper).batchInsertTile(eq(MEMBER), eq(9L), captor.capture(), any(Date.class));
        assertEquals(ApiRoamController.REVEAL_TILE_BASE - 150, captor.getValue().size(),
                "刚开局一次上报 500 格只能落到基础额度的剩余部分");
    }

    @Test
    void revealBudgetGrowsWithSessionDurationAndExhaustedBudgetWritesNothing() {
        long now = System.currentTimeMillis();
        assertEquals(ApiRoamController.REVEAL_TILE_BASE, ApiRoamController.revealTileBudget(null, now));
        // 1 小时 × 35 m/s ÷ 100 m/格 = 1260 格
        assertEquals(ApiRoamController.REVEAL_TILE_BASE + 1260,
                ApiRoamController.revealTileBudget(new Date(now - 3_600_000L), now));

        session.setStartedAt(new Date());
        when(roamMapper.countTilesBySession(9L)).thenReturn(ApiRoamController.REVEAL_TILE_BASE);
        AjaxResult result = controller.reveal("9", tiles(3));
        assertEquals(200, result.get(AjaxResult.CODE_TAG), "预算用尽也不能报错:客户端待传队列不能因此卡死");
        verify(roamMapper, never()).batchInsertTile(any(), any(), anyList(), any());
    }

    @Test
    void finishClampsClientReportedDistance() {
        Date start = new Date(System.currentTimeMillis() - 10 * 60_000L);   // 10 分钟
        Date end = new Date(start.getTime() + 10 * 60_000L);
        // 10 分钟 × 35 m/s = 21000 m,再加 5 格 × 300 m
        assertEquals(22_500, ApiRoamController.clampClientDistance(999_999, start, end, 5));
        assertEquals(3_000, ApiRoamController.clampClientDistance(3_000, start, end, 5), "合理里程原样保留");
        assertEquals(0, ApiRoamController.clampClientDistance(-5, start, end, 5));
        assertEquals(0, ApiRoamController.clampClientDistance(null, start, end, 5));
        Date longStart = new Date(end.getTime() - 48 * 3_600_000L);
        assertEquals(ApiRoamController.ROAM_MAX_DISTANCE_M,
                ApiRoamController.clampClientDistance(Integer.MAX_VALUE, longStart, end, 10_000), "总上限封顶");

        session.setStartedAt(new Date(System.currentTimeMillis() - 60_000L));
        when(roamMapper.selectFoundPoisBySession(9L)).thenReturn(Collections.emptyList());
        when(roamMapper.countTilesBySession(9L)).thenReturn(0);
        when(roamMapper.updateSessionFinish(any(RoamSession.class))).thenReturn(1);
        when(roamMapper.saveFinishResult(eq(9L), eq(MEMBER), any())).thenReturn(1);

        controller.finish("9", "", 1_000_000);

        ArgumentCaptor<RoamSession> captor = ArgumentCaptor.forClass(RoamSession.class);
        verify(roamMapper).updateSessionFinish(captor.capture());
        int stored = captor.getValue().getDistanceM();
        assertTrue(stored > 0 && stored <= 60 * 35 + 35, "1 分钟的会话落库里程不得超过 35 m/s × 时长,实际 " + stored);
    }
}
