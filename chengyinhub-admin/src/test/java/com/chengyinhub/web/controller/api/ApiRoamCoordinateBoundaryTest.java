package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.mapper.CmsRegistrationMerchantMapper;
import com.chengyinhub.business.mapper.MemberVisitedPoiMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.impl.RoamSessionWriteService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 漫游坐标边界:玩家上报的经纬度必须有限且在范围内,NaN/Infinity/越界不得触发任何写或奖励。
 *
 * <p>旧码只判 null,而 {@code haversineM} 遇到 NaN 返回 NaN,`NaN > 半径` 恒 false
 * ⇒ 伪造定位可白拿 POI 发现 / 探店入账 / 点到店探索值;presence 的 NaN 则会漏到
 * {@code BigDecimal.valueOf(NaN)} 抛 500。这里钉住失败关闭与既有合法路径不缩减。
 */
class ApiRoamCoordinateBoundaryTest
{
    private static final long PLAYER = 9L;
    private static final long SID = 77L;
    private static final long POI_ID = 501L;
    private static final long MERCHANT_ID = 601L;
    private static final double NEAR_LAT = 31.2304;
    private static final double NEAR_LNG = 121.4737;

    private static final double[] BAD_LAT = {
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 90.1d, -90.1d,
    };
    private static final double[] BAD_LNG = {
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 180.1d, -180.1d,
    };

    private static class StubController extends ApiRoamController
    {
        @Override
        Long uid() { return PLAYER; }
    }

    private RoamMapper roamMapper;
    private RoamSessionWriteService writeService;
    private IGrowthService growthService;
    private MemberVisitedPoiMapper visitedPoiMapper;
    private CmsRegistrationMerchantMapper merchantMapper;
    private StubController controller;

    @BeforeEach
    void setUp()
    {
        roamMapper = mock(RoamMapper.class);
        writeService = mock(RoamSessionWriteService.class);
        growthService = mock(IGrowthService.class);
        visitedPoiMapper = mock(MemberVisitedPoiMapper.class);
        merchantMapper = mock(CmsRegistrationMerchantMapper.class);

        controller = new StubController();
        ReflectionTestUtils.setField(controller, "roamMapper", roamMapper);
        ReflectionTestUtils.setField(controller,"locationConsent",mock(com.chengyinhub.business.service.impl.RoamLocationConsentService.class));
        ReflectionTestUtils.setField(controller, "roamSessionWriteService", writeService);
        ReflectionTestUtils.setField(controller, "growthService", growthService);
        ReflectionTestUtils.setField(controller, "memberVisitedPoiMapper", visitedPoiMapper);
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantMapper", merchantMapper);
        NpcFeatureFlags flags = mock(NpcFeatureFlags.class);
        when(flags.eventOn()).thenReturn(false);
        ReflectionTestUtils.setField(controller, "npcFeatureFlags", flags);

        RoamSession session = new RoamSession();
        session.setId(SID);
        session.setMemberId(PLAYER);
        session.setStatus(0);
        when(roamMapper.selectSessionById(SID)).thenReturn(session);
        when(roamMapper.selectOwnedSessionForUpdate(SID, PLAYER)).thenReturn(session);

        RoamPoi poi = new RoamPoi();
        poi.setId(POI_ID);
        poi.setName("测试据点");
        poi.setType(1);
        poi.setXp(20);
        poi.setLat(BigDecimal.valueOf(NEAR_LAT));
        poi.setLng(BigDecimal.valueOf(NEAR_LNG));
        when(roamMapper.selectPlayerPoisByIds(anyList())).thenReturn(Collections.singletonList(poi));
        when(roamMapper.selectLastPoiFoundLoc(PLAYER)).thenReturn(null);
        when(roamMapper.selectMerchantNodesNear(anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), any(), any(), any(), any())).thenReturn(Collections.emptyList());
    }

    // ---------- POI 发现 ----------

    @Test
    void discoverPoiRejectsInvalidCoordinatesWithoutAnyWriteOrReward()
    {
        for (double bad : BAD_LAT) {
            AjaxResult r = controller.discoverPoi(String.valueOf(SID), POI_ID, bad, NEAR_LNG);
            assertNotEquals(200, r.get("code"), "非法纬度不得推进:" + bad);
            assertEquals("定位无效", r.get("msg"));
        }
        for (double bad : BAD_LNG) {
            AjaxResult r = controller.discoverPoi(String.valueOf(SID), POI_ID, NEAR_LAT, bad);
            assertNotEquals(200, r.get("code"), "非法经度不得推进:" + bad);
            assertEquals("定位无效", r.get("msg"));
        }
        verify(writeService, never()).recordPoiFound(anyLong(), anyLong(), anyLong());
        verify(growthService, never()).addExp(anyLong(), anyInt(), any());
    }

    @Test
    void discoverPoiStillRecordsNearAndRejectsFar()
    {
        when(writeService.recordPoiFound(PLAYER, SID, POI_ID)).thenReturn(1);

        AjaxResult near = controller.discoverPoi(String.valueOf(SID), POI_ID, NEAR_LAT, NEAR_LNG);
        assertEquals(200, near.get("code"));
        verify(writeService).recordPoiFound(PLAYER, SID, POI_ID);

        AjaxResult far = controller.discoverPoi(String.valueOf(SID), POI_ID, 31.4d, 121.6d);
        assertNotEquals(200, far.get("code"));
        assertEquals("还没到该地点附近", far.get("msg"));
    }

    // ---------- 探店入账 ----------

    @Test
    void shopVisitRejectsInvalidCoordinatesWithoutRecord()
    {
        for (double bad : BAD_LAT) {
            AjaxResult r = controller.shopVisit(String.valueOf(SID), 1, POI_ID, bad, NEAR_LNG);
            assertNotEquals(200, r.get("code"), "非法纬度不得记入:" + bad);
        }
        for (double bad : BAD_LNG) {
            AjaxResult r = controller.shopVisit(String.valueOf(SID), 1, POI_ID, NEAR_LAT, bad);
            assertNotEquals(200, r.get("code"), "非法经度不得记入:" + bad);
        }
        verify(roamMapper, never()).insertShopVisitIgnore(anyLong(), anyLong(), anyInt(), anyLong(), any(), any());
    }

    @Test
    void shopVisitOutOfRangeMerchantCoordinateIsTreatedAsMissing()
    {
        CmsRegistrationMerchant merchant = new CmsRegistrationMerchant();
        merchant.setId(MERCHANT_ID);
        merchant.setDelFlag(0);
        merchant.setAuditStatus(1L);
        merchant.setAddressName("测试商家");
        merchant.setLatitude("95");          // 越界,parseCoord 必须判无效
        merchant.setLongitude(String.valueOf(NEAR_LNG));
        when(merchantMapper.selectCmsRegistrationMerchantById(MERCHANT_ID)).thenReturn(merchant);

        AjaxResult r = controller.shopVisit(String.valueOf(SID), 2, MERCHANT_ID, NEAR_LAT, NEAR_LNG);

        assertNotEquals(200, r.get("code"));
        assertEquals("商家未设坐标", r.get("msg"));
        verify(roamMapper, never()).insertShopVisitIgnore(anyLong(), anyLong(), anyInt(), anyLong(), any(), any());
    }

    @Test
    void shopVisitStillRecordsNear()
    {
        when(roamMapper.insertShopVisitIgnore(PLAYER, SID, 1, POI_ID, "测试据点", new java.util.Date())).thenReturn(1);
        when(roamMapper.countDistinctShopsBySession(SID)).thenReturn(1);

        AjaxResult r = controller.shopVisit(String.valueOf(SID), 1, POI_ID, NEAR_LAT, NEAR_LNG);

        assertEquals(200, r.get("code"));
        verify(roamMapper).insertShopVisitIgnore(anyLong(), anyLong(), anyInt(), anyLong(), any(), any());
    }

    // ---------- 点到店 ----------

    @SuppressWarnings("unchecked")
    @Test
    void checkinRejectsInvalidCoordinatesWithoutVisitOrXp()
    {
        for (double bad : BAD_LAT) {
            AjaxResult r = controller.checkin("测试点", bad, NEAR_LNG, NEAR_LAT, NEAR_LNG);
            assertNotEquals(200, r.get("code"), "poiLat 非法不得点亮:" + bad);
            r = controller.checkin("测试点", NEAR_LAT, NEAR_LNG, bad, NEAR_LNG);
            assertNotEquals(200, r.get("code"), "curLat 非法不得点亮:" + bad);
        }
        for (double bad : BAD_LNG) {
            AjaxResult r = controller.checkin("测试点", NEAR_LAT, bad, NEAR_LAT, NEAR_LNG);
            assertNotEquals(200, r.get("code"), "poiLng 非法不得点亮:" + bad);
            r = controller.checkin("测试点", NEAR_LAT, NEAR_LNG, NEAR_LAT, bad);
            assertNotEquals(200, r.get("code"), "curLng 非法不得点亮:" + bad);
        }
        verify(visitedPoiMapper, never()).insertVisitedPoiIgnore(anyLong(), any(), any(), any(), any(), any());
        verify(growthService, never()).addExp(anyLong(), anyInt(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void checkinStillLightsUpNearAndRejectsFar()
    {
        when(visitedPoiMapper.insertVisitedPoiIgnore(anyLong(), any(), any(), any(), any(), any())).thenReturn(1);

        AjaxResult near = controller.checkin("测试点", NEAR_LAT, NEAR_LNG, NEAR_LAT, NEAR_LNG);
        assertEquals(200, near.get("code"));
        assertEquals(Boolean.TRUE, ((Map<String, Object>) near.get("data")).get("firstVisit"));
        verify(growthService).addExp(PLAYER, 20, "点亮地点");

        AjaxResult far = controller.checkin("测试点", NEAR_LAT, NEAR_LNG, 31.5d, 121.5d);
        assertEquals(200, far.get("code"));
        assertEquals(Boolean.TRUE, ((Map<String, Object>) far.get("data")).get("tooFar"));
        verify(visitedPoiMapper).insertVisitedPoiIgnore(anyLong(), any(), any(), any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void checkinUsesStableIdentityOnlyWhenSwitchOn()
    {
        com.chengyinhub.business.service.support.VisitedPoiIdentityResolver identity =
                mock(com.chengyinhub.business.service.support.VisitedPoiIdentityResolver.class);
        ReflectionTestUtils.setField(controller, "visitedPoiIdentity", identity);
        when(visitedPoiMapper.insertVisitedPoiIgnore(anyLong(), any(), any(), any(), any(), any())).thenReturn(1);

        // 开关关(默认):旧 geo_key 路径一字不变
        assertEquals(200, controller.checkin("测试点", NEAR_LAT, NEAR_LNG, NEAR_LAT, NEAR_LNG).get("code"));
        verify(visitedPoiMapper).insertVisitedPoiIgnore(org.mockito.ArgumentMatchers.eq(PLAYER),
                org.mockito.ArgumentMatchers.eq("31.230,121.474"), any(), any(), any(), any());
        verify(identity, never()).recordVisit(anyLong(), anyDouble(), anyDouble(), any(), any());

        // 开关开:走稳定身份,已点亮过则不再发探索值
        when(identity.enabled()).thenReturn(true);
        when(identity.recordVisit(anyLong(), anyDouble(), anyDouble(), any(), any())).thenReturn(false);
        AjaxResult again = controller.checkin("测试点", NEAR_LAT, NEAR_LNG, NEAR_LAT, NEAR_LNG);
        assertEquals(Boolean.FALSE, ((Map<String, Object>) again.get("data")).get("firstVisit"));
        verify(identity).recordVisit(org.mockito.ArgumentMatchers.eq(PLAYER), org.mockito.ArgumentMatchers.eq(NEAR_LAT),
                org.mockito.ArgumentMatchers.eq(NEAR_LNG), org.mockito.ArgumentMatchers.eq("测试点"), any());
        verify(growthService).addExp(PLAYER, 20, "点亮地点");
    }

    // ---------- 心跳/查询 ----------

    @Test
    void presenceRejectsNaNAsOutOfRangeInsteadOfThrowing()
    {
        for (double bad : BAD_LAT) {
            AjaxResult r = controller.presence(String.valueOf(SID), bad, NEAR_LNG, 0);
            assertNotEquals(200, r.get("code"), "非法纬度不得上报:" + bad);
            assertEquals("位置越界", r.get("msg"));
        }
        for (double bad : BAD_LNG) {
            AjaxResult r = controller.presence(String.valueOf(SID), NEAR_LAT, bad, 0);
            assertNotEquals(200, r.get("code"), "非法经度不得上报:" + bad);
            assertEquals("位置越界", r.get("msg"));
        }
        verify(roamMapper, never()).updatePresence(anyLong(), anyLong(), any(), any(), any(), anyInt());
    }

    @Test
    void readOnlyCoordinateQueriesRejectInvalidWithoutHittingMapper()
    {
        assertNotEquals(200, controller.pois(Double.NaN, NEAR_LNG, null).get("code"));
        assertNotEquals(200, controller.nearbyExploreDay(Double.NaN, NEAR_LNG).get("code"));
        assertNotEquals(200, controller.nearbyRunners(Double.NaN, NEAR_LNG, null).get("code"));
        verify(roamMapper, never()).selectPoisNear(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any());
        verify(roamMapper, never()).selectNearbyRunners(anyLong(), anyDouble(), anyDouble(), anyInt(), any(), any(), anyInt());
    }

    // ---------- C-13 已发现按账号投影 ----------

    @SuppressWarnings("unchecked")
    @Test
    void poisProjectsFoundForTheCurrentAccount()
    {
        RoamPoi found = new RoamPoi();
        found.setId(POI_ID);
        found.setFound(true);
        when(roamMapper.selectPoisNear(anyDouble(), anyDouble(), anyDouble(), anyDouble(), org.mockito.ArgumentMatchers.eq(PLAYER)))
                .thenReturn(Collections.singletonList(found));

        AjaxResult r = controller.pois(NEAR_LAT, NEAR_LNG, 3000);

        assertEquals(200, r.get("code"));
        assertEquals(Boolean.TRUE, ((java.util.List<RoamPoi>) r.get("data")).get(0).getFound(),
                "found 是服务端按账号投影的事实,必须原样下发 —— 前端靠它把已发现地点保持点亮");
        verify(roamMapper).selectPoisNear(anyDouble(), anyDouble(), anyDouble(), anyDouble(), org.mockito.ArgumentMatchers.eq(PLAYER));
    }
}
