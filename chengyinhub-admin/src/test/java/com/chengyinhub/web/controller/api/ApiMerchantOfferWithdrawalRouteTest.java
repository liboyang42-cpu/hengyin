package com.chengyinhub.web.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The app (MerchantOfferWithdrawalOriginalFlow) calls the offer-withdrawal request protocol as
 * GET current?merchantId&offerId, GET result?merchantId&offerId&requestId and POST withdraw (JSON).
 * The bare prefix /api/merchant/offer-withdrawal/request is not a route: a probe of the prefix answers 404 by design.
 */
class ApiMerchantOfferWithdrawalRouteTest {

    private static final String BASE = "/api/merchant/offer-withdrawal/request";

    @Test
    void protocolIsServedAtTheThreeChildPathsWithTheAppVerbs() {
        Map<String, Method> routes = new TreeMap<>();
        for (Method m : ApiMerchantGapController.class.getDeclaredMethods()) {
            GetMapping get = m.getAnnotation(GetMapping.class);
            PostMapping post = m.getAnnotation(PostMapping.class);
            assertFalse(m.isAnnotationPresent(RequestMapping.class) && String.join(",", m.getAnnotation(RequestMapping.class).value()).contains(BASE));
            for (String p : get == null ? new String[0] : get.value()) if (p.startsWith(BASE)) routes.put("GET " + p, m);
            for (String p : post == null ? new String[0] : post.value()) if (p.startsWith(BASE)) routes.put("POST " + p, m);
        }
        assertEquals("[GET " + BASE + "/current, GET " + BASE + "/result, POST " + BASE + "/withdraw]", routes.keySet().toString());
        assertEquals(2, requestParams(routes.get("GET " + BASE + "/current")));
        assertEquals(3, requestParams(routes.get("GET " + BASE + "/result")));
        Method withdraw = routes.get("POST " + BASE + "/withdraw");
        assertEquals(1, withdraw.getParameterCount());
        assertEquals(true, withdraw.getParameters()[0].isAnnotationPresent(RequestBody.class));
    }

    private static long requestParams(Method m) {
        return java.util.Arrays.stream(m.getParameters()).filter(p -> p.isAnnotationPresent(RequestParam.class)).count();
    }
}
