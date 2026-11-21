package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.creatorearnings.CreatorEarningsPolicy;
import com.chengyinhub.business.creatorearnings.CreatorEarningsService;
import com.chengyinhub.web.controller.api.appgaps.ApiAppGapsWorkshopController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class ApiCreatorEarningsControllerTest {

    private final CreatorEarningsService service = mock(CreatorEarningsService.class);

    private ApiCreatorEarningsController as(Long member) {
        return new ApiCreatorEarningsController(service) { @Override Long member() { return member; } };
    }

    @Test
    void everyRouteNeedsALogin() {
        var anonymous = as(null);
        String key = CreatorEarningsPolicy.incomeOrderKey("WKO_x");
        for (var result : List.of(anonymous.mine(), anonymous.ledger(null), anonymous.settlements(100, null),
                anonymous.qualification(), anonymous.income(null), anonymous.incomeDetail(key),
                anonymous.incomeRevisions(key, 1L, null), anonymous.settlement("ms_" + "a".repeat(64))))
            assertEquals(401, result.get("code"));
        verifyNoInteractions(service);
    }

    @Test
    void readsAreScopedToTheSignedInMember() {
        when(service.mine(7L)).thenReturn(Map.of("schema", "creator-earnings-v1"));
        when(service.incomePage(7L, null)).thenReturn(Map.of("schema", "mod-creator-income-page-v1"));
        var me = as(7L);
        assertEquals(200, me.mine().get("code"));
        assertEquals(200, me.income(null).get("code"));
        verify(service).mine(7L);
        verify(service).incomePage(7L, null);
        assertEquals(400, me.income("not-a-key").get("code"));
        assertEquals(400, me.ledger(0L).get("code"));
        assertEquals(400, me.settlements(0, null).get("code"));
    }

    @Test
    void noPayoutRecordOrQualificationIsInvented() {
        var me = as(7L);
        var list = me.settlements(100, null);
        assertEquals(200, list.get("code"));
        assertEquals(List.of(), list.get("data"));
        var qualification = me.qualification();
        assertEquals(200, qualification.get("code"));
        assertFalse(qualification.containsKey("data"), "absent qualification decodes as nil in the app");
        assertEquals(404, me.settlement("ms_" + "b".repeat(64)).get("code"));
    }

    @Test
    void unknownIncomeOrderIsNotFound() {
        when(service.incomeDetail(anyLong(), any())).thenReturn(null);
        when(service.incomeRevisions(anyLong(), any(), anyLong(), any())).thenReturn(null);
        var me = as(7L);
        String key = CreatorEarningsPolicy.incomeOrderKey("WKO_y");
        assertEquals(404, me.incomeDetail(key).get("code"));
        assertEquals(404, me.incomeRevisions(key, 1L, null).get("code"));
        assertEquals(404, me.incomeRevisions(key, null, null).get("code"));
        assertEquals(404, me.incomeDetail("WKO_y").get("code"));
    }

    @Test
    void onlyGetRoutesAndPayoutWritesStayClosed() throws Exception {
        for (Method m : ApiCreatorEarningsController.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(RequestMapping.class)) fail("no catch-all mapping on " + m.getName());
            if (m.getName().startsWith("lambda$") || m.getName().equals("member")) continue;
            if (java.lang.reflect.Modifier.isPublic(m.getModifiers()) && m.getReturnType().getSimpleName().equals("AjaxResult"))
                assertTrue(m.isAnnotationPresent(GetMapping.class), m.getName() + " must be GET");
        }
        RequestMapping off = ApiAppGapsWorkshopController.class.getMethod("notEnabled",
                jakarta.servlet.http.HttpServletRequest.class, jakarta.servlet.http.HttpServletResponse.class)
                .getAnnotation(RequestMapping.class);
        assertTrue(Arrays.asList(off.value()).contains("/api/mod-settlement/**"),
                "anything that is not one of the exact GET reads (payout writes) still answers 503");
        for (var f : ApiCreatorEarningsController.class.getDeclaredFields())
            assertFalse(f.getType().getName().toLowerCase().contains("pay"), "no payment dependency: " + f);
    }
}
