package com.chengyinhub.web.controller.api;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 七条路由是小程序 api-existence 门禁的对手方,路径改了必须同步小程序。 */
class ApiRoamHangoutControllerContractTest {

    @Test
    void 七条路由齐全() {
        assertArrayEquals(new String[]{"/api/roam/hangout"},
                ApiRoamHangoutController.class.getAnnotation(RequestMapping.class).value());
        Set<String> gets = new HashSet<String>(), posts = new HashSet<String>();
        for (Method m : ApiRoamHangoutController.class.getDeclaredMethods()) {
            GetMapping g = m.getAnnotation(GetMapping.class);
            PostMapping p = m.getAnnotation(PostMapping.class);
            if (g != null) gets.add(g.value()[0]);
            if (p != null) posts.add(p.value()[0]);
        }
        assertTrue(gets.contains("/nearby") && gets.contains("/detail"), gets.toString());
        for (String p : new String[]{"/create", "/join", "/leave", "/close", "/report"}) assertTrue(posts.contains(p), p);
        assertEquals(7, gets.size() + posts.size());
    }
}
