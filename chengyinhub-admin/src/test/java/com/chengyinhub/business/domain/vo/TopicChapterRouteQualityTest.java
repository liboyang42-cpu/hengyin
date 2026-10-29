package com.chengyinhub.business.domain.vo;

import com.chengyinhub.common.core.domain.AjaxResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TopicChapterRouteQualityTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void missingAndFailedGeometryNeverClaimsProviderAvailability() {
        TopicChapterVO chapter = new TopicChapterVO();
        assertNull(chapter.getRouteGeometryAvailable(), "an unattempted/withheld chapter has no provider fact");
        chapter.setRouteGeometry(null); // Both existing topic detail callers already use this setter.
        var response = json.valueToTree(AjaxResult.success(chapter)).path("data");
        assertTrue(response.path("routeGeometry").isNull());
        assertFalse(response.path("routeGeometryAvailable").asBoolean(true));
        assertEquals("MAP_ROUTE_UNAVAILABLE", response.path("routeGeometryErrorCode").asText());
    }

    @Test void successfulRecoveryUsesSameWrapperAndClearsPreviousError() {
        TopicChapterVO chapter = new TopicChapterVO();
        chapter.setRouteGeometry(null);
        String geometry = "[{\"latitude\":30.1,\"longitude\":120.1},{\"latitude\":30.2,\"longitude\":120.2}]";
        chapter.setRouteGeometry(geometry);
        var response = json.valueToTree(AjaxResult.success(chapter)).path("data");
        assertEquals(geometry, response.path("routeGeometry").asText());
        assertTrue(response.path("routeGeometryAvailable").asBoolean());
        assertTrue(response.path("routeGeometryErrorCode").isNull());
    }
}
