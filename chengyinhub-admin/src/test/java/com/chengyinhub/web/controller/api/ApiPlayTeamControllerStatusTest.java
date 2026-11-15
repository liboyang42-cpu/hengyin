package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IPlayTeamService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证 ApiPlayTeamController.fail 映射业务拒绝为 4xx，同时保留 info 的 TEAM_INFO_REFUSED 契约。 */
class ApiPlayTeamControllerStatusTest {

    private IPlayTeamService playTeamService;
    private TestablePlayTeamController controller;

    @BeforeEach
    void setUp() {
        playTeamService = mock(IPlayTeamService.class);
        controller = new TestablePlayTeamController();
        ReflectionTestUtils.setField(controller, "playTeamService", playTeamService);
    }

    @Test
    void teamRejectionMapsToSemanticStatusWithErrorCode() {
        doThrow(new IPlayTeamService.TeamRejection("INVITE_REVOKED", "邀请已失效"))
                .when(playTeamService).inviteQrcode(10L, 9001L);
        Map<String, Object> body = new HashMap<>();
        body.put("teamId", 10L);
        AjaxResult revoked = controller.inviteQrcode(body);
        assertEquals(HttpStatus.NOT_FOUND, revoked.get("code"));
        assertEquals("INVITE_REVOKED", revoked.get("errorCode"));

        doThrow(new IPlayTeamService.TeamRejection("TICKET_REQUIRED", "持有这一场的票才能申请加入"))
                .when(playTeamService).apply(10L, 9001L, "hi");
        Map<String, Object> applyBody = new HashMap<>();
        applyBody.put("teamId", 10L);
        applyBody.put("message", "hi");
        AjaxResult ticket = controller.apply(applyBody);
        assertEquals(HttpStatus.FORBIDDEN, ticket.get("code"));
        assertEquals("TICKET_REQUIRED", ticket.get("errorCode"));
    }

    @Test
    void illegalArgumentAndIllegalStateMapTo4xxWhileInfoRefusalContractStays500() {
        doThrow(new IllegalStateException("只有队长可以操作")).when(playTeamService).disband(10L, 9001L);
        Map<String, Object> body = new HashMap<>();
        body.put("teamId", 10L);
        assertEquals(HttpStatus.FORBIDDEN, controller.disband(body).get("code"));

        doThrow(new IllegalArgumentException("队伍不存在")).when(playTeamService).withdraw(10L, 9001L);
        assertEquals(HttpStatus.NOT_FOUND, controller.withdraw(body).get("code"));

        doThrow(new IllegalStateException("你不在该队伍中")).when(playTeamService).quit(10L, 9001L);
        assertEquals(HttpStatus.CONFLICT, controller.quit(body).get("code"));

        when(playTeamService.info(10L, null, 9001L)).thenThrow(new IllegalArgumentException("邀请已失效"));
        AjaxResult infoRefusal = controller.info(body);
        assertEquals(HttpStatus.ERROR, infoRefusal.get("code"));
        assertEquals("TEAM_INFO_REFUSED", infoRefusal.get("errorCode"));
    }

    private static class TestablePlayTeamController extends ApiPlayTeamController {
        @Override
        public Long getAppUserId() {
            return 9001L;
        }
    }
}
