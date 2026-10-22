package com.chengyinhub.business.citygame.analytics.fairness;

import com.chengyinhub.business.citygame.analytics.fairness.contract.FairnessFieldContract;
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
        CityFairnessReader.EventRow unknown = task("city-task", "unknown", "o9", 7, 6_000);
        unknown.knownAt = null;
        assertEquals("knownAt", ingestion.ingest(unknown).reason);
        assertEquals("STORED", ingestion.ingest(mark("city-task", "scan", "scan-1", SCAN, 7, 7_000)).status);
        assertEquals(2, store.events.size());
    }

    @Test void reportIsolatesAccountScopeAndReadsTheSnapshotBack() {
        Store store = approvedStore();
        CityFairnessIngestionService ingestion = new CityFairnessIngestionService(store);
        ingestion.ingest(task("city-task", "a", "oa", 7, 6_000));
        CityFairnessReader.EventRow other = task("city-task", "b", "ob", 8, 6_000);
        other.teamSize = 9;
        ingestion.ingest(other);
        CityFairnessReader.EventRow otherBoard = task("city-task", "c", "oc", 7, 6_300);
        otherBoard.boardId = "board-2";
        otherBoard.teamSize = 4;
        ingestion.ingest(otherBoard);
        CityFairnessReportService reports = new CityFairnessReportService(store);
        CityFairnessReportService.Page first = scoped(reports, 7, "q1", 12_000L, null);
        assertEquals(CityFairnessReader.COMPUTABLE, first.status);
        assertEquals(FairnessFieldContract.COUNTED, first.maturity);
        assertEquals(1, first.groups.size());
        assertEquals(2, first.groups.get(0).teamSize);
        assertEquals(6_000L, first.outingClasses.get(0).t0);
        assertEquals(7L, first.outingClasses.get(0).actorId);
        assertEquals("metric-1", first.metricVersion);
        assertEquals(FairnessFieldContract.FORMULA_ID, first.formulaId);
        assertEquals(12_000L, first.asOf);
        assertEquals(0, first.matureCount);
        assertEquals(1, first.denominator);
        assertNull(first.cursor);
        assertFalse(first.readBack);
        store.events.add(task("city-task", "later", "olater", 7, 6_500));
        CityFairnessReportService.Page retry = scoped(reports, 7, "q1", 12_000L, "0");
        assertTrue(retry.readBack);
        assertNull(retry.cursor);
        assertEquals(1, retry.groups.size());
        assertEquals(0, retry.matureCount);
        assertEquals(1, retry.denominator);
        assertEquals("query-conflict", scoped(reports, 7, "q1", 12_001L, null).errorCode);
        CityFairnessReportService.Page accountEight = scoped(reports, 8, "q1", 12_000L, null);
        assertFalse(accountEight.readBack);
        assertEquals(0, accountEight.matureCount);
        assertEquals(1, accountEight.denominator);
        assertEquals(8L, accountEight.outingClasses.get(0).actorId);
        assertEquals(9, accountEight.groups.get(0).teamSize);
        CityFairnessReader.ParameterRow secondScope = approved();
        secondScope.boardId = "board-2";
        store.parameters.add(secondScope);
        CityFairnessReportService.Page switched = reports.read(7, "q1", 12_000L, null, "board-2", "region-1", "season-1", "rules-1");
        assertEquals(4, switched.groups.get(0).teamSize);
        assertNull(switched.denominator);
        assertEquals(FairnessFieldContract.NO_MATURE_SAMPLE, switched.maturity);
        assertEquals(2, scoped(reports, 7, "q1", 12_000L, null).groups.get(0).teamSize);
        assertTrue(scoped(reports, 7, "q1", 12_000L, null).readBack);
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
        ingestion.ingest(task("city-task", "a2", "oa2", 7, 6_200));
        CityFairnessReader.EventRow other = task("city-task", "b", "ob", 8, 6_000);
        other.teamSize = 9;
        ingestion.ingest(other);
        CityFairnessReportService reports = new CityFairnessReportService(store);
        CityFairnessReportService.Page page = scoped(reports, 7, "pages", 12_000L, null);
        assertNull(page.cursor);
        assertNull(page.nextCursor);
        assertEquals(1, page.matureCount);
        assertEquals(1, page.denominator);
        assertEquals(1, page.groups.size());
        assertEquals(2, page.groups.get(0).teamSize);
        assertEquals(1, page.outingClasses.size());
        assertEquals(7L, page.outingClasses.get(0).actorId);
        assertEquals("oa2", page.secondOutings.get(0).outingId);
        assertEquals(6_000L, page.outingClasses.get(0).t0);
    }

    @Test void awaitingMaturityAndACloseSecondOutingDoNotInventARate() {
        Store store = approvedStore();
        new CityFairnessIngestionService(store).ingest(task("city-task", "e1", "o1", 7, 6_000));
        CityFairnessReportService.Page immature = scoped(new CityFairnessReportService(store), 7, "low", 7_000L, null);
        assertEquals(FairnessFieldContract.AWAITING_MATURITY, immature.outingClasses.get(0).reason);
        assertEquals(6_000L, immature.outingClasses.get(0).t0);
        assertEquals(FairnessFieldContract.NO_MATURE_SAMPLE, immature.maturity);
        assertNull(immature.matureCount);
        assertNull(immature.denominator);

        Store close = approvedStore();
        CityFairnessIngestionService ingestion = new CityFairnessIngestionService(close);
        ingestion.ingest(task("city-task", "e1", "o1", 7, 6_000));
        ingestion.ingest(task("city-task", "e2", "o2", 7, 6_050));
        CityFairnessReportService.Page counted = scoped(new CityFairnessReportService(close), 7, "gap", 12_000L, null);
        assertEquals(FairnessFieldContract.COUNTED, counted.maturity);
        assertEquals(FairnessFieldContract.NO_SECOND_OUTING, counted.outingClasses.get(0).reason);
        assertEquals(0, counted.matureCount);
        assertEquals(1, counted.denominator);
        assertTrue(counted.secondOutings.isEmpty());
    }

    @Test void revokeAfterASnapshotLeavesTheHistoricalQueryAndHidesTheFactLater() {
        Store store = approvedStore();
        CityFairnessIngestionService ingestion = new CityFairnessIngestionService(store);
        ingestion.ingest(task("city-task", "e1", "o1", 7, 6_000));
        CityFairnessReportService reports = new CityFairnessReportService(store);
        CityFairnessReportService.Page first = scoped(reports, 7, "q1", 12_000L, null);
        assertEquals(1, first.denominator);
        CityFairnessReader.EventRow revoke = mark("city-task", "rv", null, REVOKE, 7, 6_000);
        revoke.knownAt = 13_000L;
        revoke.subjectSource = "city-task";
        revoke.subjectEventId = "e1";
        assertEquals("STORED", ingestion.ingest(revoke).status);
        CityFairnessReportService.Page replay = scoped(reports, 7, "q1", 12_000L, null);
        assertTrue(replay.readBack);
        assertEquals(1, replay.denominator);
        CityFairnessReportService.Page sameInstant = scoped(reports, 7, "q2", 12_000L, null);
        assertEquals(1, sameInstant.denominator);
        assertFalse(sameInstant.readBack);
        CityFairnessReportService.Page later = scoped(reports, 7, "q3", 13_000L, null);
        assertEquals(FairnessFieldContract.NO_MATURE_SAMPLE, later.maturity);
        assertNull(later.matureCount);
        assertNull(later.denominator);
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
        CityFairnessIngestionService ingestion = new CityFairnessIngestionService(store);
        ingestion.ingest(task("city-task", "a", "oa", 7, 6_000));
        ingestion.ingest(task("city-task", "b", "ob", 7, 6_200));
        CityFairnessReportService reports = new CityFairnessReportService(store);
        CityFairnessReportController stranger = new CityFairnessReportController(reports) {
            protected Long currentAccount() { return null; }
        };
        ResponseEntity<AjaxResult> denied = stranger.report("c1", 12_000L, null, "board-1", "region-1", "season-1", "rules-1");
        assertEquals(401, denied.getStatusCode().value());
        CityFairnessReportController owner = new CityFairnessReportController(reports) {
            protected Long currentAccount() { return 7L; }
        };
        ResponseEntity<AjaxResult> allowed = owner.report("c1", 12_000L, null, "board-1", "region-1", "season-1", "rules-1");
        assertEquals(200, allowed.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) allowed.getBody().get(AjaxResult.DATA_TAG);
        assertEquals(7L, data.get("accountId"));
        assertEquals(12_000L, data.get("asOf"));
        assertEquals("metric-1", data.get("metricVersion"));
        assertEquals(FairnessFieldContract.FORMULA_ID, data.get("formulaId"));
        assertEquals(1, data.get("matureCount"));
        assertEquals(1, data.get("denominator"));
        assertEquals(Boolean.FALSE, data.get("readBack"));
        assertNotNull(data.get("members"));
    }

    @Test void outingDetailIsOnlyTheCallerAndPagesOneMergedOuting() {
        Store store = approvedStore();
        store.parameter.pageSize = 1;
        CityFairnessIngestionService ingestion = new CityFairnessIngestionService(store);
        ingestion.ingest(task("city-task", "e1", "o1", 7, 6_000));
        ingestion.ingest(task("city-task", "e1b", "o1", 7, 6_100));
        ingestion.ingest(task("city-task", "e2", "o2", 7, 6_200));
        ingestion.ingest(task("city-task", "eb", "ob", 8, 6_000));
        CityFairnessReportService reports = new CityFairnessReportService(store);

        CityFairnessReportService.Page first = outings(reports, 7, "oq", 12_000L, null);
        assertEquals(200, first.httpStatus);
        assertEquals(1, first.outings.size());
        assertEquals("o1", first.outings.get(0).outingId);
        assertTrue(first.outings.get(0).valid);
        assertEquals(FairnessFieldContract.FIRST_OUTING, first.outings.get(0).reason);
        assertFalse(first.outings.get(0).inWindow);
        assertEquals(6_000L, first.t0);
        assertEquals(6_000L, first.outings.get(0).t0);
        assertEquals(FairnessFieldContract.MATURE, first.maturity);
        assertEquals(FairnessFieldContract.MATURE, first.outings.get(0).maturity);
        assertEquals("metric-1", first.metricVersion);
        assertEquals(6_200L, first.factRevision);
        assertEquals("1", first.nextCursor);
        assertFalse(first.readBack);
        assertTrue(first.groups.isEmpty());

        CityFairnessReportService.Page second = outings(reports, 7, "oq", 12_000L, "1");
        assertTrue(second.readBack);
        assertEquals("o2", second.outings.get(0).outingId);
        assertTrue(second.outings.get(0).inWindow);
        assertEquals(FairnessFieldContract.SECOND_OUTING, second.outings.get(0).reason);
        assertNull(second.nextCursor);

        CityFairnessReportService.Page other = outings(reports, 8, "oq", 12_000L, null);
        assertFalse(other.readBack);
        assertEquals(1, other.outings.size());
        assertEquals("ob", other.outings.get(0).outingId);
        assertEquals(FairnessFieldContract.FIRST_OUTING, other.outings.get(0).reason);

        ingestion.ingest(task("city-task", "e3", "o3", 7, 6_400));
        CityFairnessReportService.Page replay = outings(reports, 7, "oq", 12_000L, null);
        assertTrue(replay.readBack);
        assertEquals("o1", replay.outings.get(0).outingId);
        assertEquals("1", replay.nextCursor);
        assertEquals(6_200L, replay.factRevision);

        CityFairnessReportService.Page immature = outings(reports, 7, "early", 7_000L, null);
        assertEquals(FairnessFieldContract.IMMATURE, immature.maturity);
        assertEquals(6_000L, immature.t0);
        assertEquals(FairnessFieldContract.FIRST_OUTING, immature.outings.get(0).reason);
        CityFairnessReportService.Page immatureNext = outings(reports, 7, "early", 7_000L, "1");
        assertEquals("o2", immatureNext.outings.get(0).outingId);
        assertTrue(immatureNext.outings.get(0).inWindow);
        assertEquals(FairnessFieldContract.IMMATURE, immatureNext.outings.get(0).maturity);
        CityFairnessReportService.Page cohort = scoped(reports, 7, "early-report", 7_000L, null);
        assertEquals(FairnessFieldContract.NO_MATURE_SAMPLE, cohort.maturity);
        assertNull(cohort.matureCount);
        assertNull(cohort.denominator);

        CityFairnessReportService.Page rejected = reports.outings(7, "bad-board", 12_000L, null, "board-9", "region-1", "season-1", "rules-1");
        assertEquals(403, rejected.httpStatus);
        assertEquals("scope-rejected", rejected.errorCode);
        assertTrue(rejected.outings.isEmpty());

        Store broken = new Store();
        broken.parametersDown = true;
        CityFairnessReportService.Page unavailable = new CityFairnessReportService(broken)
                .outings(7, "down", 12_000L, null, "board-1", "region-1", "season-1", "rules-1");
        assertEquals(503, unavailable.httpStatus);
        assertEquals("city_fairness_parameter", unavailable.errorCode);
        assertTrue(broken.reads.isEmpty());

        CityFairnessReportController stranger = new CityFairnessReportController(reports) {
            protected Long currentAccount() { return null; }
        };
        ResponseEntity<AjaxResult> denied = stranger.outing("c-out", 12_000L, null, "board-1", "region-1", "season-1", "rules-1");
        assertEquals(401, denied.getStatusCode().value());
        CityFairnessReportController owner = new CityFairnessReportController(reports) {
            protected Long currentAccount() { return 7L; }
        };
        ResponseEntity<AjaxResult> allowed = owner.outing("c-out", 12_000L, null, "board-1", "region-1", "season-1", "rules-1");
        assertEquals(200, allowed.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) allowed.getBody().get(AjaxResult.DATA_TAG);
        assertEquals(7L, data.get("accountId"));
        assertEquals(6_000L, data.get("t0"));
        assertEquals("metric-1", data.get("metricVersion"));
        assertEquals(6_400L, data.get("factRevision"));
        assertNull(data.get("groups"));
        assertNull(data.get("members"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("outings");
        assertEquals(1, rows.size());
        assertEquals("o1", rows.get(0).get("outingId"));
        assertEquals(Boolean.TRUE, rows.get(0).get("valid"));
        assertEquals(FairnessFieldContract.FIRST_OUTING, rows.get(0).get("reason"));
        assertEquals(Boolean.FALSE, rows.get(0).get("inWindow"));
        assertEquals(FairnessFieldContract.MATURE, rows.get(0).get("maturity"));
        assertEquals("metric-1", rows.get(0).get("metricVersion"));
        assertEquals(6_400L, rows.get(0).get("factRevision"));

        store.parameter.graceMillis = null;
        CityFairnessReportService.Page missing = outings(reports, 7, "nograce", 12_000L, null);
        assertEquals(CityFairnessReader.NOT_COMPUTABLE, missing.status);
        assertTrue(missing.missing.contains(GRACE_MILLIS));
        assertNull(missing.matureCount);
        ResponseEntity<AjaxResult> gap = owner.outing("nograce-http", 12_000L, null, "board-1", "region-1", "season-1", "rules-1");
        assertEquals(422, gap.getStatusCode().value());
    }

    @Test void selfPagesCoverageAndAReadFailureStayOnTheOriginalQuery() {
        Store store = approvedStore();
        store.parameter.pageSize = 1;
        store.ignoreActor = true;
        CityFairnessIngestionService ingestion = new CityFairnessIngestionService(store);
        ingestion.ingest(task("city-task", "e1", "o1", 7, 6_000));
        ingestion.ingest(task("city-task", "e2", "o2", 7, 6_200));
        CityFairnessReader.EventRow stranger = task("city-task", "eb", "ob", 8, 6_000);
        stranger.knownAt = 9_000L;
        stranger.teamSize = 9;
        ingestion.ingest(stranger);
        CityFairnessReportService reports = new CityFairnessReportService(store);

        CityFairnessReportService.Page report = scoped(reports, 7, "q-self", 12_000L, null);
        assertNull(report.cursor);
        assertNull(report.nextCursor);
        assertEquals(1, report.matureCount);
        assertEquals(1, report.denominator);
        assertEquals(1, report.groups.size());
        assertEquals(2, report.groups.get(0).teamSize);
        assertEquals(0, report.groups.get(0).goalCompletions);
        assertEquals(0, report.groups.get(0).gpsFalseRejections);
        assertEquals(6_200L, report.factRevision);
        assertEquals(1, report.outingClasses.size());
        assertEquals(7L, report.outingClasses.get(0).actorId);
        assertTrue(report.coverageGaps.contains("gps-false-rejections"));
        assertTrue(report.coverageGaps.contains("revenue"));
        assertFalse(report.coverageGaps.contains("headcount"));
        assertNull(scoped(reports, 7, "q-self", 12_000L, "0").cursor);
        assertTrue(scoped(reports, 7, "q-self", 12_000L, "0").readBack);
        assertEquals(400, scoped(reports, 7, "q-self", 12_000L, "nil").httpStatus);
        assertEquals("cursor", scoped(reports, 7, "q-self", 12_000L, "nil").errorCode);

        CityFairnessReportService.Page first = outings(reports, 7, "q-self", 12_000L, null);
        assertNull(first.cursor);
        assertEquals("1", first.nextCursor);
        assertEquals("o1", first.outings.get(0).outingId);
        assertTrue(first.readBack);
        CityFairnessReportService.Page next = outings(reports, 7, "q-self", 12_000L, first.nextCursor);
        assertEquals("1", next.cursor);
        assertNull(next.nextCursor);
        assertTrue(next.readBack);
        assertEquals("o2", next.outings.get(0).outingId);
        assertTrue(next.outings.get(0).inWindow);

        CityFairnessReportService.Page immature = scoped(reports, 7, "q-empty", 7_000L, null);
        assertEquals(FairnessFieldContract.NO_MATURE_SAMPLE, immature.maturity);
        assertNull(immature.matureCount);
        assertNull(immature.denominator);
        assertNull(immature.cursor);
        assertEquals(0, immature.groups.get(0).goalCompletions);
        assertTrue(immature.coverageGaps.contains("safety-tickets"));

        ingestion.ingest(mark("city-task", "gps", "o1", GPS_FALSE_REJECTION, 7, 6_300));
        CityFairnessReportService.Page same = scoped(reports, 7, "q-self", 12_000L, null);
        assertTrue(same.readBack);
        assertEquals(6_200L, same.factRevision);
        assertTrue(same.coverageGaps.contains("gps-false-rejections"));
        assertEquals(1, same.matureCount);
        CityFairnessReportService.Page recounted = scoped(reports, 7, "q-later", 12_000L, null);
        assertFalse(recounted.coverageGaps.contains("gps-false-rejections"));
        assertEquals(1, recounted.groups.get(0).gpsFalseRejections);
        assertEquals(0, recounted.groups.get(0).goalCompletions);

        String payload = store.reads.get("7:q-self:board-1:region-1:season-1:rules-1").payload;
        store.failReads = true;
        CityFairnessReportService.Page down = scoped(reports, 7, "q-self", 12_000L, null);
        assertEquals(503, down.httpStatus);
        assertEquals("unavailable", down.errorCode);
        assertEquals("q-self", down.queryId);
        assertEquals(7L, down.accountId);
        assertNull(down.status);
        assertNull(down.matureCount);
        assertEquals(payload, store.reads.get("7:q-self:board-1:region-1:season-1:rules-1").payload);
        store.skipNextRead = true;
        CityFairnessReportService.Page conflict = scoped(reports, 7, "q-self", 12_000L, null);
        assertEquals(503, conflict.httpStatus);
        assertEquals("unavailable", conflict.errorCode);
        assertEquals(payload, store.reads.get("7:q-self:board-1:region-1:season-1:rules-1").payload);
        store.failReads = false;
        CityFairnessReportService.Page restored = scoped(reports, 7, "q-self", 12_000L, null);
        assertTrue(restored.readBack);
        assertEquals(1, restored.matureCount);
        assertEquals(6_200L, restored.factRevision);

        Store fresh = approvedStore();
        fresh.failReads = true;
        CityFairnessReportService.Page missingTable = scoped(new CityFairnessReportService(fresh), 7, "missing-table", 12_000L, null);
        assertEquals(503, missingTable.httpStatus);
        assertEquals("unavailable", missingTable.errorCode);
        assertEquals("missing-table", missingTable.queryId);
        assertNull(missingTable.status);
        assertTrue(fresh.reads.isEmpty());

        CityFairnessReportController owner = new CityFairnessReportController(reports) {
            protected Long currentAccount() { return 7L; }
        };
        ResponseEntity<AjaxResult> firstPage = owner.report("q-self", 12_000L, null, "board-1", "region-1", "season-1", "rules-1");
        assertEquals(200, firstPage.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) firstPage.getBody().get(AjaxResult.DATA_TAG);
        assertNull(data.get("cursor"));
        @SuppressWarnings("unchecked")
        Map<String, Object> group = ((List<Map<String, Object>>) data.get("groups")).get(0);
        assertEquals(0, group.get("goalCompletions"));
        assertNull(group.get("gpsFalseRejections"));
        assertNull(group.get("effectiveGain"));
        assertEquals(2, group.get("teamSize"));
        ResponseEntity<AjaxResult> outingPage = owner.outing("q-self", 12_000L, null, "board-1", "region-1", "season-1", "rules-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> outing = (Map<String, Object>) outingPage.getBody().get(AjaxResult.DATA_TAG);
        assertEquals("1", outing.get("nextCursor"));
        assertNull(outing.get("cursor"));
        ResponseEntity<AjaxResult> outingNext = owner.outing("q-self", 12_000L, "1", "board-1", "region-1", "season-1", "rules-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> second = (Map<String, Object>) outingNext.getBody().get(AjaxResult.DATA_TAG);
        assertEquals("1", second.get("cursor"));
        assertEquals(Boolean.TRUE, second.get("readBack"));
        @SuppressWarnings("unchecked")
        Map<String, Object> row = ((List<Map<String, Object>>) second.get("outings")).get(0);
        assertEquals("o2", row.get("outingId"));
        store.failReads = true;
        ResponseEntity<AjaxResult> failed = owner.report("q-self", 12_000L, null, "board-1", "region-1", "season-1", "rules-1");
        assertEquals(503, failed.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) failed.getBody().get(AjaxResult.DATA_TAG);
        assertEquals("unavailable", error.get("errorCode"));
        assertEquals("q-self", error.get("queryId"));
        assertNull(error.get("status"));
        assertNull(error.get("matureCount"));
        assertNull(error.get("denominator"));
    }

    private static CityFairnessReportService.Page outings(CityFairnessReportService reports, long account, String query,
                                                           Long asOf, String cursor) {
        return reports.outings(account, query, asOf, cursor, "board-1", "region-1", "season-1", "rules-1");
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
        row.mergeWindowMillis = 100_000L;
        row.stateRule = FairnessFieldContract.STATE_RULE;
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
        row.knownAt = at;
        row.boardId = "board-1";
        row.regionId = "region-1";
        row.seasonId = "season-1";
        row.rulesVersion = "rules-1";
        return row;
    }

    private static final class Store implements CityFairnessMapper {
        CityFairnessReader.ParameterRow parameter;
        boolean parametersDown;
        boolean ignoreActor;
        boolean failReads;
        boolean skipNextRead;
        final List<CityFairnessReader.ParameterRow> parameters = new ArrayList<>();
        final List<CityFairnessReader.EventRow> events = new ArrayList<>();
        final Map<String, CityFairnessReadRow> reads = new LinkedHashMap<>();

        public List<CityFairnessReader.ParameterRow> selectParameters(String boardId, String regionId, String seasonId, String rulesVersion) {
            if (parametersDown) throw new IllegalStateException("down");
            List<CityFairnessReader.ParameterRow> out = new ArrayList<>();
            for (CityFairnessReader.ParameterRow row : parameters) {
                if (boardId.equals(row.boardId) && regionId.equals(row.regionId) && seasonId.equals(row.seasonId)
                        && rulesVersion.equals(row.rulesVersion)) out.add(row);
            }
            return out;
        }

        public List<CityFairnessReader.EventRow> selectEvents(String boardId, String regionId, String seasonId,
                                                               String rulesVersion, Long actorId, long asOf, int limit) {
            List<CityFairnessReader.EventRow> out = new ArrayList<>();
            for (CityFairnessReader.EventRow event : events) {
                if (!ignoreActor && (actorId == null || !actorId.equals(event.actorId))) continue;
                if (boardId.equals(event.boardId) && regionId.equals(event.regionId)
                        && seasonId.equals(event.seasonId) && rulesVersion.equals(event.rulesVersion)
                        && event.knownAt != null && event.knownAt <= asOf && event.occurredAt <= asOf) out.add(event);
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
            copy.knownAt = row.knownAt;
            copy.subjectSource = row.subjectSource;
            copy.subjectEventId = row.subjectEventId;
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
            if (skipNextRead) {
                skipNextRead = false;
                return null;
            }
            if (failReads) throw new IllegalStateException("city_fairness_read");
            return reads.get(accountId + ":" + queryId + ":" + boardId + ":" + regionId + ":" + seasonId + ":" + rulesVersion);
        }
    }
}
