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

    @Test
    void placeSearchValidatesInputAndSharesTheProviderRateBudget() {
        IMapGeocodeService geocodeService = mock(IMapGeocodeService.class);
        when(geocodeService.searchPlaces("海星市场", 121.37D, 31.10D))
                .thenReturn(java.util.Collections.singletonList(java.util.Collections.singletonMap("name", "海星市场")));
        ApiMapController controller = new ApiMapController();
        ReflectionTestUtils.setField(controller, "mapGeocodeService", geocodeService);
        ReflectionTestUtils.setField(controller, "reverseGeocodePerMinute", 1);

        assertEquals(500, controller.placeSearch("  ", "121.37", "31.10").get(AjaxResult.CODE_TAG));
        assertEquals(500, controller.placeSearch("海星市场", "999", "31.10").get(AjaxResult.CODE_TAG));
        AjaxResult ok = controller.placeSearch(" 海星市场 ", "121.37", "31.10");
        assertEquals(200, ok.get(AjaxResult.CODE_TAG));
        assertEquals(1, ((java.util.List<?>) ok.get(AjaxResult.DATA_TAG)).size());

        AjaxResult limited = controller.placeSearch("海星市场", "121.37", "31.10");
        assertTrue(((java.util.List<?>) limited.get(AjaxResult.DATA_TAG)).isEmpty(), "额度用完返回空列表,不再打供应商");
        verify(geocodeService, times(1)).searchPlaces("海星市场", 121.37D, 31.10D);
    }

    @Test
    void weatherValidatesCoordinatesIsIpRateLimitedAndReturnsOnlyTheTinyDto() throws Exception {
        com.chengyinhub.business.service.IMapWeatherService weatherService =
                mock(com.chengyinhub.business.service.IMapWeatherService.class);
        when(weatherService.weatherAt(30.27D, 120.15D))
                .thenReturn(new com.chengyinhub.business.domain.vo.MapWeatherVO("rain", 0.6D));
        ApiMapController controller = new ApiMapController();
        ReflectionTestUtils.setField(controller, "mapWeatherService", weatherService);

        for (String[] bad : new String[][]{{null, "120"}, {"30", ""}, {"91", "120"}, {"-91", "120"},
                {"30", "181"}, {"30", "-180.5"}, {"NaN", "120"}, {"abc", "120"}}) {
            assertEquals(500, controller.weather(bad[0], bad[1]).get(AjaxResult.CODE_TAG), Arrays.toString(bad));
        }
        verify(weatherService, times(0)).weatherAt(org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble());

        AjaxResult ok = controller.weather("30.27", "120.15");
        assertEquals(200, ok.get(AjaxResult.CODE_TAG));
        com.chengyinhub.business.domain.vo.MapWeatherVO vo =
                (com.chengyinhub.business.domain.vo.MapWeatherVO) ok.get(AjaxResult.DATA_TAG);
        assertEquals("rain", vo.getKind());

        java.lang.reflect.Method method = ApiMapController.class.getMethod("weather", String.class, String.class);
        assertTrue(Arrays.asList(method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class).value())
                .contains("/weather"));
        com.chengyinhub.common.annotation.RateLimiter limiter =
                method.getAnnotation(com.chengyinhub.common.annotation.RateLimiter.class);
        assertEquals(com.chengyinhub.common.enums.LimitType.IP, limiter.limitType());
    }

    @Test
    void geocodeValidatesInputReturnsCoordsAndSharesTheProviderRateBudget() {
        IMapGeocodeService geocodeService = mock(IMapGeocodeService.class);
        when(geocodeService.locateAddress("闵行区都市路 1 号", "上海")).thenReturn(new double[]{121.38D, 31.11D});
        ApiMapController controller = new ApiMapController();
        ReflectionTestUtils.setField(controller, "mapGeocodeService", geocodeService);
        ReflectionTestUtils.setField(controller, "reverseGeocodePerMinute", 1);

        assertEquals(500, controller.geocode("  ", "上海").get(AjaxResult.CODE_TAG));
        assertEquals(500, controller.geocode("x".repeat(121), "上海").get(AjaxResult.CODE_TAG));
        AjaxResult ok = controller.geocode(" 闵行区都市路 1 号 ", " 上海 ");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) ok.get(AjaxResult.DATA_TAG);
        assertEquals(121.38D, data.get("lng"));
        assertEquals(31.11D, data.get("lat"));
        AjaxResult limited = controller.geocode("闵行区都市路 1 号", "上海");
        assertTrue(((Map<?, ?>) limited.get(AjaxResult.DATA_TAG)).isEmpty(), "额度用完回空,不再打供应商");
        verify(geocodeService, times(1)).locateAddress("闵行区都市路 1 号", "上海");
    }
}
