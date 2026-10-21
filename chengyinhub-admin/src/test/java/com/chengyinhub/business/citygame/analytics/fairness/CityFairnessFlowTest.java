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
        CityFairnessReader.EventRow fact = task("city-task", "e1", "o1", 7, 6_000);
        assertEquals("STORED", ingestion.ingest(fact).status);
        assertEquals("DEDUPED", ingestion.ingest(task("city-task", "e1", "o1", 7, 6_000)).status);
        assertEquals(1, store.events.size());
        CityFairnessReader.EventRow changed = task("city-task", "e1", "o1", 7, 6_100);
        CityFairnessIngestionService.IngestResult conflict = ingestion.ingest(changed);
        assertEquals("CONFLICT", conflict.status);
        assertEquals("event-conflict:e1", conflict.reason);
        assertEquals(6_000L, store.events.get(0).occurredAt);
        assertEquals("not-an-outing", ingestion.ingest(mark("city-task", "reopen", null, APP_REOPEN, 7, 7_000)).reason);
        assertEquals("not-an-outing", ingestion.ingest(mark("city-task", "repeat", null, REGISTRATION_REPEAT, 7, 7_100)).reason);
        assertEquals(1, store.events.size());
    }

    @Test void sameEventIdFromAnotherSourceIsADifferentFact() {
        Store store = new Store();
        CityFairnessIngestionService ingestion = new CityFairnessIngestionService(store);
        assertEquals("STORED", ingestion.ingest(task("city-task", "e1", "o1", 7, 6_000)).status);
        assertEquals("STORED", ingestion.ingest(task("city-service", "e1", "o2", 7, 6_200)).status);
        assertEquals(2, store.events.size());
    }

    @Test void reportIsolatesAccountAndReadsTheSnapshotBack() {
        Store store = approvedStore();
        CityFairnessIngestionService ingestion = new CityFairnessIngestionService(store);
        ingestion.ingest(task("city-task", "a", "oa", 7, 6_000));
        CityFairnessReader.EventRow other = task("city-task", "b", "ob", 8, 6_100);
        other.teamSize = 9;
        ingestion.ingest(other);
        CityFairnessReportService reports = new CityFairnessReportService(store);
        CityFairnessReportService.Page first = reports.read(7, "q1", 10_000L, null);
        assertEquals(CityFairnessReader.COMPUTABLE, first.status);
        assertEquals("MATURE", first.maturity);
        assertEquals(1, first.groups.size());
        assertEquals(2, first.groups.get(0).teamSize);
        assertFalse(first.readBack);
        store.events.add(task("city-task", "later", "olater", 7, 6_500));
        CityFairnessReportService.Page retry = reports.read(7, "q1", 10_000L, "0");
        assertTrue(retry.readBack);
        assertEquals(1, retry.groups.size());
        assertEquals("query-conflict", reports.read(7, "q1", 10_001L, null).errorCode);
        CityFairnessReportService.Page accountEight = reports.read(8, "q1", 10_000L, null);
        assertEquals(1, accountEight.groups.size());
        assertEquals(9, accountEight.groups.get(0).teamSize);
        assertEquals(8L, accountEight.accountId);
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
        CityFairnessReportService.Page page = reports.read(7, "pages", 10_000L, null);
        assertEquals("0", page.cursor);
        assertEquals("1", page.nextCursor);
        assertEquals(2, page.groups.get(0).teamSize);
        CityFairnessReportService.Page next = reports.read(7, "pages", 10_000L, "1");
        assertTrue(next.readBack);
        assertEquals(9, next.groups.get(0).teamSize);
        assertNull(next.nextCursor);
        assertEquals(1, next.secondOutings.size());
        assertEquals("ob", next.secondOutings.get(0).outingId);
        CityFairnessReportService.Page again = reports.read(7, "pages", null, "0");
        assertTrue(again.readBack);
        assertEquals(2, again.groups.get(0).teamSize);
    }

    @Test void belowLowerBoundAndInsideDeltaAreImmature() {
        Store store = approvedStore();
        store.parameter.lowerEffectiveEvents = 2;
        new CityFairnessIngestionService(store).ingest(task("city-task", "e1", "o1", 7, 6_000));
        CityFairnessReportService.Page immature = new CityFairnessReportService(store).read(7, "low", 10_000L, null);
        assertEquals(CityFairnessReader.COMPUTABLE, immature.status);
        assertEquals("IMMATURE", immature.maturity);
        assertEquals("below-lower-bound", immature.outingClasses.get(0).reason);
        assertTrue(immature.groups.isEmpty());

        Store close = approvedStore();
        CityFairnessIngestionService ingestion = new CityFairnessIngestionService(close);
        ingestion.ingest(task("city-task", "e1", "o1", 7, 6_000));
        ingestion.ingest(task("city-task", "e2", "o2", 7, 6_050));
        CityFairnessReportService.Page mixed = new CityFairnessReportService(close).read(7, "gap", 10_000L, null);
        assertEquals("MIXED", mixed.maturity);
        assertTrue(mixed.secondOutings.isEmpty());
        assertEquals("inside-delta", mixed.outingClasses.get(1).reason);
    }

    @Test void missingPageSizeAndEventBoundAreNotEmptySuccess() {
        Store store = approvedStore();
        store.parameter.pageSize = null;
        new CityFairnessIngestionService(store).ingest(task("city-task", "e1", "o1", 7, 6_000));
        CityFairnessReportService.Page missing = new CityFairnessReportService(store).read(7, "nopage", 10_000L, null);
        assertEquals(CityFairnessReader.NOT_COMPUTABLE, missing.status);
        assertEquals("MISSING", missing.maturity);
        assertTrue(missing.missing.contains(PAGE_SIZE));
        assertTrue(missing.groups.isEmpty());

        Store bounded = approvedStore();
        bounded.parameter.maxEvents = 1;
        CityFairnessIngestionService ingestion = new CityFairnessIngestionService(bounded);
        ingestion.ingest(task("city-task", "e1", "o1", 7, 6_000));
        ingestion.ingest(task("city-task", "e2", "o2", 7, 6_200));
        CityFairnessReportService.Page bound = new CityFairnessReportService(bounded).read(7, "bound", 10_000L, null);
        assertTrue(bound.missing.contains("event-bound"));
        assertTrue(bound.groups.isEmpty());
    }

    @Test void controllerUsesTheLoggedInAccountAndRejectsAStranger() {
        Store store = approvedStore();
        new CityFairnessIngestionService(store).ingest(task("city-task", "a", "oa", 7, 6_000));
        CityFairnessReportService reports = new CityFairnessReportService(store);
        CityFairnessReportController stranger = new CityFairnessReportController(reports) {
            protected Long currentAccount() { return null; }
        };
        ResponseEntity<AjaxResult> denied = stranger.report("c1", 10_000L, null);
        assertEquals(401, denied.getStatusCode().value());
        assertTrue(store.reads.isEmpty());
        CityFairnessReportController owner = new CityFairnessReportController(reports) {
            protected Long currentAccount() { return 7L; }
        };
        ResponseEntity<AjaxResult> allowed = owner.report("c1", 10_000L, null);
        assertEquals(200, allowed.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) allowed.getBody().get(AjaxResult.DATA_TAG);
        assertEquals(7L, data.get("accountId"));
        assertEquals(Boolean.FALSE, data.get("readBack"));
        ResponseEntity<AjaxResult> retry = owner.report("c1", 10_000L, "0");
        @SuppressWarnings("unchecked")
        Map<String, Object> again = (Map<String, Object>) retry.getBody().get(AjaxResult.DATA_TAG);
        assertEquals(Boolean.TRUE, again.get("readBack"));
    }

    private static Store approvedStore() {
        Store store = new Store();
        store.parameter = new CityFairnessReader.ParameterRow();
        store.parameter.state = "APPROVED";
        store.parameter.sourceRef = "fairness-approval-1";
        store.parameter.deltaMillis = 100L;
        store.parameter.horizonMillis = 5_000L;
        store.parameter.lowerEffectiveEvents = 1;
        store.parameter.maxEvents = 10;
        store.parameter.pageSize = 10;
        return store;
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
        return row;
    }

    private static final class Store implements CityFairnessMapper {
        CityFairnessReader.ParameterRow parameter;
        final List<CityFairnessReader.EventRow> events = new ArrayList<>();
        final Map<String, CityFairnessReadRow> reads = new LinkedHashMap<>();

        public List<CityFairnessReader.ParameterRow> selectParameters() {
            return parameter == null ? List.of() : List.of(parameter);
        }

        public List<CityFairnessReader.EventRow> selectEvents(long actorId, long start, long asOf, int limit) {
            List<CityFairnessReader.EventRow> out = new ArrayList<>();
            for (CityFairnessReader.EventRow event : events) {
                if (event.actorId == actorId && event.occurredAt > start && event.occurredAt <= asOf) out.add(event);
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
            events.add(copy);
            return 1;
        }

        public int insertRead(CityFairnessReadRow row) {
            String key = row.accountId + ":" + row.queryId;
            if (reads.containsKey(key)) throw new DuplicateKeyException("read");
            reads.put(key, row);
            return 1;
        }

        public CityFairnessReadRow selectRead(long accountId, String queryId) {
            return reads.get(accountId + ":" + queryId);
        }
    }
}
