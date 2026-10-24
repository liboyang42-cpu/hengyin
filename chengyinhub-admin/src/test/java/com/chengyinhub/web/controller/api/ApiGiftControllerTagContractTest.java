package com.chengyinhub.web.controller.api;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 两个控制器共用 /api/gift 前缀（已发版客户端依赖，不改路径），
 * 但 Swagger Tag 必须可区分，且子路径不得重叠。
 */
class ApiGiftControllerTagContractTest {

    @Test
    void sharedPrefixButDistinctTagsAndNonOverlappingSubPaths() {
        assertArrayEquals(new String[]{"/api/gift"}, ApiGiftController.class.getAnnotation(RequestMapping.class).value());
        assertArrayEquals(new String[]{"/api/gift"}, ApiCrowdfundingController.class.getAnnotation(RequestMapping.class).value());

        String listTag = ApiGiftController.class.getAnnotation(Tag.class).name();
        String giveTag = ApiCrowdfundingController.class.getAnnotation(Tag.class).name();
        assertNotEquals(listTag, giveTag);

        Set<String> listPaths = postPaths(ApiGiftController.class);
        Set<String> givePaths = postPaths(ApiCrowdfundingController.class);
        assertTrue(listPaths.contains("/list"));
        assertTrue(givePaths.contains("/give"));
        Set<String> overlap = new HashSet<>(listPaths);
        overlap.retainAll(givePaths);
        assertTrue(overlap.isEmpty(), "共用前缀下子路径重叠: " + overlap);
    }

    private static Set<String> postPaths(Class<?> type) {
        Set<String> paths = new HashSet<>();
        for (Method m : type.getDeclaredMethods()) {
            PostMapping pm = m.getAnnotation(PostMapping.class);
            if (pm != null) {
                paths.addAll(Arrays.asList(pm.value()));
                paths.addAll(Arrays.asList(pm.path()));
            }
        }
        return paths;
    }
}
