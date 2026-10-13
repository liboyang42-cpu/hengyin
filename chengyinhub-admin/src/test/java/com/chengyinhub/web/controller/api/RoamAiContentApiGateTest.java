package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.badge.RuleEvaluator;
import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.mapper.GrowthBadgeMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IRewardDropService;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.RoamAiContentService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** API 负控：开关、服务端到点与审核内容三个条件缺任一项都不得给客户端 meaning/story。 */
class RoamAiContentApiGateTest {
    private static final Long MEMBER = 77L;

    private ApiRoamController controller;
    private RoamMapper roamMapper;
    private NpcFeatureFlags flags;
    private RoamAiContentService contentService;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiRoamController());
        roamMapper = mock(RoamMapper.class);
        flags = mock(NpcFeatureFlags.class);
        contentService = mock(RoamAiContentService.class);
        ReflectionTestUtils.setField(controller, "roamMapper", roamMapper);
        ReflectionTestUtils.setField(controller, "roamBadgeDeliveryService", mock(com.chengyinhub.business.service.impl.RoamBadgeDeliveryService.class));
        com.chengyinhub.business.service.impl.RoamSessionWriteService writer = new com.chengyinhub.business.service.impl.RoamSessionWriteService();
        ReflectionTestUtils.setField(writer, "roamMapper", roamMapper);
        ReflectionTestUtils.setField(controller, "roamSessionWriteService", writer);
        when(roamMapper.selectSessionById(9L)).thenReturn(openSession(9L));
        ReflectionTestUtils.setField(controller, "npcFeatureFlags", flags);
        ReflectionTestUtils.setField(controller, "roamAiContentService", contentService);
        ReflectionTestUtils.setField(controller, "growthService", mock(IGrowthService.class));
        ReflectionTestUtils.setField(controller, "playerBadgeMapper", mock(PlayerBadgeMapper.class));
        ReflectionTestUtils.setField(controller, "growthBadgeMapper", mock(GrowthBadgeMapper.class));
        ReflectionTestUtils.setField(controller, "ruleEvaluator", new RuleEvaluator());
        doReturn(MEMBER).when(controller).getAppUserId();
    }

    @Test
    @SuppressWarnings("unchecked")
    void verifiedPoiDoesNotLeakDescriptionWhenFlagIsOff() {
        RoamPoi poi = verifiedLandmark();
        when(roamMapper.selectOwnedSessionForUpdate(9L, MEMBER)).thenReturn(openSession(9L));
        when(roamMapper.selectPlayerPoisByIds(any())).thenReturn(Collections.singletonList(poi));
        when(roamMapper.insertPoiFoundIgnore(any(), any(), any(), any())).thenReturn(1);
        when(flags.eventOn()).thenReturn(false);

        AjaxResult result = controller.discoverPoi("9", 5L, 31.2300, 121.4700);
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);

        assertFalse(data.containsKey("meaning"));
        verify(contentService, never()).approvedPoiMeaning(any());
        assertEquals("未审核原始描述", poi.getDescription(), "测试数据刻意保留原描述，接口不能把它当意义输出");
    }

    @Test
    @SuppressWarnings("unchecked")
    void routeStoryIsReturnedOnlyAfterSuccessfulServerSettlementAndFlagOn() {
        RoamSession session = openSession(9L);
        when(roamMapper.selectOwnedSessionForUpdate(9L, MEMBER)).thenReturn(session);
        when(roamMapper.selectFoundPoisBySession(9L)).thenReturn(Collections.emptyList());
        when(roamMapper.countTilesBySession(9L)).thenReturn(3);
        when(roamMapper.countTilesTodayByMember(MEMBER)).thenReturn(0);
        when(roamMapper.countTilesEverByMember(MEMBER)).thenReturn(3);
        when(roamMapper.countDistinctShopsBySession(9L)).thenReturn(0);
        when(roamMapper.updateSessionFinish(any(RoamSession.class))).thenReturn(1);
        when(roamMapper.saveFinishResult(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
        when(flags.eventOn()).thenReturn(true);
        when(contentService.approvedRouteStory(any(RoamSession.class))).thenReturn("本次点亮 3 格。");

        AjaxResult result = controller.finish("9", "", 500);
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);

        assertEquals("本次点亮 3 格。", data.get("routeStory"));
        verify(contentService).approvedRouteStory(any(RoamSession.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void optionalPoiMeaningFailureDoesNotTurnVerifiedDiscoveryIntoFailure() {
        RoamPoi poi = verifiedLandmark();
        when(roamMapper.selectOwnedSessionForUpdate(9L, MEMBER)).thenReturn(openSession(9L));
        when(roamMapper.selectPlayerPoisByIds(any())).thenReturn(Collections.singletonList(poi));
        when(roamMapper.insertPoiFoundIgnore(any(), any(), any(), any())).thenReturn(1);
        when(flags.eventOn()).thenReturn(true);
        when(contentService.approvedPoiMeaning(any())).thenThrow(new RuntimeException("content db unavailable"));

        AjaxResult result = controller.discoverPoi("9", 5L, 31.2300, 121.4700);
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertTrue((Boolean) data.get("discovered"));
        assertFalse(data.containsKey("meaning"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void firstMerchantPoiDiscoveryRollsTheRoamSceneAndCreditsXpDrop() {
        RoamPoi poi = verifiedMerchantPoi();
        IRewardDropService dropService = mock(IRewardDropService.class);
        ISmsCouponHistoryService couponHistoryService = mock(ISmsCouponHistoryService.class);
        IGrowthService growthService = mock(IGrowthService.class);
        ReflectionTestUtils.setField(controller, "rewardDropService", dropService);
        ReflectionTestUtils.setField(controller, "smsCouponHistoryService", couponHistoryService);
        ReflectionTestUtils.setField(controller, "growthService", growthService);
        when(roamMapper.selectOwnedSessionForUpdate(9L, MEMBER)).thenReturn(openSession(9L));
        when(roamMapper.selectPlayerPoisByIds(any())).thenReturn(Collections.singletonList(poi));
        when(roamMapper.insertPoiFoundIgnore(any(), any(), any(), any())).thenReturn(1);
        Map<String, Object> drop = new LinkedHashMap<>();
        drop.put("dropType", 1);
        drop.put("extraXp", 20L);
        drop.put("dropLogId", 7L);
        drop.put("sceneKey", IRewardDropService.SCENE_ROAM_MERCHANT_POI);
        when(dropService.rollDrop(eq(MEMBER), eq(0L), eq(5L), eq(9L),
                eq(IRewardDropService.SCENE_ROAM_MERCHANT_POI), eq(20L))).thenReturn(drop);

        com.chengyinhub.business.mapper.RoamRewardDeliveryMapper deliveryMapper = mock(com.chengyinhub.business.mapper.RoamRewardDeliveryMapper.class);
        Map<String, Object> original = new LinkedHashMap<>(); original.put("dropType", 1);
        when(deliveryMapper.selectOriginalRewardDrop(7L, MEMBER)).thenReturn(original);
        Map<String, Object> intent = new LinkedHashMap<>(); intent.put("memberId", MEMBER); intent.put("rewardKind", "XP");
        intent.put("extraXp", 20); intent.put("state", "DUE");
        when(deliveryMapper.selectIntentForUpdate(7L)).thenReturn(intent);
        when(deliveryMapper.completeIfDue(7L, MEMBER, null, "DELIVERED", null)).thenReturn(1);
        org.springframework.transaction.PlatformTransactionManager tx = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        ReflectionTestUtils.setField(controller, "roamRewardDeliveryService",
                new com.chengyinhub.business.service.impl.RoamRewardDeliveryService(deliveryMapper, couponHistoryService, tx, dropService, growthService));
        AjaxResult result = controller.discoverPoi("9", 5L, 31.2300, 121.4700);
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);

        assertEquals(drop, data.get("drop"));
        verify(dropService).rollDrop(eq(MEMBER), eq(0L), eq(5L), eq(9L),
                eq(IRewardDropService.SCENE_ROAM_MERCHANT_POI), eq(20L));
        verify(growthService).addExp(MEMBER, 20, "掉落·漫游探索值翻倍");
    }

    @Test
    @SuppressWarnings("unchecked")
    void optionalRouteStoryFailureDoesNotTurnSettlementIntoFailure() {
        RoamSession session = openSession(9L);
        when(roamMapper.selectOwnedSessionForUpdate(9L, MEMBER)).thenReturn(session);
        when(roamMapper.selectFoundPoisBySession(9L)).thenReturn(Collections.emptyList());
        when(roamMapper.countTilesBySession(9L)).thenReturn(3);
        when(roamMapper.countTilesTodayByMember(MEMBER)).thenReturn(0);
        when(roamMapper.countTilesEverByMember(MEMBER)).thenReturn(3);
        when(roamMapper.countDistinctShopsBySession(9L)).thenReturn(0);
        when(roamMapper.updateSessionFinish(any(RoamSession.class))).thenReturn(1);
        when(roamMapper.saveFinishResult(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
        when(flags.eventOn()).thenReturn(true);
        when(contentService.approvedRouteStory(any(RoamSession.class))).thenThrow(new RuntimeException("content db unavailable"));

        AjaxResult result = controller.finish("9", "", 500);
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(3, data.get("newTiles"));
        assertFalse(data.containsKey("routeStory"));
    }

    private RoamSession openSession(Long id) {
        RoamSession session = new RoamSession();
        session.setId(id);
        session.setMemberId(MEMBER);
        session.setStatus(0);
        return session;
    }

    private RoamPoi verifiedLandmark() {
        RoamPoi poi = new RoamPoi();
        poi.setId(5L);
        poi.setType(1);
        poi.setLat(BigDecimal.valueOf(31.2300));
        poi.setLng(BigDecimal.valueOf(121.4700));
        poi.setDescription("未审核原始描述");
        return poi;
    }

    private RoamPoi verifiedMerchantPoi() {
        RoamPoi poi = verifiedLandmark();
        poi.setType(2);
        poi.setXp(20);
        return poi;
    }
}
