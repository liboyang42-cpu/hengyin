package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/** F6 新接口:AjaxResult + errorCode;会员 id 只取登录态,不读客户端传值。 */
class ApiPlayRpgP1ControllerTest {
    private static final long MEMBER = 7L;

    @Test
    void bargainAndMomentumMapRefusalsToErrorCodes() {
        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        ApiPlayCheckController controller = spy(new ApiPlayCheckController());
        ReflectionTestUtils.setField(controller, "topicRouteRuntimeService", runtime);
        doReturn(MEMBER).when(controller).getAppUserId();
        PlayCheckReceipt receipt = new PlayCheckReceipt();
        when(runtime.bargain(eq(MEMBER), eq(10L), eq(0L), eq(1L), eq("c1"))).thenReturn(receipt);
        AjaxResult ok = controller.bargain("10", 0L, "1", "c1");
        assertEquals(200, ok.get(AjaxResult.CODE_TAG));
        when(runtime.spendMomentum(eq(MEMBER), anyLong(), anyLong(), anyLong(), anyString()))
                .thenThrow(new ServiceException("动量不足", 409).setDetailMessage("RPG_MOMENTUM_INSUFFICIENT"));
        AjaxResult refused = controller.momentum("10", 0L, "1", "c1");
        assertEquals(409, refused.get(AjaxResult.CODE_TAG));
        assertEquals("RPG_MOMENTUM_INSUFFICIENT", refused.get("errorCode"));
        when(runtime.bargain(eq(MEMBER), eq(10L), eq(0L), eq(2L), eq("c1"))).thenThrow(new IllegalStateException("该检定已结算"));
        assertEquals("CHECK_REJECTED", controller.bargain("10", 0L, "2", "c1").get("errorCode"));
        doThrow(new RuntimeException("no token")).when(controller).getAppUserId();
        assertEquals("CHECK_LOGIN_REQUIRED", controller.bargain("10", 0L, "1", "c1").get("errorCode"));
    }

    @Test
    void statusIsReadOnlyAndCoded() {
        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        ApiPlayRpgController controller = spy(new ApiPlayRpgController());
        ReflectionTestUtils.setField(controller, "topicRouteRuntimeService", runtime);
        doReturn(MEMBER).when(controller).getAppUserId();
        when(runtime.rpgStatus(MEMBER, 10L, null)).thenReturn(Collections.<String, Object>singletonMap("running", Boolean.FALSE));
        assertEquals(200, controller.status("10", null).get(AjaxResult.CODE_TAG));
        when(runtime.rpgStatus(MEMBER, 0L, null)).thenThrow(new IllegalArgumentException("参数有误"));
        assertEquals("RPG_BAD_REQUEST", controller.status("x", null).get("errorCode"));
        doThrow(new RuntimeException("no token")).when(controller).getAppUserId();
        assertEquals("RPG_LOGIN_REQUIRED", controller.status("10", null).get("errorCode"));
    }
}
