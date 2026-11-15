package com.chengyinhub.web.controller.api.appgaps;

import com.chengyinhub.business.appgaps.WorkshopOwnedRead;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ApiAppGapsWorkshopControllerTest {
    private final ApiAppGapsWorkshopController signedIn =
            new ApiAppGapsWorkshopController(new WorkshopOwnedRead()) { @Override boolean signedIn() { return true; } };

    @Test void commerceFamilyIsAlwaysRefusedWithoutTouchingPayment() throws Exception {
        assertEquals(401, new ApiAppGapsWorkshopController(new WorkshopOwnedRead()).commerceOff(null, null).get("code"),
                "未登录先回 401，App 走会话失效");
        MockHttpServletResponse response = new MockHttpServletResponse();
        var off = signedIn.commerceOff(new MockHttpServletRequest("POST", "/api/mod-commerce/orders"), response);
        assertEquals(503, off.get("code"));
        assertEquals("WORKSHOP_COMMERCE_DISABLED", off.get("errorCode"));
        assertEquals("POLICY_CLOSED", off.get("errorKind"));
        assertEquals("MOD_COMMERCE", response.getHeader(NotBuiltFeature.HEADER));
        RequestMapping m = ApiAppGapsWorkshopController.class
                .getMethod("commerceOff", HttpServletRequest.class, HttpServletResponse.class).getAnnotation(RequestMapping.class);
        assertTrue(Arrays.asList(m.value()).contains("/api/mod-commerce/**"));
        assertEquals(0, m.method().length, "所有 HTTP 方法都拒绝");
        for (var f : ApiAppGapsWorkshopController.class.getDeclaredFields())
            assertFalse(f.getType().getName().toLowerCase().contains("pay"), "控制器不得持有支付依赖: " + f);
    }

    /** R9 BE-06: every stub answer names its feature, says "not built" plainly, and keeps the code the app maps. */
    @Test void notBuiltStubsNameTheirFeatureAndAreMarkedNotServed() {
        String[][] cases = {
                {"/api/mod-installation/options", "MOD_INSTALLATION"},
                {"/api/mod-upgrade/versions", "MOD_UPGRADE"},
                {"/api/mod-owned-report/licenses/7/reports", "MOD_OWNED_REPORT"},
                {"/api/mod-settlement/mine/income", "MOD_SETTLEMENT"},
                {"/api/workshop/creator/pending-packages", "CREATOR_PENDING_PACKAGES"},
                {"/api/workshop/creator/public-theme-use/3", "CREATOR_PUBLIC_THEME_USE"},
        };
        for (String[] c : cases) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            var res = signedIn.notEnabled(new MockHttpServletRequest("GET", c[0]), response);
            assertEquals(503, res.get("code"), c[0]);
            assertEquals("WORKSHOP_FEATURE_NOT_ENABLED", res.get("errorCode"), "兼容旧 errorCode: " + c[0]);
            assertEquals(c[1], res.get("feature"), c[0]);
            assertEquals(Boolean.TRUE, res.get("notBuilt"), c[0]);
            assertEquals("NOT_BUILT", res.get("errorKind"), c[0]);
            assertEquals(c[1], response.getHeader(NotBuiltFeature.HEADER), c[0]);
            assertFalse(String.valueOf(res.get("msg")).matches("[A-Z_]+"), "msg 是人话，不是错误码: " + c[0]);
        }
    }

    @Test void everyMappedPatternIsListedAndTheListingIsComplete() throws Exception {
        Set<String> mapped = new HashSet<>();
        for (String name : new String[] {"commerceOff", "notEnabled"})
            mapped.addAll(Arrays.asList(ApiAppGapsWorkshopController.class
                    .getMethod(name, HttpServletRequest.class, HttpServletResponse.class).getAnnotation(RequestMapping.class).value()));
        Set<String> listed = new HashSet<>();
        for (NotBuiltFeature f : NotBuiltFeature.values()) listed.addAll(f.patterns());
        assertEquals(listed, mapped, "每个桩路由都必须在 NotBuiltFeature 清单里登记");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) signedIn.notBuiltList().get("data");
        assertEquals(NotBuiltFeature.values().length, rows.size());
        for (Map<String, Object> row : rows) {
            assertNotNull(row.get("messageZh"));
            assertNotNull(row.get("messageEn"));
        }
        assertNull(NotBuiltFeature.forPath("/api/mod-installationx"), "前缀匹配按路径段，不吞相邻路由");
    }
}
