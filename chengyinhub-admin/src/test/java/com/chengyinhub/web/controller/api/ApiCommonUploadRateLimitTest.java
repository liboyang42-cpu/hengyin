package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.enums.LimitType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 每张图都要等微信内容安全检查,上传不限频时单个客户端就能占住 Tomcat 线程、写满存储。 */
class ApiCommonUploadRateLimitTest {

    @Test
    void uploadEndpointsAreLimitedPerMember() {
        assertLimit("uploadOSS", 60);
        assertLimit("uploadFile", 60);
        assertLimit("uploadFiles", 20);
    }

    private static void assertLimit(String name, int count) {
        Method method = Arrays.stream(ApiCommonController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(name)).findFirst().orElseThrow(AssertionError::new);
        RateLimiter limiter = method.getAnnotation(RateLimiter.class);
        assertEquals(LimitType.MEMBER, limiter.limitType());
        assertEquals(60, limiter.time());
        assertEquals(count, limiter.count());
    }
}
