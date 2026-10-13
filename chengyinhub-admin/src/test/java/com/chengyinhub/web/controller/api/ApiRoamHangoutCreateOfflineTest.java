package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IRoamHangoutService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 2026-09-15 裁决:漫游搭子局(A)下架,地图只展示票务组队(B)。老客户端调开局/加入/举报
 * 必须拿到同一份明确的下线终态;存量局的 detail/leave/close 仍保留(存量读取不崩)。 */
class ApiRoamHangoutCreateOfflineTest {

    private static final String OFFLINE_MSG = "附近的局已下线，可在地图上查看附近的队伍";

    private ApiRoamHangoutController controller(IRoamHangoutService service) {
        ApiRoamHangoutController controller = new ApiRoamHangoutController();
        ReflectionTestUtils.setField(controller, "roamHangoutService", service);
        return controller;
    }

    @Test
    void createReturnsExplicitOfflineErrorWithoutTouchingService() {
        IRoamHangoutService service = mock(IRoamHangoutService.class);
        ApiRoamHangoutController controller = controller(service);

        AjaxResult result = controller.create("打 UNO", null, null,
                new BigDecimal("31.23"), new BigDecimal("121.47"), "人民广场", null);

        assertEquals(410, result.get(AjaxResult.CODE_TAG));
        assertEquals("HANGOUT_OFFLINE", result.get("errorCode"));
        assertEquals(OFFLINE_MSG, result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(service);
    }

    @Test
    void joinAndReportShareTheSameOfflineTerminalStateWithoutTouchingService() {
        IRoamHangoutService service = mock(IRoamHangoutService.class);
        ApiRoamHangoutController controller = controller(service);

        AjaxResult join = controller.join(5L);
        assertEquals(410, join.get(AjaxResult.CODE_TAG));
        assertEquals("HANGOUT_OFFLINE", join.get("errorCode"));
        assertEquals(OFFLINE_MSG, join.get(AjaxResult.MSG_TAG));

        AjaxResult report = controller.report(5L, "广告");
        assertEquals(410, report.get(AjaxResult.CODE_TAG));
        assertEquals("HANGOUT_OFFLINE", report.get("errorCode"));
        assertEquals(OFFLINE_MSG, report.get(AjaxResult.MSG_TAG));

        verifyNoInteractions(service);
    }
}
