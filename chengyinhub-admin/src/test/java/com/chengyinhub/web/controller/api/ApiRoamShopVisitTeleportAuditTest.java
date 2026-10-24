package com.chengyinhub.web.controller.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.mapper.CmsRegistrationMerchantMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * owner 2026-10-23 D7(U-112):探店入账的瞬移速度校验只记不拦。
 * 隐含速度 = 上一个已入账点(POI 发现 / 探店,取较新者)到本店的距离 ÷ 时间差;> 50 m/s 写结构化 warn,照常入账。
 */
class ApiRoamShopVisitTeleportAuditTest {
    private static final long PLAYER = 9L;
    private static final long SID = 77L;
    private static final long POI_ID = 501L;
    private static final double SHOP_LAT = 31.2304;
    private static final double SHOP_LNG = 121.4737;
    /** 约 11 km 外(纬度 +0.1°) */
    private static final String FAR_LAT = "31.3304";

    private static class StubController extends ApiRoamController {
        @Override
        Long uid() { return PLAYER; }
    }

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger auditLogger;
    private RoamMapper roamMapper;
    private StubController controller;

    @BeforeEach
    void setUp() {
        auditLogger = (Logger) LoggerFactory.getLogger(ApiRoamController.class.getName() + ".teleportAudit");
        auditLogger.setLevel(Level.INFO);
        logs.start();
        auditLogger.addAppender(logs);

        roamMapper = mock(RoamMapper.class);
        controller = new StubController();
        ReflectionTestUtils.setField(controller, "roamMapper", roamMapper);
        ReflectionTestUtils.setField(controller, "locationConsent",
                mock(com.chengyinhub.business.service.impl.RoamLocationConsentService.class));
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantMapper", mock(CmsRegistrationMerchantMapper.class));

        RoamSession session = new RoamSession();
        session.setId(SID);
        session.setMemberId(PLAYER);
        session.setStatus(0);
        when(roamMapper.selectOwnedSessionForUpdate(SID, PLAYER)).thenReturn(session);
        RoamPoi poi = new RoamPoi();
        poi.setId(POI_ID);
        poi.setName("测试据点");
        poi.setType(1);
        poi.setLat(BigDecimal.valueOf(SHOP_LAT));
        poi.setLng(BigDecimal.valueOf(SHOP_LNG));
        when(roamMapper.selectPlayerPoisByIds(anyList())).thenReturn(Collections.singletonList(poi));
        when(roamMapper.insertShopVisitIgnore(anyLong(), anyLong(), anyInt(), anyLong(), any(), any())).thenReturn(1);
        when(roamMapper.countDistinctShopsBySession(SID)).thenReturn(1);
    }

    @AfterEach
    void detach() {
        auditLogger.detachAppender(logs);
    }

    private static Map<String, Object> point(String timeKey, Object at, Object lat, Object lng) {
        Map<String, Object> row = new HashMap<>();
        row.put(timeKey, at);
        row.put("lat", lat);
        row.put("lng", lng);
        return row;
    }

    private AjaxResult visit() {
        return controller.shopVisit(String.valueOf(SID), 1, POI_ID, SHOP_LAT, SHOP_LNG);
    }

    private List<String> warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
    }

    private void assertAccepted(AjaxResult result) {
        assertEquals(200, result.get("code"), "D7 只记不拦:任何速度都照常入账");
        verify(roamMapper).insertShopVisitIgnore(anyLong(), anyLong(), anyInt(), anyLong(), any(), any());
    }

    @Test
    void teleportFromPreviousShopVisitIsLoggedButStillAccepted() {
        // 上一家店(报名商家,坐标是 VARCHAR)10 秒前、约 11 km 外 ⇒ ~1100 m/s
        when(roamMapper.selectLastShopVisitLoc(PLAYER)).thenReturn(point("visitedAt",
                new Date(System.currentTimeMillis() - 10_000L), FAR_LAT, String.valueOf(SHOP_LNG)));

        AjaxResult result = visit();

        assertAccepted(result);
        List<String> warns = warnings();
        assertEquals(1, warns.size(), warns.toString());
        String line = warns.get(0);
        assertTrue(line.startsWith("event=ROAM_SHOP_VISIT_TELEPORT_SUSPECT action=ACCEPTED member=9 session=77"), line);
        assertTrue(line.contains("sourceType=1 sourceId=501 prevKind=SHOP_VISIT"), line);
        assertTrue(line.contains("thresholdMps=50.0"), line);
    }

    @Test
    void normalPaceIsNotLogged() {
        // 1 小时前、约 11 km 外 ⇒ ~3 m/s
        when(roamMapper.selectLastPoiFoundLoc(PLAYER)).thenReturn(point("foundAt",
                new Date(System.currentTimeMillis() - 3_600_000L), new BigDecimal(FAR_LAT), BigDecimal.valueOf(SHOP_LNG)));

        assertAccepted(visit());
        assertEquals(Collections.emptyList(), warnings());
    }

    @Test
    void usesTheNewerOfPoiDiscoverAndShopVisitIncludingLocalDateTimeRows() {
        // 较新的是 5 秒前的 POI 发现(驱动回 LocalDateTime),远;较旧的是 1 小时前就在本店的探店 ⇒ 按较新的那个算 → 记
        when(roamMapper.selectLastPoiFoundLoc(PLAYER)).thenReturn(point("foundAt",
                LocalDateTime.now().minusSeconds(5), new BigDecimal(FAR_LAT), BigDecimal.valueOf(SHOP_LNG)));
        when(roamMapper.selectLastShopVisitLoc(PLAYER)).thenReturn(point("visitedAt",
                new Date(System.currentTimeMillis() - 3_600_000L), String.valueOf(SHOP_LAT), String.valueOf(SHOP_LNG)));

        assertAccepted(visit());
        assertEquals(1, warnings().size());
        assertTrue(warnings().get(0).contains("prevKind=POI_DISCOVER"));
    }

    @Test
    void noPreviousPointOrDirtyCoordinatesNeverLogsOrBlocks() {
        when(roamMapper.selectLastShopVisitLoc(PLAYER)).thenReturn(point("visitedAt", new Date(), "", "abc"));

        assertAccepted(visit());
        assertEquals(Collections.emptyList(), warnings());
    }

    @Test
    void auditFailureIsSwallowedAndTheVisitIsStillRecorded() {
        when(roamMapper.selectLastShopVisitLoc(PLAYER)).thenThrow(new IllegalStateException("db down"));

        assertAccepted(visit());
        assertEquals(1, warnings().size());
        assertTrue(warnings().get(0).startsWith("event=ROAM_SHOP_VISIT_TELEPORT_AUDIT_SKIPPED member=9"));
    }
}
