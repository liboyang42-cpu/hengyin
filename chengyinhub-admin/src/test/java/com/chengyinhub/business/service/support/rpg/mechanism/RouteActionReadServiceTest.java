package com.chengyinhub.business.service.support.rpg.mechanism;

import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;

import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class RouteActionReadServiceTest {
    @Test
    void readReturnsSameRowWhenSessionMemberAndActionMatch() {
        TopicRouteSessionMapper mapper = mock(TopicRouteSessionMapper.class);
        TopicRouteAction stored = row(30L, 7L, "check:1:alarm:tick", "{\"ok\":true}");
        when(mapper.selectActionByAction(30L, 7L, "check:1:alarm:tick")).thenReturn(stored);
        RouteActionReadService service = new RouteActionReadService(mapper);

        TopicRouteAction found = service.read(30L, 7L, "  check:1:alarm:tick  ");

        assertSame(stored, found);
        assertEquals("{\"ok\":true}", found.getResponseJson());
        verify(mapper, times(1)).selectActionByAction(30L, 7L, "check:1:alarm:tick");
        verify(mapper, never()).insertAction(any());
        verify(mapper, never()).updateActionResponse(any(), any());
        verifyNoMoreInteractions(mapper);
        assertNotNull(RouteActionReadService.class.getAnnotation(Service.class));
        assertNoPermission(RouteActionReadService.class);
    }

    @Test
    void readReturnsNullWhenRowIdsDiffer() {
        TopicRouteSessionMapper mapper = mock(TopicRouteSessionMapper.class);
        when(mapper.selectActionByAction(30L, 7L, "check:1:alarm:tick")).thenReturn(
                row(30L, 7L, "game:done", "{}"),
                row(99L, 7L, "check:1:alarm:tick", "{}"),
                row(30L, 8L, "check:1:alarm:tick", "{}"),
                row(99L, 8L, "game:done", "{}"),
                row(null, 7L, "check:1:alarm:tick", "{}"),
                row(30L, null, "check:1:alarm:tick", "{}"),
                row(30L, 7L, null, "{}"),
                null);
        RouteActionReadService service = new RouteActionReadService(mapper);

        assertNull(service.read(30L, 7L, "check:1:alarm:tick"), "different action");
        assertNull(service.read(30L, 7L, "check:1:alarm:tick"), "different session");
        assertNull(service.read(30L, 7L, "check:1:alarm:tick"), "different member");
        assertNull(service.read(30L, 7L, "check:1:alarm:tick"), "different session member and action");
        assertNull(service.read(30L, 7L, "check:1:alarm:tick"), "missing session on row");
        assertNull(service.read(30L, 7L, "check:1:alarm:tick"), "missing member on row");
        assertNull(service.read(30L, 7L, "check:1:alarm:tick"), "missing action on row");
        assertNull(service.read(30L, 7L, "check:1:alarm:tick"), "missing row");
        verify(mapper, times(8)).selectActionByAction(30L, 7L, "check:1:alarm:tick");
        verify(mapper, never()).insertAction(any());
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void readDoesNotQueryWhenRequestIsNotThisAction() {
        TopicRouteSessionMapper mapper = mock(TopicRouteSessionMapper.class);
        RouteActionReadService service = new RouteActionReadService(mapper);

        assertNull(service.read(null, 7L, "check:1:alarm:tick"));
        assertNull(service.read(0L, 7L, "check:1:alarm:tick"));
        assertNull(service.read(30L, null, "check:1:alarm:tick"));
        assertNull(service.read(30L, 0L, "check:1:alarm:tick"));
        assertNull(service.read(30L, 7L, null));
        assertNull(service.read(30L, 7L, "   "));
        verify(mapper, never()).selectActionByAction(any(), any(), any());
        verify(mapper, never()).insertAction(any());
    }

    @Test
    void controllerHunkStaysUnapplied() throws Exception {
        Path root = repoRoot();
        String controller = Files.readString(root.resolve(
                "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiPlayProgressController.java"));
        String service = Files.readString(root.resolve(
                "chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/rpg/mechanism/RouteActionReadService.java"));
        String hunk = Files.readString(root.resolve("docs/issue5-hunks/C03-action-read-service.hunk"));

        assertTrue(controller.contains("@GetMapping(\"/route-state\")"));
        assertFalse(controller.contains("/route-action"));
        assertFalse(controller.contains("routeActionReadService"));
        assertEquals(1, count(service, "selectActionByAction("));
        assertFalse(service.contains("insertAction"));
        assertFalse(service.contains("PreAuthorize"));
        assertFalse(service.contains("WAITING_SOURCE"));
        assertTrue(hunk.contains("Not applied"));
        assertTrue(hunk.contains("Do not apply"));
        assertTrue(hunk.contains("@GetMapping(\"/route-action\")"));
        assertTrue(hunk.contains("currentMemberId()"));
        assertTrue(hunk.contains("routeActionReadService.read(sessionId, memberId, actionId)"));
        assertTrue(hunk.contains("does not change routeState()"));
        assertFalse(hunk.contains("insertAction"));
        assertFalse(hunk.contains("PreAuthorize"));
        assertFalse(hunk.contains("-    public AjaxResult routeState"));
    }

    private static TopicRouteAction row(Long sessionId, Long memberId, String actionId, String responseJson) {
        TopicRouteAction action = new TopicRouteAction();
        action.setSessionId(sessionId);
        action.setMemberId(memberId);
        action.setActionId(actionId);
        action.setResponseJson(responseJson);
        return action;
    }

    private static void assertNoPermission(Class<?> type) {
        rejectPermission(type.getAnnotations());
        for (var method : type.getDeclaredMethods()) {
            rejectPermission(method.getAnnotations());
        }
    }

    private static void rejectPermission(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            String name = annotation.annotationType().getSimpleName().toLowerCase();
            assertFalse(name.contains("preauthorize") || name.contains("requirespermissions")
                    || name.contains("permitall") || name.contains("grant"), name);
        }
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        if ("chengyinhub-admin".equals(dir.getFileName().toString())) {
            return dir.getParent();
        }
        return dir;
    }

    private static int count(String text, String token) {
        int found = 0;
        int from = 0;
        while (from <= text.length() - token.length()) {
            int at = text.indexOf(token, from);
            if (at < 0) {
                return found;
            }
            found++;
            from = at + token.length();
        }
        return found;
    }
}
