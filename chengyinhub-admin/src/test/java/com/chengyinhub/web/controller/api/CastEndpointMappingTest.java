package com.chengyinhub.web.controller.api;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 角色 Cast v1:三个接口并入已登记的控制器后,URL 与 PR 合同保持一致。 */
class CastEndpointMappingTest
{
    @Test
    void 本局档案与分支_在玩家进度控制器下()
    {
        assertEquals("/api/play", ApiPlayProgressController.class.getAnnotation(RequestMapping.class).value()[0]);
        assertTrue(hasPost(ApiPlayProgressController.class, "/run/profile"));
        assertTrue(hasPost(ApiPlayProgressController.class, "/run/branch"));
    }

    @Test
    void 台词配音_在主题控制器下()
    {
        assertEquals("/api/topic", ApiTopicController.class.getAnnotation(RequestMapping.class).value()[0]);
        assertTrue(Arrays.stream(ApiTopicController.class.getDeclaredMethods())
                .map(m -> m.getAnnotation(GetMapping.class))
                .anyMatch(a -> a != null && Arrays.asList(a.value()).contains("/cast/voice")));
    }

    private static boolean hasPost(Class<?> type, String path)
    {
        for (Method m : type.getDeclaredMethods())
        {
            PostMapping a = m.getAnnotation(PostMapping.class);
            if (a != null && Arrays.asList(a.value()).contains(path)) return true;
        }
        return false;
    }
}
