package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IMapGeocodeService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiMapControllerSecurityContractTest {

    @Test
    void exposesServerSideReverseGeocodeEndpoint() {
        boolean hasReverseGeocode = Arrays.stream(ApiMapController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(PostMapping.class))
                .filter(annotation -> annotation != null)
                .flatMap(annotation -> Arrays.stream(annotation.value()))
                .anyMatch("/reverse-geocode"::equals);

        assertTrue(hasReverseGeocode);
    }

    @Test
    void returnsManualEntryFallbackWhenReverseGeocodeIsUnavailable() {
        IMapGeocodeService geocodeService = mock(IMapGeocodeService.class);
        when(geocodeService.resolveCity(120.1551D, 30.2741D)).thenReturn(null);
        ApiMapController controller = new ApiMapController();
        ReflectionTestUtils.setField(controller, "mapGeocodeService", geocodeService);

        AjaxResult result = controller.reverseGeocode("120.1551", "30.2741");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        assertEquals(Boolean.TRUE, data.get("manualInputRequired"));
        assertEquals("MAP_UNAVAILABLE", data.get("reason"));
    }

    @Test
    void stopsCallingProviderWhenReverseGeocodeRateBudgetIsExhausted() {
        IMapGeocodeService geocodeService = mock(IMapGeocodeService.class);
        when(geocodeService.resolveCity(120.1551D, 30.2741D)).thenReturn("杭州市");
        ApiMapController controller = new ApiMapController();
        ReflectionTestUtils.setField(controller, "mapGeocodeService", geocodeService);
        ReflectionTestUtils.setField(controller, "reverseGeocodePerMinute", 1);

        controller.reverseGeocode("120.1551", "30.2741");
        AjaxResult result = controller.reverseGeocode("120.1551", "30.2741");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        assertEquals(Boolean.TRUE, data.get("manualInputRequired"));
        assertEquals("MAP_RATE_LIMITED", data.get("reason"));
        verify(geocodeService, times(1)).resolveCity(120.1551D, 30.2741D);
    }
}
