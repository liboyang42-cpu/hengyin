package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.PreferenceSubmitRequest;
import com.chengyinhub.business.domain.dto.SensorResultRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ApiPlayRouteContractTest {
    @Test
    void recoveryAndEveryCompletionEntryExposeTheSameCasContract() throws Exception {
        Method state = ApiPlayProgressController.class.getMethod("routeState", Long.class, Long.class, Long.class);
        assertArrayEquals(new String[]{"/route-state"}, state.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"topicId", "activityId", "actId"},
                Arrays.stream(state.getParameters()).map(parameter -> parameter.getAnnotation(RequestParam.class).name())
                        .toArray(String[]::new));

        // 完成写入口在 CAS(routeActionId/expectedRouteVersion)之后还要带原运行时身份
        // (routeSessionId/runtimeInstance/authorityEpoch);不带身份的旧重载一律拒绝。
        post("checkin", new Class<?>[]{String.class, String.class, String.class, String.class, Integer.class, Long.class,
                String.class, Long.class, String.class, Integer.class});
        // 2026-10-30 业主拍板「执行 50 米」:作答随带 longitude/latitude/accuracy(城市定向有坐标站按到达同一围栏判)
        post("answer", new Class<?>[]{String.class, String.class, String.class, String.class, String.class, Integer.class,
                Long.class, String.class, Integer.class, String.class, String.class, String.class});
        post("revealPuzzle", new Class<?>[]{String.class, String.class, String.class, String.class, Integer.class,
                Long.class, String.class, Integer.class});
        post("sensorResult", new Class<?>[]{SensorResultRequest.class});
        post("submitPreference", new Class<?>[]{Long.class, PreferenceSubmitRequest.class});
        post("arrive", new Class<?>[]{String.class, String.class, String.class, String.class, String.class, String.class, Integer.class, String.class,
                Long.class, String.class, Integer.class});
        post("photo", new Class<?>[]{String.class, String.class, String.class, String.class, String.class, Integer.class,
                Long.class, String.class, Integer.class});

        assertClientCannotChooseRoute(PreferenceSubmitRequest.class);
        assertClientCannotChooseRoute(SensorResultRequest.class);
    }

    private void post(String name, Class<?>[] parameters) throws Exception {
        Method method = ApiPlayProgressController.class.getMethod(name, parameters);
        assertNotNull(method.getAnnotation(PostMapping.class), name + " 必须保持完成写入口");
    }

    private void assertClientCannotChooseRoute(Class<?> requestType) {
        assertFalse(Arrays.stream(requestType.getDeclaredFields()).map(Field::getName)
                .anyMatch(name -> "outcomeCode".equals(name) || "targetNodeId".equals(name)));
        assertNotNull(field(requestType, "routeActionId"));
        assertNotNull(field(requestType, "expectedRouteVersion"));
    }

    private Field field(Class<?> type, String name) {
        try { return type.getDeclaredField(name); }
        catch (NoSuchFieldException e) { return null; }
    }
}
