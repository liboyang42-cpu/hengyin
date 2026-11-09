package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IPlayTeamService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 第十一轮 g2,j4 F2:/api/team/info 的业务拒绝带 errorCode,页面据此写理由与返回;其他失败不带,仍按加载失败留白。 */
class ApiPlayTeamInfoRefusalTest {

    private ApiPlayTeamController controller(IPlayTeamService service) {
        ApiPlayTeamController controller = new ApiPlayTeamController();
        ReflectionTestUtils.setField(controller, "playTeamService", service);
        return controller;
    }

    @Test
    void wrongInviteCodeIsATaggedRefusal() {
        IPlayTeamService service = mock(IPlayTeamService.class);
        when(service.info(anyLong(), any(), any())).thenThrow(new IllegalArgumentException("邀请已失效"));

        AjaxResult result = controller(service).info(Map.of("inviteCode", "WRONGcode000000000000a"));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("邀请已失效", result.get(AjaxResult.MSG_TAG));
        assertEquals("TEAM_INFO_REFUSED", result.get("errorCode"));
    }

    @Test
    void otherFailuresAreNotTaggedAsRefusals() {
        IPlayTeamService service = mock(IPlayTeamService.class);
        when(service.info(anyLong(), any(), any())).thenThrow(new IllegalStateException("队伍状态已变化，请刷新后重试"));

        AjaxResult result = controller(service).info(Map.of("teamId", 7));

        assertNull(result.get("errorCode"));
    }
}
