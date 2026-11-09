package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsPresetRoute;
import com.chengyinhub.business.domain.CmsPresetRouteNode;
import com.chengyinhub.business.domain.vo.CityNodeVO;
import com.chengyinhub.business.mapper.MemberCollectibleMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.IPresetService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 第九轮 m4:分享来的官方路线与据点给游客读。游客只拿公开字段,不读不写本人状态;会员口径不变。
 */
class GuestSharedPlaceReadTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static CityNodeVO node() {
        CityNodeVO vo = new CityNodeVO();
        vo.setPoiId(21L);
        vo.setName("街角咖啡");
        vo.setStatus(1);
        vo.setLat(BigDecimal.valueOf(31.2));
        vo.setLng(BigDecimal.valueOf(121.5));
        vo.setMerchantId(90L);
        vo.setMerchantName("营业中");
        vo.setMerchantAddress("测试路 1 号");
        vo.setMerchantPhone("13800000000");
        vo.setCouponId(66L);
        vo.setQuestionName("门口几级台阶?");
        vo.setQuestionA("3");
        vo.setQuestionB("4");
        vo.setQuestionC("5");
        vo.setQuestionD("6");
        vo.setAverageRating(4.5);
        vo.setRatingCount(2);
        return vo;
    }

    @Test
    void guestNodeDetailCarriesPublicFieldsOnlyAndTouchesNoPersonalState() throws Exception {
        RoamMapper mapper = mock(RoamMapper.class);
        MemberCollectibleMapper collectibles = mock(MemberCollectibleMapper.class);
        ApiCityNodeController guest = new ApiCityNodeController() {
            @Override public Long getAppUserId() { throw new IllegalStateException("未登录"); }
        };
        ReflectionTestUtils.setField(guest, "roamMapper", mapper);
        ReflectionTestUtils.setField(guest, "collectibleMapper", collectibles);
        when(mapper.selectPlayerMerchantNodeDetail(21L, 0L)).thenReturn(node());

        AjaxResult res = guest.detail(21L);
        assertEquals(200, res.get(AjaxResult.CODE_TAG));
        String body = JSON.writeValueAsString(res.get(AjaxResult.DATA_TAG));
        Map<?, ?> data = JSON.readValue(body, Map.class);
        assertEquals("街角咖啡", data.get("name"));
        assertEquals("测试路 1 号", data.get("merchantAddress"));
        assertEquals(4.5, data.get("averageRating"));
        for (String hidden : new String[]{"merchantPhone", "couponId", "questionName", "questionA", "questionB",
                "questionC", "questionD", "completed", "favorited"}) {
            assertNull(data.get(hidden), hidden + " 不得下发给游客");
        }
        assertEquals(false, data.get("needRedeem"));
        assertFalse(body.contains("13800000000"));
        verify(mapper, never()).selectPendingCityNodeCouponId(anyLong(), anyLong());
        verify(mapper, never()).selectRewardedPoiIdsByMember(anyLong(), anyList());
        verifyNoInteractions(collectibles);
    }

    @Test
    void guestNodeDetailForOfflineNodeIsGone() {
        RoamMapper mapper = mock(RoamMapper.class);
        ApiCityNodeController guest = new ApiCityNodeController() {
            @Override public Long getAppUserId() { throw new IllegalStateException("未登录"); }
        };
        ReflectionTestUtils.setField(guest, "roamMapper", mapper);
        when(mapper.selectPlayerMerchantNodeDetail(22L, 0L)).thenReturn(null);
        assertEquals(410, guest.detail(22L).get(AjaxResult.CODE_TAG));
    }

    @Test
    void memberNodeDetailKeepsQuizAndPersonalState() {
        RoamMapper mapper = mock(RoamMapper.class);
        MemberCollectibleMapper collectibles = mock(MemberCollectibleMapper.class);
        ApiCityNodeController member = new ApiCityNodeController() {
            @Override public Long getAppUserId() { return 7001L; }
        };
        ReflectionTestUtils.setField(member, "roamMapper", mapper);
        ReflectionTestUtils.setField(member, "collectibleMapper", collectibles);
        when(mapper.selectPlayerMerchantNodeDetail(21L, 7001L)).thenReturn(node());
        when(mapper.selectPendingCityNodeCouponId(21L, 7001L)).thenReturn(66L);
        when(mapper.selectRewardedPoiIdsByMember(anyLong(), anyList())).thenReturn(Collections.emptyList());
        CityNodeVO vo = (CityNodeVO) member.detail(21L).get(AjaxResult.DATA_TAG);
        assertEquals("门口几级台阶?", vo.getQuestionName());
        assertEquals(66L, vo.getCouponId());
        assertEquals(true, vo.getNeedRedeem());
        assertEquals(false, vo.getCompleted());
        assertNull(vo.getMerchantPhone(), "电话对会员也不下发(既有口径)");
    }

    private static CmsPresetRoute route() {
        CmsPresetRoute r = new CmsPresetRoute();
        r.setId(5L);
        r.setCity("杭州");
        r.setName("西湖一圈");
        r.setStatus("1");
        r.setDelFlag(0);
        r.setCreateBy("admin-ops");
        r.setUpdateBy("admin-ops");
        r.setRemark("内部备注:下周换图");
        r.setCreateTime(new Date());
        CmsPresetRouteNode n = new CmsPresetRouteNode();
        n.setId(51L);
        n.setRouteId(5L);
        n.setName("断桥");
        n.setAddress("北山街");
        n.setLatitude("30.26");
        n.setLongitude("120.15");
        n.setDelFlag(0);
        n.setCreateBy("admin-ops");
        n.setRemark("内部备注");
        r.setNodes(Collections.singletonList(n));
        return r;
    }

    @Test
    void guestRouteInfoAndListCarryNoAuditFields() throws Exception {
        IPresetService service = mock(IPresetService.class);
        ApiPresetController guest = new ApiPresetController() {
            @Override public Long getAppUserId() { throw new IllegalStateException("未登录"); }
        };
        ReflectionTestUtils.setField(guest, "presetService", service);
        when(service.getRouteWithNodes(5L)).thenReturn(route());
        when(service.listRoutes("杭州")).thenReturn(Collections.singletonList(route()));

        String info = JSON.writeValueAsString(guest.routeInfo(5L).get(AjaxResult.DATA_TAG));
        String list = JSON.writeValueAsString(guest.routeList("杭州").get(AjaxResult.DATA_TAG));
        for (String body : new String[]{info, list}) {
            assertTrue(body.contains("西湖一圈"));
            assertFalse(body.contains("admin-ops"), body);
            assertFalse(body.contains("内部备注"), body);
            assertFalse(body.contains("\"createTime\":1"), body);
        }
        Map<?, ?> data = JSON.readValue(info, Map.class);
        assertNull(data.get("status"));
        assertNull(data.get("delFlag"));
        Map<?, ?> stop = (Map<?, ?>) ((List<?>) data.get("nodes")).get(0);
        assertEquals("断桥", stop.get("name"));
        assertEquals("北山街", stop.get("address"));
        assertEquals("30.26", stop.get("latitude"));
        assertNull(stop.get("createBy"));
        assertNull(stop.get("remark"));
    }

    @Test
    void guestRouteInfoForOfflineRouteIsAnError() {
        IPresetService service = mock(IPresetService.class);
        ApiPresetController guest = new ApiPresetController() {
            @Override public Long getAppUserId() { throw new IllegalStateException("未登录"); }
        };
        ReflectionTestUtils.setField(guest, "presetService", service);
        when(service.getRouteWithNodes(6L)).thenReturn(null);
        assertEquals(500, guest.routeInfo(6L).get(AjaxResult.CODE_TAG));
    }

    @Test
    void memberRouteInfoIsUnchanged() {
        IPresetService service = mock(IPresetService.class);
        ApiPresetController member = new ApiPresetController() {
            @Override public Long getAppUserId() { return 7001L; }
        };
        ReflectionTestUtils.setField(member, "presetService", service);
        when(service.getRouteWithNodes(5L)).thenReturn(route());
        CmsPresetRoute r = (CmsPresetRoute) member.routeInfo(5L).get(AjaxResult.DATA_TAG);
        assertNotNull(r.getCreateBy());
    }
}
