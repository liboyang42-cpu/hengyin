package com.chengyinhub.web.controller.api.appgaps;

import com.chengyinhub.business.appgaps.WorkshopOwnedRead;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ApiAppGapsWorkshopControllerTest {
    @Test void commerceFamilyIsAlwaysRefusedWithoutTouchingPayment() throws Exception {
        assertEquals(503, new ApiAppGapsWorkshopController(new WorkshopOwnedRead()).commerceOff().get("code"));
        RequestMapping m = ApiAppGapsWorkshopController.class.getMethod("commerceOff").getAnnotation(RequestMapping.class);
        assertTrue(Arrays.asList(m.value()).contains("/api/mod-commerce/**"));
        assertEquals(0, m.method().length, "所有 HTTP 方法都拒绝");
        for (var f : ApiAppGapsWorkshopController.class.getDeclaredFields())
            assertFalse(f.getType().getName().toLowerCase().contains("pay"), "控制器不得持有支付依赖: " + f);
    }
}
