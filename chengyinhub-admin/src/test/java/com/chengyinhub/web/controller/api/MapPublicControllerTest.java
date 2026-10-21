package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.map.MapPublicException;
import com.chengyinhub.business.domain.map.MapPublicQuery;
import com.chengyinhub.business.domain.map.MapPublicResult;
import com.chengyinhub.business.service.MapPublicService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MapPublicControllerTest {

    @Test
    void exposesAuthenticatedCandidateRoute() {
        boolean mapped = Arrays.stream(MapPublicController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(PostMapping.class))
                .filter(annotation -> annotation != null)
                .flatMap(annotation -> Arrays.stream(annotation.value()))
                .anyMatch("/public-layers"::equals);
        assertTrue(mapped);
    }

    @Test
    void rejectsASessionThatIsNotAnAppPlayer() {
        MapPublicService service = mock(MapPublicService.class);
        Harness controller = controller(service, null);

        AjaxResult result = controller.publicLayers(new MapPublicQuery());

        assertEquals(401, result.get(AjaxResult.CODE_TAG));
        verifyNoInteractions(service);
    }

    @Test
    void returnsTheReadResultForTheCurrentPlayer() {
        MapPublicService service = mock(MapPublicService.class);
        MapPublicResult body = new MapPublicResult();
        body.setDatum("GCJ-02");
        when(service.query(eq(77L), any())).thenReturn(body);
        Harness controller = controller(service, 77L);
        MapPublicQuery query = new MapPublicQuery();

        AjaxResult result = controller.publicLayers(query);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(body, result.get(AjaxResult.DATA_TAG));
        verify(service).query(77L, query);
    }

    @Test
    void mapsCursorRejectionWithoutRetryingAWrite() {
        MapPublicService service = mock(MapPublicService.class);
        when(service.query(eq(77L), any())).thenThrow(new MapPublicException("CURSOR_REJECTED"));
        Harness controller = controller(service, 77L);

        AjaxResult result = controller.publicLayers(new MapPublicQuery());

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("CURSOR_REJECTED", result.get(AjaxResult.MSG_TAG));
        verify(service).query(eq(77L), any());
    }

    private static Harness controller(MapPublicService service, Long viewerId) {
        Harness controller = new Harness();
        controller.viewerId = viewerId;
        ReflectionTestUtils.setField(controller, "mapPublicService", service);
        return controller;
    }

    static final class Harness extends MapPublicController {
        Long viewerId = 77L;

        @Override
        Long viewerId() {
            return viewerId;
        }
    }
}
