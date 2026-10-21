package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.cityboard.CityBoardDraftDocument;
import com.chengyinhub.business.cityboard.CityBoardMemoryMapper;
import com.chengyinhub.business.cityboard.CityBoardPublicationService;
import com.chengyinhub.business.cityboard.CityBoardReleaseRow;
import com.chengyinhub.business.cityboard.CityBoardSourceProbe;
import com.chengyinhub.business.citygame.analytics.value.CityValuePermissions;
import com.chengyinhub.business.citygame.analytics.value.CityValueReleaseCaller;
import com.chengyinhub.business.citygame.analytics.value.ValueAnalyticsService;
import com.chengyinhub.business.citygame.analytics.value.ValueContracts;
import com.chengyinhub.business.citygame.analytics.value.ValueEventIngestor;
import com.chengyinhub.business.citygame.analytics.value.ValueEventMemoryMapper;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.framework.web.service.PermissionService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityValueReadControllerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant FROM = Instant.parse("2026-10-20T00:00:00Z");

    private AnnotationConfigApplicationContext context;
    private CityValueReadController controller;
    private ValueEventMemoryMapper events;
    private CityBoardMemoryMapper boards;
    private ValueEventIngestor ingestor;

    @BeforeEach
    void setUp() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        context = new AnnotationConfigApplicationContext(Config.class);
        controller = context.getBean(CityValueReadController.class);
        events = context.getBean(ValueEventMemoryMapper.class);
        boards = context.getBean(CityBoardMemoryMapper.class);
        ingestor = context.getBean(ValueEventIngestor.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        context.close();
    }

    @Test
    void unrelatedPermissionCannotRead() {
        login(7L, "system:config:edit");
        assertThrows(AccessDeniedException.class, () -> controller.report(reportRequest("req-1", "city-1", "board-1", "season-1", "rel1")));
        assertEquals(0, events.eventCount());
        assertEquals(0, events.requestCount());
    }

    @Test
    void missingBoardIsCoverageNotZero() {
        login(4L, CityValuePermissions.REPORT_QUERY);
        ResponseEntity<AjaxResult> response = controller.report(reportRequest("req-empty", "city-1", "board-1", "season-1", "rel1"));
        assertEquals(200, response.getStatusCode().value());
        assertEquals("city-value", response.getHeaders().getFirst("X-Chengyin-Candidate"));
        assertEquals(Boolean.TRUE, response.getBody().get("candidate"));
        assertEquals("NO_CURRENT_BOARD", map(response).get("status"));
        assertNull(map(response).get("funnel"));
        assertFalse(String.valueOf(map(response)).contains("%"));
        assertEquals(0, events.eventCount());
    }

    @Test
    void reportReplayPagingCancelAndReadback() throws Exception {
        CityBoardReleaseRow release = publish();
        login(4L, CityValuePermissions.REPORT_QUERY);
        MockHttpServletRequest first = reportRequest("req-report", release.getRegionId(), release.getBoardId(), release.getSeasonId(), release.getRulesReleaseId());
        ResponseEntity<AjaxResult> report = controller.report(first);
        assertEquals(200, report.getStatusCode().value());
        assertEquals(11, events.eventCount());
        assertEquals("MISSING_COVERAGE", map(map(map(report).get("funnel")).get("entry")).get("status"));
        ResponseEntity<AjaxResult> replay = controller.report(reportRequest("req-report", release.getRegionId(), release.getBoardId(), release.getSeasonId(), release.getRulesReleaseId()));
        assertEquals(map(report).get("resultId"), map(replay).get("resultId"));
        assertEquals(1, events.requestCount());

        login(8L, CityValuePermissions.REPORT_QUERY);
        ResponseEntity<AjaxResult> other = controller.report(reportRequest("req-report", release.getRegionId(), release.getBoardId(), release.getSeasonId(), release.getRulesReleaseId()));
        assertEquals(409, other.getStatusCode().value());
        assertEquals("ACCOUNT_CHANGED", other.getBody().get("errorCode"));
        assertNull(map(other).get("funnel"));
        assertNull(map(other).get("resultId"));

        login(4L, CityValuePermissions.REPORT_QUERY);
        seedEntries(release);
        MockHttpServletRequest page = eventRequest(release, 2, null);
        ResponseEntity<AjaxResult> eventsPage = controller.events(page);
        List<?> pageEvents = (List<?>) map(eventsPage).get("events");
        assertEquals(2, pageEvents.size());
        String cursor = String.valueOf(map(eventsPage).get("nextCursor"));
        ResponseEntity<AjaxResult> next = controller.events(eventRequest(release, 2, cursor));
        assertEquals(1, ((List<?>) map(next).get("events")).size());
        ResponseEntity<AjaxResult> same = controller.events(eventRequest(release, 2, null));
        assertEquals(ids(pageEvents), ids((List<?>) map(same).get("events")));

        login(8L, CityValuePermissions.REPORT_QUERY);
        ResponseEntity<AjaxResult> foreign = controller.events(eventRequest(release, 2, cursor));
        assertEquals("ACCOUNT_CHANGED", foreign.getBody().get("errorCode"));
        assertNull(map(foreign).get("events"));

        login(4L, CityValuePermissions.REPORT_QUERY);
        ResponseEntity<AjaxResult> failed = controller.events(eventRequest(release, 0, cursor));
        assertEquals(400, failed.getStatusCode().value());
        assertEquals(cursor, map(failed).get("retryCursor"));
        assertNull(map(failed).get("events"));
        assertNull(map(failed).get("nextCursor"));

        long resultId = ((Number) map(report).get("resultId")).longValue();
        MockHttpServletRequest read = new MockHttpServletRequest();
        read.setParameter("regionId", release.getRegionId());
        read.setParameter("rulesReleaseId", release.getRulesReleaseId());
        ResponseEntity<AjaxResult> result = controller.result(resultId, read);
        assertEquals(200, result.getStatusCode().value());
        assertEquals(map(report).get("resultId"), map(result).get("resultId"));

        MockHttpServletRequest cancel = new MockHttpServletRequest();
        cancel.setParameter("intent", "cancel");
        ResponseEntity<AjaxResult> rejected = controller.request("req-report", cancel);
        assertEquals("CANCEL_REJECTED", rejected.getBody().get("errorCode"));
        assertEquals("REPORTED", map(rejected).get("status"));
        ResponseEntity<AjaxResult> still = controller.request("req-report", new MockHttpServletRequest());
        assertEquals("REPORTED", map(still).get("status"));

        assertEquals("REPLAYED", ingestor.acceptBehavior(behavior(release, "visit-a"), release).status);
        MockHttpServletRequest defined = reportRequest("req-entry-defined", release.getRegionId(), release.getBoardId(), release.getSeasonId(), release.getRulesReleaseId());
        defined.setParameter("meaningfulActions", "choose-route");
        defined.setParameter("effectiveChoices", "choose-route");
        ResponseEntity<AjaxResult> measured = controller.report(defined);
        assertEquals(3, ((Number) map(map(map(measured).get("funnel")).get("entry")).get("visitCount")).intValue());
    }

    @Test
    void unknownActorParameterIsRejected() throws Exception {
        publish();
        login(4L, CityValuePermissions.REPORT_QUERY);
        MockHttpServletRequest request = reportRequest("req-actor", "city-1", "board-1", "season-1", "rel1");
        request.setParameter("actor", "4");
        ResponseEntity<AjaxResult> response = controller.report(request);
        assertEquals(400, response.getStatusCode().value());
        assertEquals("UNKNOWN_FIELD", response.getBody().get("errorCode"));
        assertEquals(0, events.requestCount());
    }

    private CityBoardReleaseRow publish() throws Exception {
        CityBoardPublicationService publication = context.getBean(CityBoardPublicationService.class);
        var saved = publication.save(7L, json(saveBody("save-1")));
        assertEquals(200, saved.httpStatus);
        long draftId = ((Number) saved.data.get("draftId")).longValue();
        assertEquals(200, publication.validate(8L, json(draftBody("approve-1", draftId))).httpStatus);
        assertEquals(200, publication.publish(9L, json(draftBody("pub-1", draftId))).httpStatus);
        return boards.selectCurrentRelease("city-1");
    }

    private void seedEntries(CityBoardReleaseRow release) {
        ingestor.acceptBehavior(behavior(release, "visit-a"), release);
        ValueContracts.Ingest second = behavior(release, "visit-b");
        second.memberId = 2L;
        second.occurredAt = Date.from(FROM.plusMillis(2000));
        ingestor.acceptBehavior(second, release);
        ValueContracts.Ingest third = behavior(release, "visit-c");
        third.memberId = 3L;
        third.occurredAt = Date.from(FROM.plusMillis(3000));
        ingestor.acceptBehavior(third, release);
    }

    private static ValueContracts.Ingest behavior(CityBoardReleaseRow release, String visitId) {
        ValueContracts.Ingest ingest = new ValueContracts.Ingest();
        ingest.sourceId = "server-play";
        ingest.kind = "ENTRY";
        ingest.regionId = release.getRegionId();
        ingest.boardId = release.getBoardId();
        ingest.seasonId = release.getSeasonId();
        ingest.rulesReleaseId = release.getRulesReleaseId();
        ingest.memberId = 1L;
        ingest.visitId = visitId;
        ingest.occurredAt = Date.from(FROM.plusMillis(1000));
        return ingest;
    }

    private static MockHttpServletRequest reportRequest(String requestId, String regionId, String boardId, String seasonId, String rules) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("requestId", requestId);
        request.setParameter("regionId", regionId);
        request.setParameter("boardId", boardId);
        request.setParameter("seasonId", seasonId);
        request.setParameter("rulesReleaseId", rules);
        request.setParameter("from", "2026-10-20T00:00:00Z");
        request.setParameter("to", "2026-10-21T00:00:00Z");
        request.setParameter("limit", "50");
        return request;
    }

    private static MockHttpServletRequest eventRequest(CityBoardReleaseRow release, int limit, String cursor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("regionId", release.getRegionId());
        request.setParameter("boardId", release.getBoardId());
        request.setParameter("seasonId", release.getSeasonId());
        request.setParameter("rulesReleaseId", release.getRulesReleaseId());
        request.setParameter("from", "2026-10-20T00:00:00Z");
        request.setParameter("to", "2026-10-21T00:00:00Z");
        request.setParameter("limit", Integer.toString(limit));
        if (cursor != null) {
            request.setParameter("cursor", cursor);
        }
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(ResponseEntity<AjaxResult> response) {
        return (Map<String, Object>) response.getBody().get(AjaxResult.DATA_TAG);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static List<?> ids(List<?> events) {
        return events.stream().map(item -> ((Map<?, ?>) item).get("id")).toList();
    }

    private static JsonNode json(String text) throws Exception {
        return CityBoardDraftDocument.mapper().readTree(text);
    }

    private static void login(long userId, String... permissions) {
        SysUser user = new SysUser();
        user.setUserName("city-value");
        user.setPassword("ignored");
        LoginUser loginUser = new LoginUser(userId, 1L, user, new HashSet<String>(java.util.Arrays.asList(permissions)));
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null, java.util.Collections.emptyList()));
        SecurityContextHolder.setContext(securityContext);
    }

    private static String saveBody(String requestId) {
        return "{\"requestId\":\"" + requestId + "\",\"expectedVersion\":0,\"document\":" + doc() + "}";
    }

    private static String draftBody(String requestId, long draftId) {
        return "{\"requestId\":\"" + requestId + "\",\"draftId\":" + draftId + ",\"expectedVersion\":1}";
    }

    private static String doc() {
        return "{"
                + "\"title\": \"Synthetic CITY\",\"regionId\": \"city-1\",\"gameId\": \"game-1\",\"boardId\": \"board-1\","
                + "\"season\": {\"seasonId\": \"season-1\", \"budget\": {\"currency\": \"CNY\", \"amountMinor\": 10000}},"
                + "\"seasonSettlement\": {\"budgetCurrency\": \"CNY\", \"budgetAmountMinor\": 10000},"
                + "\"rules\": {\"rulesId\": \"rules-1\", \"text\": \"Walk every point.\"},"
                + "\"dice\": {\"diceId\": \"dice-1\", \"faces\": [1, 2, 3, 4]},"
                + "\"pointCost\": {\"unit\": \"POINT\", \"amount\": 0},"
                + "\"serviceFee\": {\"currency\": \"CNY\", \"amountMinor\": 100},"
                + "\"protection\": {\"challengeId\": \"protect-1\", \"text\": \"Stay on marked path\"},"
                + "\"safety\": {\"statement\": \"Operator confirmed the board text\", \"confirmed\": true},"
                + "\"pointRoles\": [{\"roleId\": \"walker\", \"title\": \"Walker\"}],"
                + "\"points\": [{\"pointId\": \"point-1\", \"title\": \"Gate\", \"latitude\": 31.2, \"longitude\": 121.5, \"roleId\": \"walker\", \"domain\": \"PLATFORM_VIRTUAL\"}],"
                + "\"tasks\": [{\"taskId\": \"task-1\", \"title\": \"Visit gate\", \"pointId\": \"point-1\", \"steps\": [\"Arrive\"]}],"
                + "\"locationPermits\": [],"
                + "\"permissionMatrix\": [{\"permission\": \"cityboard:player:read\", \"scope\": \"region:city-1\"}]"
                + "}";
    }

    @Configuration
    @EnableMethodSecurity(prePostEnabled = true)
    static class Config {
        @Bean("ss")
        PermissionService permissionService() {
            return new PermissionService();
        }

        @Bean
        CityBoardMemoryMapper cityBoardMapper() {
            return new CityBoardMemoryMapper();
        }

        @Bean
        ValueEventMemoryMapper valueEventMapper() {
            return new ValueEventMemoryMapper();
        }

        @Bean
        CityBoardPublicationService cityBoardPublicationService(CityBoardMemoryMapper mapper) {
            return new CityBoardPublicationService(mapper, new CityBoardSourceProbe());
        }

        @Bean
        ValueEventIngestor valueEventIngestor(ValueEventMemoryMapper mapper) {
            return new ValueEventIngestor(mapper, Set.of(ValueEventIngestor.FACT_SOURCE, "server-play"), CLOCK);
        }

        @Bean
        CityValueReleaseCaller cityValueReleaseCaller(CityBoardMemoryMapper boards, ValueEventIngestor ingestor) {
            return new CityValueReleaseCaller(boards, ingestor);
        }

        @Bean
        ValueAnalyticsService valueAnalyticsService(ValueEventMemoryMapper events, CityValueReleaseCaller caller) {
            return new ValueAnalyticsService(events, caller, CLOCK);
        }

        @Bean
        CityValueReadController cityValueReadController(ValueAnalyticsService service) {
            return new CityValueReadController(service);
        }
    }
}
