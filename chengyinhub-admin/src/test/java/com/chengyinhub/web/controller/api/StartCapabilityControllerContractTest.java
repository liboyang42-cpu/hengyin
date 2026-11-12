package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IAdvancedGameRuntimeService;
import com.chengyinhub.business.service.IOrientSessionService;
import com.chengyinhub.business.service.support.ContentCapabilityRequirements.Declaration;
import com.chengyinhub.business.service.support.ContentCapabilityRequirements.UnsupportedClientException;
import com.chengyinhub.business.service.support.OrientSessionException;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StartCapabilityControllerContractTest {
    @Test
    void advancedJsonPreservesOldArgumentsAndDoesNotTrustForgedRequiredCapabilities() {
        IAdvancedGameRuntimeService runtime = mock(IAdvancedGameRuntimeService.class);
        ApiAdvancedPlayController controller = new ApiAdvancedPlayController() {
            @Override public Long getAppUserId() { return 7L; }
        };
        ReflectionTestUtils.setField(controller, "runtimeService", runtime);
        when(runtime.start(eq(7L), eq(9L), eq(19L), eq(29L), isNull(), any(Declaration.class)))
                .thenThrow(new UnsupportedClientException());
        Map<String, Object> body = new HashMap<>(Map.of("activityId", 9, "topicId", 19, "nodeId", 29,
                "clientPlatform", "ios", "clientVersion", "1", "supportedCapabilities", List.of(),
                "requiredCapabilities", List.of()));
        AjaxResult denied = controller.start(body);
        assertEquals(500, denied.get("code")); // Existing advanced error(msg) envelope.
        ArgumentCaptor<Declaration> declaration = ArgumentCaptor.forClass(Declaration.class);
        verify(runtime).start(eq(7L), eq(9L), eq(19L), eq(29L), isNull(), declaration.capture());
        assertTrue(declaration.getValue().supplied());
        assertTrue(declaration.getValue().capabilities().isEmpty());
        assertEquals("ios", declaration.getValue().platform());
        verify(runtime, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void advancedUndeclaredClientUsesOriginalStartAndResponseShape() {
        IAdvancedGameRuntimeService runtime = mock(IAdvancedGameRuntimeService.class);
        ApiAdvancedPlayController controller = new ApiAdvancedPlayController() {
            @Override public Long getAppUserId() { return 7L; }
        };
        ReflectionTestUtils.setField(controller, "runtimeService", runtime);
        when(runtime.start(7L, 9L, 19L, 29L, null)).thenReturn(Map.of("sessionId", 51L));
        AjaxResult result = controller.start(Map.of("activityId", 9, "topicId", 19, "nodeId", 29));
        assertEquals(200, result.get("code"));
        assertEquals(51L, new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(result.get("data")).path("sessionId").asLong());
        verify(runtime, never()).start(any(), any(), any(), any(), any(), any(Declaration.class));
    }

    @Test
    void orientFormFieldsReachBothPrepareAndStartWithOriginalDenialEnvelope() {
        IOrientSessionService runtime = mock(IOrientSessionService.class);
        ApiOrientSessionController controller = new ApiOrientSessionController() {
            @Override public Long getAppUserId() { return 7L; }
        };
        ReflectionTestUtils.setField(controller, "orientSessionService", runtime);
        when(runtime.prepare(eq(7L), eq(19L), eq(0L), eq("prepare-capability"), any(Declaration.class)))
                .thenThrow(new OrientSessionException("CONTENT_CAPABILITY_UNSUPPORTED", 409, "请升级客户端"));
        when(runtime.start(eq(7L), eq(51L), eq(3L), eq("start-capability"), any(Declaration.class)))
                .thenThrow(new OrientSessionException("CONTENT_CAPABILITY_UNSUPPORTED", 409, "请升级客户端"));
        AjaxResult prepared = controller.prepare(19L, 0L, "prepare-capability", "ios", "1", "");
        AjaxResult started = controller.start(51L, 3L, "start-capability", "ios", "1", "");
        for (AjaxResult result : List.of(prepared, started)) {
            assertEquals(409, result.get("code"));
            assertEquals("CONTENT_CAPABILITY_UNSUPPORTED", result.get("err"));
        }
        ArgumentCaptor<Declaration> declaration = ArgumentCaptor.forClass(Declaration.class);
        verify(runtime).start(eq(7L), eq(51L), eq(3L), eq("start-capability"), declaration.capture());
        assertTrue(declaration.getValue().supplied());
        assertTrue(declaration.getValue().capabilities().isEmpty());
        verify(runtime, never()).start(any(), any(), any(), anyString());
    }
}
