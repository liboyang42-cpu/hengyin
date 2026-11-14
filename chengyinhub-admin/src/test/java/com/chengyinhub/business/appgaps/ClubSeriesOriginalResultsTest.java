package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.appgaps.mapper.ClubSeriesOriginalResultMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClubSeriesOriginalResultsTest {
    static final Set<String> APPLIED_KEYS = Set.of("version", "actorMemberId", "clubId", "topicId", "requestId", "state",
            "wire", "series", "effectiveDates", "startTime", "occurrences", "receiptHash");

    ClubSeriesOriginalResultMapper storing(Map<String, Object> captured) {
        ClubSeriesOriginalResultMapper m = mock(ClubSeriesOriginalResultMapper.class);
        when(m.insert(any())).thenAnswer(a -> { captured.putAll(a.getArgument(0)); return 1; });
        when(m.selectByRequest(anyLong(), anyLong(), anyString())).thenAnswer(a ->
                captured.isEmpty() || !a.getArgument(2).equals(captured.get("requestId")) || !a.getArgument(1).equals(captured.get("actor")) ? null : captured);
        return m;
    }

    @SuppressWarnings("unchecked")
    @Test void appliedResultHasExactAppKeySetAndFrozenFields() {
        Map<String, Object> row = new HashMap<>();
        ClubSeriesOriginalResults r = new ClubSeriesOriginalResults(storing(row));
        ClubSeriesOriginalResultsFixture f = new ClubSeriesOriginalResultsFixture(11, 22, 33, 44, " req-1 ");
        r.record(f.series, f.occurrences, f.dates, f.request);
        Map<String, Object> d = (Map<String, Object>) r.read(33, f.fields()).get("data");
        assertEquals(APPLIED_KEYS, d.keySet());
        assertEquals("APPLIED", d.get("state")); assertEquals("req-1", d.get("requestId"), "Java trim 后的 requestId");
        Map<String, Object> wire = (Map<String, Object>) d.get("wire");
        assertEquals(15, wire.size()); assertNull(wire.get("rrule")); assertEquals(" req-1 ", wire.get("requestId"), "原线格式不归一化");
        List<Map<String, Object>> occ = (List<Map<String, Object>>) d.get("occurrences");
        assertEquals(2, occ.size()); assertEquals(List.of("2026-12-05", "2026-12-12"), d.get("effectiveDates"));
        assertEquals(((Map<String, Object>) d.get("series")).get("createdAtEpochMs"), occ.get(0).get("createdAtEpochMs"));
        assertEquals(1796470200000L, ((Number) occ.get(0).get("occurrenceAtEpochMs")).longValue(), "2026-12-05 19:30 上海");
        assertTrue(((String) d.get("receiptHash")).matches("[0-9a-f]{64}"));
        Map<String, Object> withoutHash = new HashMap<>(d); withoutHash.remove("receiptHash");
        assertEquals(d.get("receiptHash"), AppOriginalRequestLedger.sha256Hex(ClubSeriesOriginalResults.canonical(withoutHash)));
    }

    @SuppressWarnings("unchecked")
    @Test void changedWireIsConflictAndMissingIsUnknownIdentityOnly() {
        Map<String, Object> row = new HashMap<>();
        ClubSeriesOriginalResults r = new ClubSeriesOriginalResults(storing(row));
        ClubSeriesOriginalResultsFixture f = new ClubSeriesOriginalResultsFixture(11, 22, 33, 44, "req-2");
        Map<String, Object> unknown = (Map<String, Object>) r.read(33, f.fields()).get("data");
        assertEquals(Set.of("version", "actorMemberId", "clubId", "topicId", "requestId", "state"), unknown.keySet());
        assertEquals("UNKNOWN", unknown.get("state"));
        r.record(f.series, f.occurrences, f.dates, f.request);
        Map<String, Object> changed = f.fields(); changed.put("offerMinutes", 90);
        assertEquals(409, r.read(33, changed).get("code"));
        assertEquals("UNKNOWN", ((Map<String, Object>) r.read(34, f.fields()).get("data")).get("state"), "他人读不到");
        Map<String, Object> extra = f.fields(); extra.put("token", "x");
        assertEquals(400, r.read(33, extra).get("code"));
    }
}
