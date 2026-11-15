package com.chengyinhub.framework.config;

import com.chengyinhub.web.controller.api.ApiChannelVerificationCallbackController;
import com.chengyinhub.web.controller.api.ApiCommonController;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-02:/api/common/ 下每条真实路由都必须被归类(公开或要登录)。新增一条路由时本测试变红,
 * 逼着作者决定它是否公开;默认是要登录(SecurityConfig 不再有 /api/common/** 通配)。
 * 行为证明在 UpgradeAuthorizationBoundaryTest(公开的 200、未列出的 401)。
 */
class CommonRoutesInventoryTest {

    private static final Set<String> PUBLIC = Set.of(
            "/api/common/dict", "/api/common/banner",
            "/api/common/infomation_list", "/api/common/infomation_detail",
            "/api/common/channel/callback/{providerKey}");

    private static final Set<String> AUTHENTICATED = Set.of(
            "/api/common/upload", "/api/common/uploads", "/api/common/uploadOSS",
            "/api/common/uploadOSS/receipt");

    @Test
    void everyCommonRouteIsClassified() {
        Set<String> actual = new TreeSet<>();
        for (Class<?> type : new Class<?>[]{ApiCommonController.class, ApiChannelVerificationCallbackController.class}) {
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String prefix = base == null || base.value().length == 0 ? "" : base.value()[0];
            for (Method method : type.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) continue;
                for (String path : mapping.value().length == 0 ? mapping.path() : mapping.value()) {
                    actual.add(prefix + (path.startsWith("/") ? path : "/" + path));
                }
            }
        }
        Set<String> expected = new TreeSet<>(PUBLIC);
        expected.addAll(AUTHENTICATED);
        assertEquals(expected, actual, "新增 /api/common 路由:先决定是否公开,再更新 SecurityConfig 与本表");
    }

    @Test
    void securityConfigHasNoCommonWildcard() throws Exception {
        String source = Files.readString(Path.of("..", "chengyinhub-framework", "src", "main", "java",
                "com", "chengyinhub", "framework", "config", "SecurityConfig.java"), StandardCharsets.UTF_8);
        String code = source; // 注释里只用不带引号的路径,所以带引号的字面量只可能来自代码
        assertFalse(code.contains("\"/api/common/**\""), "/api/common/** 通配不得回来");
        assertFalse(code.contains("\"/api/common/*\""), "/api/common/* 通配不得回来");
        for (String route : new String[]{"\"/api/common/dict\"", "\"/api/common/banner\"",
                "\"/api/common/infomation_list\"", "\"/api/common/infomation_detail\"",
                "\"/api/common/channel/callback/*\""}) {
            assertTrue(code.contains(route), route);
        }
    }
}
