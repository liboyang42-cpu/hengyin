package com.chengyinhub.web.controller.api.appgaps;

import com.chengyinhub.business.appgaps.WorkshopOwnedRead;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ApiAppGapsWorkshopControllerTest {
    @Test void commerceFamilyIsAlwaysRefusedWithoutTouchingPayment() throws Exception {
        assertEquals(401, new ApiAppGapsWorkshopController(new WorkshopOwnedRead()).commerceOff().get("code"), "未登录先回 401，App 走会话失效");
        var signedIn = new ApiAppGapsWorkshopController(new WorkshopOwnedRead()) { @Override boolean signedIn() { return true; } };
        assertEquals(503, signedIn.commerceOff().get("code"));
        assertEquals("WORKSHOP_COMMERCE_DISABLED", signedIn.commerceOff().get("errorCode"));
        RequestMapping m = ApiAppGapsWorkshopController.class.getMethod("commerceOff").getAnnotation(RequestMapping.class);
        assertTrue(Arrays.asList(m.value()).contains("/api/mod-commerce/**"));
        assertEquals(0, m.method().length, "所有 HTTP 方法都拒绝");
        for (var f : ApiAppGapsWorkshopController.class.getDeclaredFields())
            assertFalse(f.getType().getName().toLowerCase().contains("pay"), "控制器不得持有支付依赖: " + f);
    }
}
