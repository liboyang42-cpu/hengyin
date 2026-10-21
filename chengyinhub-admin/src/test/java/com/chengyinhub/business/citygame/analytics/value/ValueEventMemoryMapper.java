package com.chengyinhub.business.citygame.analytics.value;

import com.chengyinhub.business.citygame.analytics.value.mapper.ValueEventMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 本地隔离验收用的价值事件映射，不连接数据库。 */
public class ValueEventMemoryMapper implements ValueEventMapper {
    private final Map<Long, ValueEventRow> events = new ConcurrentHashMap<Long, ValueEventRow>();
    private final Map<String, Long> dedupe = new ConcurrentHashMap<String, Long>();
    private final Map<Long, ValueReportRow> reports = new ConcurrentHashMap<Long, ValueReportRow>();
    private final Map<String, Long> reportKeys = new ConcurrentHashMap<String, Long>();
    private final Map<String, ValueRequestRow> requests = new ConcurrentHashMap<String, ValueRequestRow>();

    @Override
    public synchronized int insertEvent(ValueEventRow row) {
        if (row.getId() == null || row.getDedupeKey() == null || events.containsKey(row.getId()) || dedupe.containsKey(row.getDedupeKey())) {
            return 0;
        }
        events.put(row.getId(), row.copy());
        dedupe.put(row.getDedupeKey(), row.getId());
        return 1;
    }

    @Override
    public synchronized ValueEventRow selectByDedupe(String dedupeKey) {
        Long id = dedupe.get(dedupeKey);
        ValueEventRow row = id == null ? null : events.get(id);
        return row == null ? null : row.copy();
    }

    @Override
    public synchronized List<ValueEventRow> selectVisits(String regionId, String boardId, String seasonId, String rulesReleaseId,
            Date from, Date to, Date cursorAt, Long cursorId, int limit) {
        List<ValueEventRow> matched = new ArrayList<ValueEventRow>();
        for (ValueEventRow row : events.values()) {
            if (!regionId.equals(row.getRegionId()) || !boardId.equals(row.getBoardId()) || !seasonId.equals(row.getSeasonId())
                    || !rulesReleaseId.equals(row.getRulesReleaseId()) || ValueKinds.DEFINITION.equals(row.getKind())
                    || ValueKinds.COVERAGE.equals(row.getKind())) {
                continue;
            }
            long at = row.getOccurredAt().getTime();
            if (at < from.getTime() || at >= to.getTime()) {
                continue;
            }
            if (cursorAt != null && (at < cursorAt.getTime() || (at == cursorAt.getTime() && row.getId() <= cursorId))) {
                continue;
            }
            matched.add(row.copy());
        }
        matched.sort(Comparator.comparingLong((ValueEventRow row) -> row.getOccurredAt().getTime()).thenComparingLong(ValueEventRow::getId));
        return matched.size() <= limit ? matched : new ArrayList<ValueEventRow>(matched.subList(0, limit));
    }

    @Override
    public synchronized List<ValueEventRow> selectFacts(String regionId, String boardId, String seasonId, String rulesReleaseId, int limit) {
        List<ValueEventRow> matched = new ArrayList<ValueEventRow>();
        for (ValueEventRow row : events.values()) {
            if (regionId.equals(row.getRegionId()) && boardId.equals(row.getBoardId()) && seasonId.equals(row.getSeasonId())
                    && rulesReleaseId.equals(row.getRulesReleaseId())
                    && (ValueKinds.DEFINITION.equals(row.getKind()) || ValueKinds.COVERAGE.equals(row.getKind()))) {
                matched.add(row.copy());
            }
        }
        matched.sort(Comparator.comparingLong(ValueEventRow::getId));
        return matched.size() <= limit ? matched : new ArrayList<ValueEventRow>(matched.subList(0, limit));
    }

    @Override
    public synchronized int insertReport(ValueReportRow row) {
        if (row.getId() == null || row.getResultKey() == null || reports.containsKey(row.getId()) || reportKeys.containsKey(row.getResultKey())) {
            return 0;
        }
        reports.put(row.getId(), row.copy());
        reportKeys.put(row.getResultKey(), row.getId());
        return 1;
    }

    @Override
    public synchronized ValueReportRow selectReportByKey(String resultKey) {
        Long id = reportKeys.get(resultKey);
        ValueReportRow row = id == null ? null : reports.get(id);
        return row == null ? null : row.copy();
    }

    @Override
    public synchronized ValueReportRow selectReportById(long id) {
        ValueReportRow row = reports.get(id);
        return row == null ? null : row.copy();
    }

    @Override
    public synchronized int insertRequest(ValueRequestRow row) {
        if (row.getRequestId() == null || requests.containsKey(row.getRequestId())) {
            return 0;
        }
        requests.put(row.getRequestId(), row.copy());
        return 1;
    }

    @Override
    public synchronized ValueRequestRow selectRequest(String requestId) {
        ValueRequestRow row = requests.get(requestId);
        return row == null ? null : row.copy();
    }

    public synchronized int eventCount() {
        return events.size();
    }

    public synchronized int behaviorCount() {
        int count = 0;
        for (ValueEventRow row : events.values()) {
            if (!ValueKinds.DEFINITION.equals(row.getKind()) && !ValueKinds.COVERAGE.equals(row.getKind())) {
                count++;
            }
        }
        return count;
    }

    public synchronized int requestCount() {
        return requests.size();
    }

    public synchronized int reportCount() {
        return reports.size();
    }

    public synchronized List<ValueEventRow> rows() {
        List<ValueEventRow> copies = new ArrayList<ValueEventRow>();
        for (ValueEventRow row : events.values()) {
            copies.add(row.copy());
        }
        return copies;
    }
}
