package com.chengyinhub.web.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.service.IOrientScoringService;
import com.chengyinhub.common.core.domain.AjaxResult;

class ApiOrientScoringControllerTest {

    private ApiOrientScoringController controller;
    private IOrientScoringService scoring;

    @BeforeEach
    void setUp() {
        scoring = mock(IOrientScoringService.class);
        controller = spy(new ApiOrientScoringController());
        ReflectionTestUtils.setField(controller, "orientScoringService", scoring);
        doReturn(7L).when(controller).getAppUserId();
    }

    @Test
    void receiptKeepsNotFoundAndDropsSettlementForAnotherSession() {
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("state", "NOT_FOUND");
        missing.put("kind", "SETTLE");
        when(scoring.receipt(7L, "SETTLE", 9L, "key-settle-1")).thenReturn(missing);
        @SuppressWarnings("unchecked")
        Map<String, Object> notFound = (Map<String, Object>) controller.receipt("SETTLE", "9", "key-settle-1")
                .get(AjaxResult.DATA_TAG);
        assertEquals("NOT_FOUND", notFound.get("state"));

        Map<String, Object> other = new LinkedHashMap<>();
        other.put("state", "APPLIED");
        other.put("sessionId", 8L);
        when(scoring.receipt(7L, "SETTLE", 9L, "key-settle-2")).thenReturn(other);
        assertNull(controller.receipt("SETTLE", "9", "key-settle-2").get(AjaxResult.DATA_TAG));

        Map<String, Object> same = new LinkedHashMap<>();
        same.put("state", "APPLIED");
        same.put("sessionId", 9L);
        when(scoring.receipt(7L, "SETTLE", 9L, "key-settle-3")).thenReturn(same);
        @SuppressWarnings("unchecked")
        Map<String, Object> kept = (Map<String, Object>) controller.receipt("SETTLE", "9", "key-settle-3")
                .get(AjaxResult.DATA_TAG);
        assertEquals(9L, kept.get("sessionId"));
    }
}
