package com.chengyinhub.business.citygame.analytics.value;

import com.chengyinhub.business.cityboard.CityBoardDraftDocument;
import com.chengyinhub.business.cityboard.CityBoardMemoryMapper;
import com.chengyinhub.business.cityboard.CityBoardOutcome;
import com.chengyinhub.business.cityboard.CityBoardPublicationService;
import com.chengyinhub.business.cityboard.CityBoardReleaseRow;
import com.chengyinhub.business.cityboard.CityBoardSourceProbe;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueAnalyticsServiceTest {
    private static final Instant FROM = Instant.parse("2026-10-20T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-21T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void missingBoardWritesNothing() {
        Harness harness = new Harness();
        ValueOutcome outcome = harness.service.query(4L, query("missing-board", "city-1", "board-1", "season-1", "rel1", definition()));
        assertEquals(200, outcome.httpStatus);
        assertEquals("NO_CURRENT_BOARD", outcome.data.get("status"));
        assertNull(outcome.data.get("funnel"));
        assertEquals(0, harness.events.eventCount());
        assertHonest(outcome.data);
    }

    @Test
    void callerAdaptsOnceAndReplaysFacts() throws Exception {
        Harness harness = new Harness();
        CityBoardReleaseRow release = publish(harness.boards);
        ValueOutcome first = harness.caller.pull(release.getRegionId());
        ValueOutcome second = harness.caller.pull(release.getRegionId());
        assertEquals("ADAPTED", first.data.get("status"));
        assertEquals(11, ((Number) first.data.get("accepted")).intValue());
        assertEquals(0, ((Number) second.data.get("accepted")).intValue());
        assertEquals(11, ((Number) second.data.get("replayed")).intValue());
        assertEquals(11, harness.events.eventCount());
        String details = details(harness);
        assertTrue(details.contains("MISSING_EMITTER"));
        assertTrue(details.contains("MISSING_FIELD"));
        assertFalse(details.contains("Walk every point"));
    }

    @Test
    void unreadableSnapshotDoesNotInventAmounts() {
        Harness harness = new Harness();
        CityBoardReleaseRow release = new CityBoardReleaseRow();
        release.setId(42L);
        release.setRegionId("city-2");
        release.setBoardId("board-2");
        release.setSeasonId("season-2");
        release.setRulesReleaseId("relbad");
        release.setContentHash("abc123");
        release.setSnapshotJson("{");
        release.setCurrentFlag(1);
        release.setCreateTime(new Date());
        harness.boards.insertRelease(release);
        ValueOutcome pulled = harness.caller.pull("city-2");
        assertEquals("ADAPTED", pulled.data.get("status"));
        assertEquals(11, harness.events.eventCount());
        String details = details(harness);
        assertTrue(details.contains("DOCUMENT_UNREADABLE"));
        assertFalse(details.contains("amountMinor"));
        assertFalse(details.contains("pointCostAmount"));
    }

    @Test
    void noiseAndUntrustedSourcesAreNotStored() throws Exception {
        Harness harness = new Harness();
        CityBoardReleaseRow release = publish(harness.boards);
        for (String action : List.of("click", "walk", "walking", "daily_login", "daily-login", "rent_login", "collect_rent")) {
            ValueContracts.Ingest ingest = behavior(release, ValueKinds.MEANINGFUL_ACTION, 1L, "visit-a", 5000);
            ingest.actionCode = action;
            ValueEventIngestor.Accept accept = harness.ingestor.acceptBehavior(ingest, release);
            assertEquals("REJECTED", accept.status);
            assertEquals("NOT_MEANINGFUL", accept.errorCode);
        }
        ValueContracts.Ingest sample = behavior(release, ValueKinds.ENTRY, 1L, "visit-a", 1000);
        sample.sourceId = "sample";
        assertEquals("NOT_TRUSTED", harness.ingestor.acceptBehavior(sample, release).errorCode);
        sample.sourceId = ValueEventIngestor.FACT_SOURCE;
        assertEquals("NOT_TRUSTED", harness.ingestor.acceptBehavior(sample, release).errorCode);
        assertEquals(0, harness.events.behaviorCount());

        ValueEventIngestor production = new ValueEventIngestor(harness.events, Set.of(ValueEventIngestor.FACT_SOURCE), CLOCK);
        assertEquals("NOT_TRUSTED", production.acceptBehavior(behavior(release, ValueKinds.ENTRY, 1L, "visit-a", 1000), release).errorCode);
        assertEquals(0, harness.events.behaviorCount());
    }

    @Test
    void duplicateReplayAndAmountConflictDoNotInflate() throws Exception {
        Harness harness = new Harness();
        CityBoardReleaseRow release = publish(harness.boards);
        ValueContracts.Ingest entry = behavior(release, ValueKinds.ENTRY, 1L, "visit-a", 1000);
        assertEquals("ACCEPTED", harness.ingestor.acceptBehavior(entry, release).status);
        assertEquals("REPLAYED", harness.ingestor.acceptBehavior(entry, release).status);
        ValueContracts.Ingest points = behavior(release, ValueKinds.NET_POINTS, 1L, "visit-a", 1200);
        points.amount = 10L;
        points.unit = "POINT";
        assertEquals("ACCEPTED", harness.ingestor.acceptBehavior(points, release).status);
        points.amount = 11L;
        ValueEventIngestor.Accept conflict = harness.ingestor.acceptBehavior(points, release);
        assertEquals("DUPLICATE_CONFLICT", conflict.status);
        assertEquals(10L, conflict.row.getAmount().longValue());
        assertEquals(2, harness.events.behaviorCount());
        ValueContracts.Query pointsQuery = query("points-1", release, pointsOnly());
        pointsQuery.priorFrom = null;
        pointsQuery.priorTo = null;
        ValueOutcome outcome = harness.service.query(4L, pointsQuery);
        Map<String, Object> net = map(map(outcome.data.get("visitCost")).get("netPoints"));
        assertEquals("MEASURED", net.get("status"));
        assertEquals(10L, ((Number) net.get("sum")).longValue());
        assertEquals("MISSING_DEFINITION", map(map(outcome.data.get("funnel")).get("meaningfulAction")).get("status"));
        assertHonest(outcome.data);
    }

    @Test
    void noiseDefinitionStoresNothing() throws Exception {
        Harness harness = new Harness();
        CityBoardReleaseRow release = publish(harness.boards);
        ValueContracts.Definition definition = definition();
        definition.meaningfulActions = Set.of("walk");
        ValueOutcome outcome = harness.service.query(4L, query("noise-def", release, definition));
        assertEquals(400, outcome.httpStatus);
        assertEquals("NOT_MEANINGFUL", outcome.errorCode);
        assertEquals(0, harness.events.eventCount());
        assertEquals(0, harness.events.requestCount());
    }

    @Test
    void measuredFunnelTimeRatingsCostAndSplits() throws Exception {
        Harness harness = new Harness();
        CityBoardReleaseRow release = publish(harness.boards);
        seed(harness, release, true);
        ValueOutcome outcome = harness.service.query(4L, query("measured", release, definition()));
        assertEquals("REPORTED", outcome.data.get("status"));
        Map<String, Object> funnel = map(outcome.data.get("funnel"));
        assertEquals(2, number(map(funnel.get("entry")).get("visitCount")));
        assertEquals("2/2", map(funnel.get("meaningfulAction")).get("rate"));
        assertEquals("2/2", map(funnel.get("serviceSuccess")).get("rate"));
        assertEquals("1/2", map(funnel.get("publicGoal")).get("rate"));
        assertEquals("1/2", map(funnel.get("cooperativeGoal")).get("rate"));
        assertEquals(2500L, ((Number) map(outcome.data.get("timeToFirstEffectiveChoice")).get("medianMillis")).longValue());
        assertEquals("too-far", ((Map<?, ?>) list(map(map(outcome.data.get("skipExitReasons")).get("skip")).get("reasons")).get(0)).get("reasonCode"));
        assertEquals("done", ((Map<?, ?>) list(map(map(outcome.data.get("skipExitReasons")).get("exit")).get("reasons")).get(0)).get("reasonCode"));
        Map<String, Object> ratings = map(outcome.data.get("serviceRatings"));
        assertEquals("9/2", ratings.get("mean"));
        assertEquals(9L, ((Number) ratings.get("sum")).longValue());
        Map<String, Object> net = map(map(outcome.data.get("visitCost")).get("netPoints"));
        Map<String, Object> budget = map(map(outcome.data.get("visitCost")).get("budget"));
        assertEquals(8L, ((Number) net.get("sum")).longValue());
        assertEquals("POINT", net.get("unit"));
        assertEquals(150L, ((Number) budget.get("sum")).longValue());
        assertEquals("CNY", budget.get("currency"));
        assertEquals("MEASURED", map(outcome.data.get("byService")).get("status"));
        assertEquals(1, number(map(map(outcome.data.get("byCohort")).get("newcomer")).get("visitCount")));
        assertEquals(1, number(map(map(outcome.data.get("byCohort")).get("veteran")).get("visitCount")));
        assertEquals(0, number(map(outcome.data.get("boardFacts")).get("definition") instanceof Map<?, ?> ignored
                ? map(map(outcome.data.get("boardFacts")).get("definition")).get("pointCostAmount") : null));
        assertHonest(outcome.data);
        assertFalse(String.valueOf(outcome.data).contains("Walk every point"));

        ValueAnalyticsService restarted = new ValueAnalyticsService(harness.events, harness.caller, CLOCK);
        ValueOutcome again = restarted.query(4L, query("measured", release, definition()));
        assertEquals(number(outcome.data.get("resultId")), number(again.data.get("resultId")));
        assertEquals(1, harness.events.requestCount());
        assertEquals(1, harness.events.reportCount());
    }

    @Test
    void absentCoopStaysMissingAndEntriesDoNotBecomeZero() throws Exception {
        Harness harness = new Harness();
        CityBoardReleaseRow release = publish(harness.boards);
        seed(harness, release, false);
        ValueOutcome outcome = harness.service.query(4L, query("no-coop", release, definition()));
        assertEquals("1/2", map(map(outcome.data.get("funnel")).get("publicGoal")).get("rate"));
        Map<String, Object> coop = map(map(outcome.data.get("funnel")).get("cooperativeGoal"));
        assertEquals("MISSING_COVERAGE", coop.get("status"));
        assertNull(coop.get("rate"));
        assertNull(coop.get("visitCount"));

        Harness entries = new Harness();
        CityBoardReleaseRow second = publish(entries.boards);
        entries.ingestor.acceptBehavior(behavior(second, ValueKinds.ENTRY, 1L, "visit-a", 1000), second);
        ValueOutcome only = entries.service.query(4L, query("entries", second, definition()));
        assertEquals("MEASURED", map(map(only.data.get("funnel")).get("entry")).get("status"));
        assertEquals("MISSING_COVERAGE", map(map(only.data.get("funnel")).get("meaningfulAction")).get("status"));
        assertNull(map(map(only.data.get("funnel")).get("meaningfulAction")).get("rate"));
        assertEquals("MISSING_COVERAGE", map(only.data.get("byCohort")).get("status"));
        assertNull(map(only.data.get("byCohort")).get("newcomer"));
        assertHonest(only.data);
    }

    @Test
    void truncationScopeFutureAndWindowStayStable() throws Exception {
        Harness harness = new Harness();
        CityBoardReleaseRow release = publish(harness.boards);
        harness.ingestor.acceptBehavior(behavior(release, ValueKinds.ENTRY, 1L, "visit-a", 1000), release);
        harness.ingestor.acceptBehavior(behavior(release, ValueKinds.ENTRY, 2L, "visit-b", 2000), release);
        harness.ingestor.acceptBehavior(behavior(release, ValueKinds.ENTRY, 3L, "visit-c", 3000), release);
        ValueContracts.Query narrow = query("truncated", release, definition());
        narrow.limit = 2;
        ValueOutcome incomplete = harness.service.query(4L, narrow);
        assertEquals("INCOMPLETE", incomplete.data.get("status"));
        assertNull(incomplete.data.get("funnel"));
        assertTrue(incomplete.data.get("resumeFrom") instanceof String);
        ValueOutcome retry = harness.service.query(4L, narrow);
        assertEquals(incomplete.data.get("resumeFrom"), retry.data.get("resumeFrom"));
        assertEquals(1, harness.events.requestCount());

        ValueContracts.Query wide = query("wide", release, definition());
        wide.from = Instant.parse("2026-09-01T00:00:00Z");
        wide.to = Instant.parse("2026-10-03T00:00:00Z");
        assertEquals("INVALID_WINDOW", harness.service.query(4L, wide).errorCode);
        assertEquals("INVALID_WINDOW", harness.service.query(4L, wide).data.get("status"));

        ValueContracts.Query mismatch = query("mismatch", release, definition());
        mismatch.rulesReleaseId = "rel-other";
        ValueOutcome scoped = harness.service.query(4L, mismatch);
        assertEquals("SCOPE_MISMATCH", scoped.errorCode);
        assertNull(scoped.data.get("funnel"));

        ValueContracts.Ingest future = behavior(release, ValueKinds.ENTRY, 9L, "visit-z", 1000);
        future.occurredAt = Date.from(CLOCK.instant().plusSeconds(6 * 60));
        assertEquals("FUTURE_EVENT", harness.ingestor.acceptBehavior(future, release).errorCode);

        ValueOutcome read = harness.service.readResult(4L, ((Number) incomplete.data.get("resultId")).longValue(), "city-x", release.getRulesReleaseId());
        assertEquals("SCOPE_MISMATCH", read.errorCode);
        assertNull(read.data.get("funnel"));
        assertEquals("ACCOUNT_CHANGED", harness.service.query(8L, narrow).errorCode);
        assertNull(harness.service.query(8L, narrow).data.get("funnel"));
    }

    @Test
    void outOfOrderRestartCancelAndAccount() throws Exception {
        Harness harness = new Harness();
        CityBoardReleaseRow release = publish(harness.boards);
        ValueContracts.Ingest meaningful = behavior(release, ValueKinds.MEANINGFUL_ACTION, 1L, "visit-a", 5000);
        meaningful.actionCode = "choose-route";
        harness.ingestor.acceptBehavior(meaningful, release);
        harness.ingestor.acceptBehavior(behavior(release, ValueKinds.ENTRY, 1L, "visit-a", 1000), release);
        ValueOutcome outcome = harness.service.query(4L, query("order", release, definition()));
        assertEquals(1, number(map(map(outcome.data.get("funnel")).get("entry")).get("visitCount")));
        assertEquals("1/1", map(map(outcome.data.get("funnel")).get("meaningfulAction")).get("rate"));

        ValueOutcome cancelled = harness.service.cancel(4L, "cancel-first");
        assertEquals("CANCELLED", cancelled.data.get("status"));
        assertEquals("CANCELLED", harness.service.cancel(4L, "cancel-first").data.get("status"));
        ValueContracts.Query blocked = query("cancel-first", release, definition());
        assertEquals("DUPLICATE_REQUEST", harness.service.query(4L, blocked).errorCode);
        assertEquals("CANCELLED", harness.service.readRequest(4L, "cancel-first").data.get("status"));

        ValueOutcome reject = harness.service.cancel(4L, "order");
        assertEquals("CANCEL_REJECTED", reject.errorCode);
        assertEquals("REPORTED", reject.data.get("status"));
        assertEquals("REPORTED", harness.service.readRequest(4L, "order").data.get("status"));
        assertEquals("ACCOUNT_CHANGED", harness.service.readRequest(8L, "order").errorCode);
        assertNull(harness.service.readRequest(8L, "order").data.get("resultId"));
    }

    private static void seed(Harness harness, CityBoardReleaseRow release, boolean coop) {
        ValueContracts.Ingest prior = behavior(release, ValueKinds.SERVICE_SUCCESS, 2L, "visit-old", 100);
        prior.occurredAt = Date.from(Instant.parse("2026-10-19T00:00:00Z").plusMillis(100));
        prior.serviceId = "s2";
        harness.ingestor.acceptBehavior(prior, release);
        harness.ingestor.acceptBehavior(behavior(release, ValueKinds.ENTRY, 1L, "visit-a", 1000), release);
        harness.ingestor.acceptBehavior(behavior(release, ValueKinds.ENTRY, 1L, "visit-a", 1000), release);
        ValueContracts.Ingest chooseA = behavior(release, ValueKinds.MEANINGFUL_ACTION, 1L, "visit-a", 5000);
        chooseA.actionCode = "choose-route";
        chooseA.serviceId = "s1";
        harness.ingestor.acceptBehavior(chooseA, release);
        ValueContracts.Ingest serviceA = behavior(release, ValueKinds.SERVICE_SUCCESS, 1L, "visit-a", 8000);
        serviceA.serviceId = "s1";
        harness.ingestor.acceptBehavior(serviceA, release);
        ValueContracts.Ingest goalA = behavior(release, ValueKinds.PUBLIC_GOAL, 1L, "visit-a", 9000);
        goalA.goalId = "g1";
        harness.ingestor.acceptBehavior(goalA, release);
        ValueContracts.Ingest ratingA = behavior(release, ValueKinds.SERVICE_RATING, 1L, "visit-a", 9100);
        ratingA.serviceId = "s1";
        ratingA.rating = 4;
        harness.ingestor.acceptBehavior(ratingA, release);
        ValueContracts.Ingest pointsA = behavior(release, ValueKinds.NET_POINTS, 1L, "visit-a", 9200);
        pointsA.amount = 10L;
        pointsA.unit = "POINT";
        pointsA.serviceId = "s1";
        harness.ingestor.acceptBehavior(pointsA, release);
        ValueContracts.Ingest budgetA = behavior(release, ValueKinds.VISIT_BUDGET, 1L, "visit-a", 9300);
        budgetA.amount = 100L;
        budgetA.unit = "MINOR";
        budgetA.currency = "CNY";
        budgetA.serviceId = "s1";
        harness.ingestor.acceptBehavior(budgetA, release);

        harness.ingestor.acceptBehavior(behavior(release, ValueKinds.ENTRY, 2L, "visit-b", 2000), release);
        ValueContracts.Ingest chooseB = behavior(release, ValueKinds.MEANINGFUL_ACTION, 2L, "visit-b", 3000);
        chooseB.actionCode = "choose-route";
        harness.ingestor.acceptBehavior(chooseB, release);
        ValueContracts.Ingest serviceB = behavior(release, ValueKinds.SERVICE_SUCCESS, 2L, "visit-b", 4000);
        serviceB.serviceId = "s2";
        harness.ingestor.acceptBehavior(serviceB, release);
        if (coop) {
            ValueContracts.Ingest goalB = behavior(release, ValueKinds.COOPERATIVE_GOAL, 2L, "visit-b", 4500);
            goalB.goalId = "g2";
            harness.ingestor.acceptBehavior(goalB, release);
        }
        ValueContracts.Ingest skip = behavior(release, ValueKinds.SKIP, 2L, "visit-b", 4600);
        skip.reasonCode = "too-far";
        harness.ingestor.acceptBehavior(skip, release);
        ValueContracts.Ingest exit = behavior(release, ValueKinds.EXIT, 2L, "visit-b", 4700);
        exit.reasonCode = "done";
        harness.ingestor.acceptBehavior(exit, release);
        ValueContracts.Ingest ratingB = behavior(release, ValueKinds.SERVICE_RATING, 2L, "visit-b", 4800);
        ratingB.serviceId = "s2";
        ratingB.rating = 5;
        harness.ingestor.acceptBehavior(ratingB, release);
        ValueContracts.Ingest pointsB = behavior(release, ValueKinds.NET_POINTS, 2L, "visit-b", 4900);
        pointsB.amount = -2L;
        pointsB.unit = "POINT";
        harness.ingestor.acceptBehavior(pointsB, release);
        ValueContracts.Ingest budgetB = behavior(release, ValueKinds.VISIT_BUDGET, 2L, "visit-b", 4950);
        budgetB.amount = 50L;
        budgetB.unit = "MINOR";
        budgetB.currency = "CNY";
        harness.ingestor.acceptBehavior(budgetB, release);
    }

    private static ValueContracts.Query query(String requestId, CityBoardReleaseRow release, ValueContracts.Definition definition) {
        return query(requestId, release.getRegionId(), release.getBoardId(), release.getSeasonId(), release.getRulesReleaseId(), definition);
    }

    private static ValueContracts.Query query(String requestId, String regionId, String boardId, String seasonId, String rules, ValueContracts.Definition definition) {
        ValueContracts.Query query = new ValueContracts.Query();
        query.requestId = requestId;
        query.regionId = regionId;
        query.boardId = boardId;
        query.seasonId = seasonId;
        query.rulesReleaseId = rules;
        query.from = FROM;
        query.to = TO;
        query.priorFrom = Instant.parse("2026-10-19T00:00:00Z");
        query.priorTo = FROM;
        query.limit = 50;
        query.definition = definition;
        return query;
    }

    private static ValueContracts.Definition definition() {
        ValueContracts.Definition definition = new ValueContracts.Definition();
        definition.meaningfulActions = Set.of("choose-route");
        definition.effectiveChoices = Set.of("choose-route");
        definition.ratingMin = 1;
        definition.ratingMax = 5;
        definition.veteranMinPriorServiceSuccess = 1;
        definition.budgetCurrency = "CNY";
        definition.pointsUnit = "POINT";
        return definition;
    }

    private static ValueContracts.Definition pointsOnly() {
        ValueContracts.Definition definition = new ValueContracts.Definition();
        definition.pointsUnit = "POINT";
        return definition;
    }

    private static ValueContracts.Ingest behavior(CityBoardReleaseRow release, String kind, long memberId, String visitId, long millis) {
        ValueContracts.Ingest ingest = new ValueContracts.Ingest();
        ingest.sourceId = "server-play";
        ingest.kind = kind;
        ingest.regionId = release.getRegionId();
        ingest.boardId = release.getBoardId();
        ingest.seasonId = release.getSeasonId();
        ingest.rulesReleaseId = release.getRulesReleaseId();
        ingest.memberId = memberId;
        ingest.visitId = visitId;
        ingest.occurredAt = Date.from(FROM.plusMillis(millis));
        return ingest;
    }

    private static CityBoardReleaseRow publish(CityBoardMemoryMapper boards) throws Exception {
        CityBoardPublicationService publication = new CityBoardPublicationService(boards, new CityBoardSourceProbe());
        CityBoardOutcome saved = publication.save(7L, json(saveBody("save-1")));
        assertEquals(200, saved.httpStatus, String.valueOf(saved.errorCode));
        long draftId = ((Number) saved.data.get("draftId")).longValue();
        assertEquals(200, publication.validate(8L, json(draftBody("approve-1", draftId))).httpStatus);
        assertEquals(200, publication.publish(9L, json(draftBody("pub-1", draftId))).httpStatus);
        return boards.selectCurrentRelease("city-1");
    }

    private static JsonNode json(String text) throws Exception {
        return CityBoardDraftDocument.mapper().readTree(text);
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

    private static String details(Harness harness) {
        StringBuilder details = new StringBuilder();
        for (ValueEventRow row : harness.events.rows()) {
            details.append(row.getDetailJson()).append('\n');
        }
        return details.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private static int number(Object value) {
        return ((Number) value).intValue();
    }

    private static void assertHonest(Object node) {
        if (node instanceof Map<?, ?> map) {
            assertFalse(map.containsKey("success"));
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() instanceof String text) {
                    assertFalse(text.contains("%"), text);
                }
                assertHonest(entry.getValue());
            }
        } else if (node instanceof Iterable<?> items) {
            for (Object item : items) {
                assertHonest(item);
            }
        }
    }

    private static final class Harness {
        private final CityBoardMemoryMapper boards = new CityBoardMemoryMapper();
        private final ValueEventMemoryMapper events = new ValueEventMemoryMapper();
        private final ValueEventIngestor ingestor = new ValueEventIngestor(events, Set.of(ValueEventIngestor.FACT_SOURCE, "server-play"), CLOCK);
        private final CityValueReleaseCaller caller = new CityValueReleaseCaller(boards, ingestor);
        private final ValueAnalyticsService service = new ValueAnalyticsService(events, caller, CLOCK);
    }
}
