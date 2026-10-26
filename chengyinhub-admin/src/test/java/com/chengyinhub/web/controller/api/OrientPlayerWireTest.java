package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.OrientScoreAppeal;
import com.chengyinhub.business.service.IOrientScoringService;
import com.chengyinhub.business.service.IOrientSessionService;
import com.chengyinhub.business.service.support.OrientSessionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrientPlayerWireTest {
    private static final long MAX = Long.MAX_VALUE;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void scoringControllersSerializeNestedIdsAndTotalsWithoutChangingSharedValues() throws Exception {
        IOrientScoringService service = mock(IOrientScoringService.class);
        ApiOrientScoringController controller = new ApiOrientScoringController() {
            @Override public Long getAppUserId() { return 7L; }
        };
        ReflectionTestUtils.setField(controller, "orientScoringService", service);
        OrientScoreAppeal appeal = new OrientScoreAppeal();
        appeal.setId(MAX);
        appeal.setSessionId(MAX);
        appeal.setMemberId(MAX);
        appeal.setEntryId(MAX);
        appeal.setNodeId(MAX);
        appeal.setResolutionEntryId(MAX);
        Map<String, Object> data = Map.of(
                "sessionId", MAX, "topicId", MAX, "activityId", MAX,
                "totalCenti", MAX, "byDimension", Map.of("TASK", MAX),
                "timeline", List.of(Map.of("entryId", MAX, "nodeId", MAX, "reversesEntryId", MAX,
                        "deltaCenti", Integer.MIN_VALUE, "runningCenti", MAX)),
                "settlement", Map.of("settlementId", MAX, "sessionId", MAX, "ledgerWatermarkId", MAX, "totalCenti", MAX),
                "appeals", List.of(appeal), "disputes", List.of(appeal));
        when(service.breakdown(7L, MAX)).thenReturn(data);
        JsonNode result = json.readTree(json.writeValueAsString(controller.breakdown(MAX))).get("data");
        for (String field : List.of("sessionId", "topicId", "activityId", "totalCenti")) exact(result.get(field));
        exact(result.path("byDimension").get("TASK"));
        for (String field : List.of("entryId", "nodeId", "reversesEntryId", "runningCenti")) exact(result.path("timeline").get(0).get(field));
        assertEquals(Integer.MIN_VALUE, result.path("timeline").get(0).path("deltaCenti").intValue());
        for (String field : List.of("settlementId", "sessionId", "ledgerWatermarkId", "totalCenti")) exact(result.path("settlement").get(field));
        for (String list : List.of("appeals", "disputes")) {
            for (String field : List.of("id", "sessionId", "memberId", "entryId", "nodeId", "resolutionEntryId")) exact(result.path(list).get(0).get(field));
        }
        when(service.receipt(7L, "APPEAL", MAX, "receipt-key")).thenReturn(Map.of("record", appeal));
        exact(json.readTree(json.writeValueAsString(controller.receipt("APPEAL", Long.toString(MAX), "receipt-key")))
                .path("data").path("record").get("id"));
        when(service.settle(7L, MAX, "settle-key")).thenReturn(Map.of("settlementId", MAX, "totalCenti", MAX));
        exact(json.readTree(json.writeValueAsString(controller.settle(MAX, "settle-key"))).path("data").get("settlementId"));
        when(service.submitAppeal(7L, MAX, MAX, MAX, "reason", "appeal-key")).thenReturn(appeal);
        exact(json.readTree(json.writeValueAsString(controller.appeal(Long.toString(MAX), Long.toString(MAX),
                Long.toString(MAX), "reason", "appeal-key"))).path("data").get("id"));
        when(service.submitDispute(7L, MAX, "reason", "dispute-key")).thenReturn(appeal);
        exact(json.readTree(json.writeValueAsString(controller.dispute(Long.toString(MAX), "reason", "dispute-key")))
                .path("data").get("id"));
        assertEquals(MAX, appeal.getId());
        assertInstanceOf(Long.class, data.get("totalCenti"));
    }

    @Test
    void sessionCurrentAndErrorReceiptsUseSameWireBoundaryAndKeepTimesNumeric() throws Exception {
        IOrientSessionService service = mock(IOrientSessionService.class);
        ApiOrientSessionController controller = new ApiOrientSessionController() {
            @Override Long uid() { return 7L; }
        };
        ReflectionTestUtils.setField(controller, "orientSessionService", service);
        Map<String, Object> view = Map.of("sessionId", MAX, "topicId", MAX, "activityId", 0L, "elapsedMs", 500L);
        when(service.current(7L, MAX, null)).thenReturn(Map.of("sessions", List.of(view)));
        JsonNode result = json.readTree(json.writeValueAsString(controller.current(MAX, null))).path("data").path("sessions").get(0);
        exact(result.get("sessionId"));
        exact(result.get("topicId"));
        assertEquals("0", result.path("activityId").textValue());
        assertTrue(result.path("elapsedMs").isNumber());
        when(service.receipt(7L, MAX, "receipt-key")).thenReturn(view);
        exact(json.readTree(json.writeValueAsString(controller.receipt(MAX, "receipt-key"))).path("data").get("sessionId"));
        when(service.start(7L, MAX, 1L, "start-key")).thenThrow(
                new OrientSessionException("ORIENT_SESSION_TERMINAL", 409, "已结束", view));
        exact(json.readTree(json.writeValueAsString(controller.start(MAX, 1L, "start-key"))).path("data").get("sessionId"));
        verify(service).current(7L, MAX, null);
    }

    @Test
    void currentRequiresLoginBeforeServiceCall() {
        IOrientSessionService service = mock(IOrientSessionService.class);
        ApiOrientSessionController controller = new ApiOrientSessionController() {
            @Override Long uid() { return 0L; }
        };
        ReflectionTestUtils.setField(controller, "orientSessionService", service);
        assertEquals("ORIENT_LOGIN_REQUIRED", controller.current(1L, null).get("err"));
        verifyNoInteractions(service);
    }

    private static void exact(JsonNode value) {
        assertTrue(value.isTextual());
        assertEquals(Long.toString(MAX), value.textValue());
    }
}
