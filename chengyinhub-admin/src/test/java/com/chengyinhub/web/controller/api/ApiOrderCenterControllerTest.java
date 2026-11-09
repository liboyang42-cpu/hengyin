package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.service.order.OrderCenterQueryApplication;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiOrderCenterControllerTest {
    @Test void controllerUsesServerPrincipalAndExactFormNumber() {
        OrderCenterQueryApplication app = mock(OrderCenterQueryApplication.class);
        ApiOrderCenterController controller = new ApiOrderCenterController() {
            @Override public Long getAppUserId() { return 7L; }
        };
        ReflectionTestUtils.setField(controller, "orderCenterQueryApplication", app);
        AjaxResult expected = AjaxResult.success(); when(app.detail(7L, "ABC|*_")).thenReturn(expected);
        assertSame(expected, controller.detail("ABC|*_")); verify(app).detail(7L, "ABC|*_"); verifyNoMoreInteractions(app);
    }

    @Test void failedAuthenticationNeverReachesApplication() {
        OrderCenterQueryApplication app = mock(OrderCenterQueryApplication.class);
        ApiOrderCenterController controller = new ApiOrderCenterController() {
            @Override public Long getAppUserId() { throw new IllegalStateException("anonymous"); }
        };
        ReflectionTestUtils.setField(controller, "orderCenterQueryApplication", app);
        assertEquals(401, controller.detail("REG123").get("code")); verifyNoInteractions(app);
    }

    @Test void endpointAcceptsOnlyOptionalFormOrderNumber() throws Exception {
        var method = ApiOrderCenterController.class.getMethod("detail", String.class);
        assertArrayEquals(new String[]{"/detail"}, method.getAnnotation(PostMapping.class).value());
        assertFalse(method.getParameters()[0].getAnnotation(RequestParam.class).required());
    }

    @Test void nullAndNonpositivePrincipalsCannotOpenAnApplicationTransaction() {
        OrderCenterQueryApplication app = mock(OrderCenterQueryApplication.class);
        for (Long principal : new Long[]{null, 0L, -1L}) {
            ApiOrderCenterController controller = new ApiOrderCenterController() {
                @Override public Long getAppUserId() { return principal; }
            };
            ReflectionTestUtils.setField(controller, "orderCenterQueryApplication", app);
            assertEquals(401, controller.detail("REG123").get("code"));
        }
        verifyNoInteractions(app);
    }
}
