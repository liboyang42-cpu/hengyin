package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.impl.RoamSessionWriteService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R6 /poi/discover 瞬移速度校验:上一次 POI 发现时间来自 resultType=map 查询,
 * MySQL Connector/J 8 回 LocalDateTime。旧代码用 instanceof Date 守卫,真库下校验静默失效。
 */
class ApiRoamPoiDiscoverTeleportTest {
    private static final long PLAYER = 9L;
    private static final long SID = 77L;
    private static final long POI_ID = 501L;
    private static final double POI_LAT = 31.2304;
    private static final double POI_LNG = 121.4737;
    /** 约 11 km 外(纬度 +0.1°) */
    private static final BigDecimal FAR_LAT = new BigDecimal("31.3304");

    private static class StubController extends ApiRoamController {
        @Override
        Long uid() { return PLAYER; }
    }

    private RoamMapper roamMapper;
    private RoamSessionWriteService writeService;
    private StubController controller;

    @BeforeEach
    void setUp() {
        roamMapper = mock(RoamMapper.class);
        writeService = mock(RoamSessionWriteService.class);
        controller = new StubController();
        ReflectionTestUtils.setField(controller, "roamMapper", roamMapper);
        ReflectionTestUtils.setField(controller, "roamSessionWriteService", writeService);
        ReflectionTestUtils.setField(controller, "npcFeatureFlags", mock(NpcFeatureFlags.class));
        ReflectionTestUtils.setField(controller, "locationConsent",
                mock(com.chengyinhub.business.service.impl.RoamLocationConsentService.class));

        RoamSession session = new RoamSession();
        session.setId(SID);
        session.setMemberId(PLAYER);
        session.setStatus(0);
        when(roamMapper.selectSessionById(SID)).thenReturn(session);
        RoamPoi poi = new RoamPoi();
        poi.setId(POI_ID);
        poi.setName("测试据点");
        poi.setType(1);
        poi.setLat(BigDecimal.valueOf(POI_LAT));
        poi.setLng(BigDecimal.valueOf(POI_LNG));
        when(roamMapper.selectPlayerPoisByIds(anyList())).thenReturn(Collections.singletonList(poi));
    }

    private static Map<String, Object> lastFound(Object foundAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("foundAt", foundAt);
        row.put("lat", FAR_LAT);
        row.put("lng", BigDecimal.valueOf(POI_LNG));
        return row;
    }

    private AjaxResult discover() {
        return controller.discoverPoi(String.valueOf(SID), POI_ID, POI_LAT, POI_LNG);
    }

    @Test
    void teleportFromDateAnchorIsRejected() {
        when(roamMapper.selectLastPoiFoundLoc(PLAYER)).thenReturn(lastFound(new Date(System.currentTimeMillis() - 10_000L)));

        AjaxResult result = discover();

        assertEquals(500, result.get("code"));
        assertEquals("移动过快，疑似伪造定位", result.get("msg"));
        verify(writeService, never()).recordPoiFound(anyLong(), anyLong(), anyLong());
    }

    @Test
    void teleportFromLocalDateTimeAnchorIsRejected() {
        // 真库形态:Connector/J 8 把 Map 结果里的 DATETIME 返回为 LocalDateTime
        when(roamMapper.selectLastPoiFoundLoc(PLAYER)).thenReturn(lastFound(LocalDateTime.now().minusSeconds(10)));

        AjaxResult result = discover();

        assertEquals(500, result.get("code"));
        assertEquals("移动过快，疑似伪造定位", result.get("msg"));
        verify(writeService, never()).recordPoiFound(anyLong(), anyLong(), anyLong());
    }

    @Test
    void normalPaceFromLocalDateTimeAnchorIsAccepted() {
        when(roamMapper.selectLastPoiFoundLoc(PLAYER)).thenReturn(lastFound(LocalDateTime.now().minusHours(1)));
        when(writeService.recordPoiFound(PLAYER, SID, POI_ID)).thenReturn(1);

        AjaxResult result = discover();

        assertEquals(200, result.get("code"));
        verify(writeService).recordPoiFound(PLAYER, SID, POI_ID);
    }
}
