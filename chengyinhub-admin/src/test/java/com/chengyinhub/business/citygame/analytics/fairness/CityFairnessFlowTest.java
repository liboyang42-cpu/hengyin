package com.chengyinhub.business.citygame.analytics.fairness;

import com.chengyinhub.business.citygame.analytics.fairness.mapper.CityFairnessMapper;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.chengyinhub.business.citygame.analytics.fairness.FairnessFactContract.*;
import static org.junit.jupiter.api.Assertions.*;

class CityFairnessFlowTest {
    @Test void ingestDedupesTheSameSourceAndEventAndRejectsADifferentPayload() {
        Store store = new Store();
        CityFairnessIngestionService ingestion = new CityFairnessIngestionService(store);
        assertEquals("STORED", ingestion.ingest(task("city-task", "e1", "o1", 7, 6_000)).status);
        assertEquals("DEDUPED", ingestion.ingest(task("city-task", "e1", "o1", 7, 6_000)).status);
        assertEquals(1, store.events.size());
        CityFairnessIngestionService.IngestResult conflict = ingestion.ingest(task("city-task", "e1", "o1", 7, 6_100));
        assertEquals("CONFLICT", conflict.status);
        assertEquals(6_000L, store.events.get(0).occurredAt);
        assertEquals("not-an-outing", ingestion.ingest(mark("city-task", "reopen", null, APP_REOPEN, 7, 7_000)).reason);
        assertEquals("not-an-outing", ingestion.ingest(mark("city-task", "repeat", null, REGISTRATION_REPEAT, 7, 7_100)).reason);
        CityFairnessReader.EventRow blank = task("city-task", "noscope", "o9", 7, 6_000);
        blank.boardId = " ";
        assertEquals("boardId", ingestion.ingest(blank).reason);
        assertEquals(1, store.events.size());
    }

    @Test void reportIsolatesAccountScopeAndReadsTheSnapshotBack() {
        Store store = approvedStore();
        CityFairnessIngestionService ingestion = new CityFairnessIngestionService(store);
        ingestion.ingest(task("city-task", "a", "oa", 7, 6_000));
        CityFairnessReader.EventRow other = task("city-task", "b", "ob", 8, 6_100);
        other.teamSize = 9;
        ingestion.ingest(other);
        CityFairnessReader.EventRow otherBoard = task("city-task", "c", "oc", 7, 6_300);
        otherBoard.boardId = "board-2";
        otherBoard.teamSize = 4;
        ingestion.ingest(otherBoard);
        CityFairnessReportService reports = new CityFairnessReportService(store);
        CityFairnessReportService.Page first = scoped(reports, 7, "q1", 10_000L, null);
        assertEquals(CityFairnessReader.COMPUTABLE, first.status);
        assertEquals("MATURE", first.maturity);
        assertEquals(1, first.groups.size());
        assertEquals(2, first.groups.get(0).teamSize);
        assertEquals(6_000L, first.outingClasses.get(0).t0);
        assertEquals("metric-1", first.metricVersion);
        assertEquals(10_000L, first.asOf);
        assertEquals(1, first.matureCount);
        assertEquals(1, first.denominator);
        assertFalse(first.readBack);
        store.events.add(task("city-task", "later", "olater", 7, 6_500));
        CityFairnessReportService.Page retry = scoped(reports, 7, "q1", 10_000L, "0");
        assertTrue(retry.readBack);
        assertEquals(1, retry.groups.size());
        assertEquals("query-conflict", scoped(reports, 7, "q1", 10_001L, null).errorCode);
        CityFairnessReportService.Page accountEight = scoped(reports, 8, "q1", 10_000L, null);
        assertEquals(9, accountEight.groups.get(0).teamSize);
        CityFairnessReader.ParameterRow secondScope = approved();
        secondScope.boardId = "board-2";
        store.parameters.add(secondScope);
        CityFairnessReportService.Page switched = reports.read(7, "q1", 10_000L, null, "board-2", "region-1", "season-1", "rules-1");
        assertEquals(4, switched.groups.get(0).teamSize);
        assertEquals(2, scoped(reports, 7, "q1", 10_000L, null).groups.get(0).teamSize);
        assertTrue(scoped(reports, 7, "q1", 10_000L, null).readBack);
    }

    @Test void queryOutsideApprovedScopeIsRejectedAndRatioStaysUnset() {
        Store store = approvedStore();
        new CityFairnessIngestionService(store).ingest(task("city-task", "a", "oa", 7, 6_000));
        CityFairnessReportService reports = new CityFairnessReportService(store);
        CityFairnessReportService.Page rejected = reports.read(7, "q1", 10_000L, null, "board-9", "region-1", "season-1", "rules-1");
        assertEquals(403, rejected.httpStatus);
        assertEquals("scope-rejected", rejected.errorCode);
        assertNull(rejected.matureCount);
        assertNull(rejected.denominator);
        assertEquals(10_000L, rejected.asOf);
        assertTrue(store.reads.isEmpty());
        store.parameter.graceMillis = null;
        CityFairnessReportService.Page missing = scoped(reports, 7, "nograce", 10_000L, null);
        assertEquals(CityFairnessReader.NOT_COMPUTABLE, missing.status);
        assertTrue(missing.missing.contains(GRACE_MILLIS));
        assertNull(missing.matureCount);
        assertNull(missing.denominator);
        assertEquals("metric-1", missing.metricVersion);
    }

    @Test void pagingRetryAndMaturityStayOnTheStoredReport() {
        Store store = approvedStore();
        store.parameter.pageSize = 1;
        CityFairnessIngestionService ingestion = new CityFairnessIngestionService(store);
        ingestion.ingest(task("city-task", "a", "oa", 7, 6_000));
        CityFairnessReader.EventRow second = task("city-task", "b", "ob", 7, 6_200);
        second.teamSize = 9;
        ingestion.ingest(second);
        CityFairnessReportService reports = new CityFairnessReportService(store);
        CityFairnessReportService.Page page = scoped(reports, 7, "pages", 10_000L, null);
        assertEquals("1", page.nextCursor);
        assertEquals(2, page.matureCount);
        assertEquals(2, page.denominator);
        CityFairnessReportService.Page next = scoped(reports, 7, "pages", 10_000L, "1");
        assertTrue(next.readBack);
        assertEquals(9, next.groups.get(0).teamSize);
        assertEquals("ob", next.secondOutings.get(0).outingId);
        assertEquals(6_200L, next.outingClasses.get(1).t0);
    }

    @Test void insideGraceAndInsideDeltaAreClassified() {
        Store store = approvedStore();
        store.parameter.graceMillis = 5_000L;
        new CityFairnessIngestionService(store).ingest(task("city-task", "e1", "o1", 7, 6_000));
        CityFairnessReportService.Page immature = scoped(new CityFairnessReportService(store), 7, "low", 10_000L, null);
        assertEquals("inside-grace", immature.outingClasses.get(0).reason);
        assertEquals(6_000L, immature.outingClasses.get(0).t0);
        assertNull(immature.matureCount);
        assertNull(immature.denominator);

        Store close = approvedStore();
        CityFairnessIngestionService ingestion = new CityFairnessIngestionService(close);
        ingestion.ingest(task("city-task", "e1", "o1", 7, 6_000));
        ingestion.ingest(task("city-task", "e2", "o2", 7, 6_050));
        CityFairnessReportService.Page mixed = scoped(new CityFairnessReportService(close), 7, "gap", 10_000L, null);
        assertEquals("MIXED", mixed.maturity);
        assertEquals("inside-delta", mixed.outingClasses.get(1).reason);
        assertEquals(1, mixed.matureCount);
        assertEquals(2, mixed.denominator);
        assertTrue(mixed.secondOutings.isEmpty());
    }

    @Test void missingPageSizeAndEventBoundAreNotEmptySuccess() {
        Store store = approvedStore();
        store.parameter.pageSize = null;
        new CityFairnessIngestionService(store).ingest(task("city-task", "e1", "o1", 7, 6_000));
        CityFairnessReportService.Page missing = scoped(new CityFairnessReportService(store), 7, "nopage", 10_000L, null);
        assertEquals(CityFairnessReader.NOT_COMPUTABLE, missing.status);
        assertTrue(missing.missing.contains(PAGE_SIZE));
        assertNull(missing.matureCount);
        assertTrue(missing.groups.isEmpty());

        Store bounded = approvedStore();
        bounded.parameter.maxEvents = 1;
        CityFairnessIngestionService ingestion = new CityFairnessIngestionService(bounded);
        ingestion.ingest(task("city-task", "e1", "o1", 7, 6_000));
        ingestion.ingest(task("city-task", "e2", "o2", 7, 6_200));
        CityFairnessReportService.Page bound = scoped(new CityFairnessReportService(bounded), 7, "bound", 10_000L, null);
        assertTrue(bound.missing.contains("event-bound"));
        assertNull(bound.denominator);
    }

    @Test void controllerUsesTheLoggedInAccountAndRejectsAStranger() {
        Store store = approvedStore();
        new CityFairnessIngestionService(store).ingest(task("city-task", "a", "oa", 7, 6_000));
        CityFairnessReportService reports = new CityFairnessReportService(store);
        CityFairnessReportController stranger = new CityFairnessReportController(reports) {
            protected Long currentAccount() { return null; }
        };
        ResponseEntity<AjaxResult> denied = stranger.report("c1", 10_000L, null, "board-1", "region-1", "season-1", "rules-1");
        assertEquals(401, denied.getStatusCode().value());
        CityFairnessReportController owner = new CityFairnessReportController(reports) {
            protected Long currentAccount() { return 7L; }
        };
        ResponseEntity<AjaxResult> allowed = owner.report("c1", 10_000L, null, "board-1", "region-1", "season-1", "rules-1");
        assertEquals(200, allowed.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) allowed.getBody().get(AjaxResult.DATA_TAG);
        assertEquals(7L, data.get("accountId"));
        assertEquals(10_000L, data.get("asOf"));
        assertEquals("metric-1", data.get("metricVersion"));
        assertEquals(1, data.get("matureCount"));
        assertEquals(1, data.get("denominator"));
        assertEquals(Boolean.FALSE, data.get("readBack"));
    }

    private static CityFairnessReportService.Page scoped(CityFairnessReportService reports, long account, String query,
                                                          Long asOf, String cursor) {
        return reports.read(account, query, asOf, cursor, "board-1", "region-1", "season-1", "rules-1");
    }

    private static Store approvedStore() {
        Store store = new Store();
        store.parameter = approved();
        store.parameters.add(store.parameter);
        return store;
    }

    private static CityFairnessReader.ParameterRow approved() {
        CityFairnessReader.ParameterRow row = new CityFairnessReader.ParameterRow();
        row.state = "APPROVED";
        row.sourceRef = "fairness-approval-1";
        row.deltaMillis = 100L;
        row.horizonMillis = 5_000L;
        row.graceMillis = 1_000L;
        row.maxEvents = 10;
        row.pageSize = 10;
        row.boardId = "board-1";
        row.regionId = "region-1";
        row.seasonId = "season-1";
        row.rulesVersion = "rules-1";
        row.metricVersion = "metric-1";
        return row;
    }

    private static CityFairnessReader.EventRow task(String source, String id, String outing, long actor, long at) {
        CityFairnessReader.EventRow row = mark(source, id, outing, EFFECTIVE_TASK, actor, at);
        row.joinTime = 1_000L;
        row.teamSize = 2;
        row.actionCost = 3;
        row.newcomer = NEWCOMER;
        return row;
    }

    private static CityFairnessReader.EventRow mark(String source, String id, String outing, String kind, long actor, long at) {
        CityFairnessReader.EventRow row = new CityFairnessReader.EventRow();
        row.source = source;
        row.eventId = id;
        row.outingId = outing;
        row.kind = kind;
        row.actorId = actor;
        row.occurredAt = at;
        row.boardId = "board-1";
        row.regionId = "region-1";
        row.seasonId = "season-1";
        row.rulesVersion = "rules-1";
        return row;
    }

    private static final class Store implements CityFairnessMapper {
        CityFairnessReader.ParameterRow parameter;
        final List<CityFairnessReader.ParameterRow> parameters = new ArrayList<>();
        final List<CityFairnessReader.EventRow> events = new ArrayList<>();
        final Map<String, CityFairnessReadRow> reads = new LinkedHashMap<>();

        public List<CityFairnessReader.ParameterRow> selectParameters(String boardId, String regionId, String seasonId, String rulesVersion) {
            List<CityFairnessReader.ParameterRow> out = new ArrayList<>();
            for (CityFairnessReader.ParameterRow row : parameters) {
                if (boardId.equals(row.boardId) && regionId.equals(row.regionId) && seasonId.equals(row.seasonId)
                        && rulesVersion.equals(row.rulesVersion)) out.add(row);
            }
            return out;
        }

        public List<CityFairnessReader.EventRow> selectEvents(long actorId, String boardId, String regionId, String seasonId,
                                                               String rulesVersion, long start, long asOf, int limit) {
            List<CityFairnessReader.EventRow> out = new ArrayList<>();
            for (CityFairnessReader.EventRow event : events) {
                if (event.actorId == actorId && boardId.equals(event.boardId) && regionId.equals(event.regionId)
                        && seasonId.equals(event.seasonId) && rulesVersion.equals(event.rulesVersion)
                        && event.occurredAt > start && event.occurredAt <= asOf) out.add(event);
                if (out.size() == limit) break;
            }
            return out;
        }

        public CityFairnessReader.EventRow selectEvent(String source, String eventId) {
            for (CityFairnessReader.EventRow event : events) {
                if (source.equals(event.source) && eventId.equals(event.eventId)) return event;
            }
            return null;
        }

        public int insertEvent(CityFairnessReader.EventRow row) {
            if (selectEvent(row.source, row.eventId) != null) throw new DuplicateKeyException("event");
            CityFairnessReader.EventRow copy = new CityFairnessReader.EventRow();
            copy.source = row.source;
            copy.eventId = row.eventId;
            copy.outingId = row.outingId;
            copy.kind = row.kind;
            copy.actorId = row.actorId;
            copy.occurredAt = row.occurredAt;
            copy.joinTime = row.joinTime;
            copy.teamSize = row.teamSize;
            copy.actionCost = row.actionCost;
            copy.newcomer = row.newcomer;
            copy.gainAmount = row.gainAmount;
            copy.verificationMillis = row.verificationMillis;
            copy.boardId = row.boardId;
            copy.regionId = row.regionId;
            copy.seasonId = row.seasonId;
            copy.rulesVersion = row.rulesVersion;
            events.add(copy);
            return 1;
        }

        public int insertRead(CityFairnessReadRow row) {
            String key = row.accountId + ":" + row.queryId + ":" + row.boardId + ":" + row.regionId + ":" + row.seasonId + ":" + row.rulesVersion;
            if (reads.containsKey(key)) throw new DuplicateKeyException("read");
            reads.put(key, row);
            return 1;
        }

        public CityFairnessReadRow selectRead(long accountId, String queryId, String boardId, String regionId,
                                               String seasonId, String rulesVersion) {
            return reads.get(accountId + ":" + queryId + ":" + boardId + ":" + regionId + ":" + seasonId + ":" + rulesVersion);
        }
    }
}
