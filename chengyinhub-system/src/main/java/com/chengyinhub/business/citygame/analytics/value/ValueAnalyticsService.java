package com.chengyinhub.business.citygame.analytics.value;

import com.chengyinhub.business.cityboard.CityBoardDraftDocument;
import com.chengyinhub.business.cityboard.CityBoardHashes;
import com.chengyinhub.business.citygame.analytics.value.mapper.ValueEventMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 只读价值统计。每次报告先调用发布适配器，再按范围读取、去重并落报告。
 * 进程内序号只用于本进程。表迁到数据库后改用库序列，请求号才是幂等边界。
 */
@Service
public class ValueAnalyticsService {
    private static final int FACT_CAP = 50;

    private final ValueEventMapper events;
    private final CityValueReleaseCaller caller;
    private final Clock clock;
    private final AtomicLong ids = new AtomicLong(8_100_000L);

    @Autowired
    public ValueAnalyticsService(com.chengyinhub.business.cityboard.mapper.CityBoardMapper boards, ValueEventMapper events,
            CityValueReleaseCaller caller, ValueEventIngestor ingestor) {
        this(events, caller, Clock.systemUTC());
        if (boards == null || ingestor == null || caller == null) {
            throw new IllegalArgumentException("value report wiring is incomplete");
        }
    }

    public ValueAnalyticsService(ValueEventMapper events, CityValueReleaseCaller caller, Clock clock) {
        this.events = events;
        this.caller = caller;
        this.clock = clock;
    }

    public ValueOutcome query(long actorId, ValueContracts.Query query) {
        if (actorId <= 0) {
            return unauthenticated();
        }
        if (query == null || !CityBoardDraftDocument.isId(query.requestId)) {
            return ValueOutcome.fail(400, "MISSING_PARAM", "缺少请求号", envelope("MISSING_PARAM"));
        }
        ValueRequestRow existing = events.selectRequest(query.requestId);
        if (existing != null && !actorIdEquals(existing, actorId)) {
            return accountChanged();
        }
        String problem = structural(query);
        if (problem != null) {
            if ("NOT_MEANINGFUL".equals(problem) && existing == null) {
                return ValueOutcome.fail(400, "NOT_MEANINGFUL", "点击、走路、登录不能当作有意义动作", envelope("NOT_MEANINGFUL"));
            }
            String hash = CityBoardHashes.sha256("reject\n" + problem + "\n" + canonical(query));
            if (existing != null) {
                return same(existing, hash);
            }
            return store(actorId, query, hash, httpOf(problem), problem, message(problem), problem, envelope(problem), false);
        }
        ValueOutcome pulled = caller.pull(query.regionId);
        if (!"ADAPTED".equals(pulled.data.get("status"))) {
            String status = String.valueOf(pulled.data.get("status"));
            String hash = CityBoardHashes.sha256("board\n" + status + "\n" + canonical(query));
            if (existing != null) {
                return same(existing, hash);
            }
            Map<String, Object> data = envelope(status);
            data.put("regionId", query.regionId);
            int http = pulled.httpStatus == 200 ? 200 : pulled.httpStatus;
            return store(actorId, query, hash, http, pulled.errorCode, pulled.message, status, data, false);
        }
        if (!query.boardId.equals(pulled.data.get("boardId")) || !query.seasonId.equals(pulled.data.get("seasonId"))
                || !query.rulesReleaseId.equals(pulled.data.get("rulesReleaseId"))) {
            String hash = CityBoardHashes.sha256("scope\n" + canonical(query) + "\n" + pulled.data.get("rulesReleaseId"));
            if (existing != null) {
                return same(existing, hash);
            }
            return store(actorId, query, hash, 409, "SCOPE_MISMATCH", "范围与当前发布不一致", "SCOPE_MISMATCH",
                    envelope("SCOPE_MISMATCH"), false);
        }
        boolean cohort = query.definition != null && query.definition.veteranMinPriorServiceSuccess != null;
        Read visits = read(query, query.from, query.to, query.limit);
        Read facts = readFacts(query);
        Read prior = cohort ? read(query, query.priorFrom, query.priorTo, query.limit) : Read.empty();
        boolean truncated = visits.truncated || facts.truncated || prior.truncated;
        String hash = CityBoardHashes.sha256(canonical(query) + "\n" + visits.truncated + "\n" + facts.truncated + "\n"
                + prior.truncated + "\n" + hashes(facts.rows, visits.rows, prior.rows));
        if (existing != null) {
            return same(existing, hash);
        }
        Map<String, Object> data = envelope(truncated ? "INCOMPLETE" : "REPORTED");
        data.put("boardId", query.boardId);
        data.put("from", query.from.toString());
        data.put("regionId", query.regionId);
        data.put("rulesReleaseId", query.rulesReleaseId);
        data.put("seasonId", query.seasonId);
        data.put("to", query.to.toString());
        if (truncated) {
            data.put("factsTruncated", facts.truncated);
            data.put("limit", query.limit);
            data.put("priorTruncated", prior.truncated);
            data.put("read", visits.rows.size());
            if (visits.truncated && !visits.rows.isEmpty()) {
                data.put("resumeFrom", cursor(actorId, page(query), visits.rows.get(visits.rows.size() - 1)));
            }
            return store(actorId, query, hash, 200, null, "已读取", "INCOMPLETE", data, true);
        }
        data.putAll(ValueReports.compute(facts.rows, visits.rows, prior.rows, query.definition, cohort));
        return store(actorId, query, hash, 200, null, "已读取", "REPORTED", data, true);
    }

    public ValueOutcome page(long actorId, ValueContracts.Page page) {
        if (actorId <= 0) {
            return unauthenticated();
        }
        String cursor = page == null || page.cursor == null ? "" : page.cursor;
        if (page == null || !CityBoardDraftDocument.isId(page.regionId) || !CityBoardDraftDocument.isId(page.boardId)
                || !CityBoardDraftDocument.isId(page.seasonId) || !CityBoardDraftDocument.isId(page.rulesReleaseId)
                || page.from == null || page.to == null || !window(page.from, page.to) || page.limit < 1 || page.limit > 50) {
            return pageFailure(400, page != null && page.from != null && page.to != null && !window(page.from, page.to)
                    ? "INVALID_WINDOW" : "MISSING_PARAM", cursor);
        }
        ValueOutcome pulled = caller.pull(page.regionId);
        if (!"ADAPTED".equals(pulled.data.get("status"))) {
            ValueOutcome outcome = pageFailure(pulled.httpStatus == 200 ? 200 : pulled.httpStatus,
                    String.valueOf(pulled.data.get("status")), cursor);
            outcome.data.put("regionId", page.regionId);
            return outcome;
        }
        if (!page.boardId.equals(pulled.data.get("boardId")) || !page.seasonId.equals(pulled.data.get("seasonId"))
                || !page.rulesReleaseId.equals(pulled.data.get("rulesReleaseId"))) {
            return pageFailure(409, "SCOPE_MISMATCH", cursor);
        }
        ParsedCursor parsed = parse(cursor, actorId, page);
        if (parsed.accountChanged) {
            ValueOutcome changed = accountChanged();
            changed.data.put("retryCursor", cursor);
            return changed;
        }
        if (parsed.error != null) {
            return pageFailure(parsed.http, parsed.error, cursor);
        }
        List<ValueEventRow> rows = events.selectVisits(page.regionId, page.boardId, page.seasonId, page.rulesReleaseId,
                Date.from(page.from), Date.from(page.to), parsed.at, parsed.id, page.limit + 1);
        boolean more = rows.size() > page.limit;
        List<ValueEventRow> included = more ? rows.subList(0, page.limit) : rows;
        Map<String, Object> data = envelope("PAGE");
        data.put("events", views(included));
        data.put("regionId", page.regionId);
        data.put("retryCursor", cursor);
        data.put("rulesReleaseId", page.rulesReleaseId);
        if (more) {
            data.put("nextCursor", cursor(actorId, page, included.get(included.size() - 1)));
        }
        return ValueOutcome.ok(data);
    }

    public ValueOutcome readResult(long actorId, long resultId, String regionId, String rulesReleaseId) {
        if (actorId <= 0) {
            return unauthenticated();
        }
        if (resultId <= 0 || !CityBoardDraftDocument.isId(regionId) || !CityBoardDraftDocument.isId(rulesReleaseId)) {
            return ValueOutcome.fail(400, "MISSING_PARAM", "缺少回读范围", envelope("MISSING_PARAM"));
        }
        ValueReportRow row = events.selectReportById(resultId);
        if (row == null) {
            return ValueOutcome.fail(404, "NOT_FOUND", "结果不存在", envelope("NOT_FOUND"));
        }
        if (!regionId.equals(row.getRegionId()) || !rulesReleaseId.equals(row.getRulesReleaseId())) {
            return ValueOutcome.fail(409, "SCOPE_MISMATCH", "范围与结果不一致", envelope("SCOPE_MISMATCH"));
        }
        return ValueOutcome.ok(readMap(row.getBodyJson()));
    }

    public ValueOutcome readRequest(long actorId, String requestId) {
        if (actorId <= 0) {
            return unauthenticated();
        }
        if (!CityBoardDraftDocument.isId(requestId)) {
            return ValueOutcome.fail(400, "MISSING_PARAM", "请求号无效", envelope("MISSING_PARAM"));
        }
        ValueRequestRow row = events.selectRequest(requestId);
        if (row == null) {
            return ValueOutcome.fail(404, "NOT_FOUND", "请求不存在", envelope("NOT_FOUND"));
        }
        if (!actorIdEquals(row, actorId)) {
            return accountChanged();
        }
        return fromRow(row);
    }

    public ValueOutcome cancel(long actorId, String requestId) {
        if (actorId <= 0) {
            return unauthenticated();
        }
        if (!CityBoardDraftDocument.isId(requestId)) {
            return ValueOutcome.fail(400, "MISSING_PARAM", "请求号无效", envelope("MISSING_PARAM"));
        }
        ValueRequestRow existing = events.selectRequest(requestId);
        if (existing != null && !actorIdEquals(existing, actorId)) {
            return accountChanged();
        }
        if (existing == null) {
            Map<String, Object> data = envelope("CANCELLED");
            data.put("requestId", requestId);
            ValueContracts.Query query = new ValueContracts.Query();
            query.requestId = requestId;
            return store(actorId, query, CityBoardHashes.sha256("cancel\n" + requestId), 200, null, "已取消", "CANCELLED", data, false);
        }
        if ("CANCELLED".equals(existing.getStatus())) {
            return fromRow(existing);
        }
        Map<String, Object> data = readMap(existing.getBodyJson());
        data.put("cancelStatus", "CANCEL_REJECTED");
        return ValueOutcome.fail(409, "CANCEL_REJECTED", "结果已落库，不能取消", data);
    }

    private ValueOutcome store(long actorId, ValueContracts.Query query, String hash, int http, String error, String message,
            String status, Map<String, Object> data, boolean report) {
        ValueRequestRow raced = events.selectRequest(query.requestId);
        if (raced != null) {
            return actorIdEquals(raced, actorId) ? same(raced, hash) : accountChanged();
        }
        Long resultId = null;
        String body = writeMap(data);
        if (report) {
            ValueReportRow stored = events.selectReportByKey(hash);
            if (stored == null) {
                ValueReportRow created = new ValueReportRow();
                created.setId(ids.incrementAndGet());
                created.setResultKey(hash);
                created.setRegionId(query.regionId);
                created.setRulesReleaseId(query.rulesReleaseId);
                created.setDefinitionHash(CityBoardHashes.sha256(canonical(query)));
                created.setActorUserId(actorId);
                data.put("resultId", created.getId());
                body = writeMap(data);
                created.setBodyJson(body);
                created.setCreateTime(Date.from(clock.instant()));
                if (events.insertReport(created) != 1) {
                    stored = events.selectReportByKey(hash);
                } else {
                    stored = created;
                }
            }
            if (stored != null && stored.getBodyJson() != null && stored != data) {
                body = stored.getBodyJson();
                data.clear();
                data.putAll(readMap(body));
                resultId = stored.getId();
            } else if (stored != null) {
                resultId = stored.getId();
            }
        }
        ValueRequestRow request = new ValueRequestRow();
        request.setId(ids.incrementAndGet());
        request.setRequestId(query.requestId);
        request.setActorUserId(actorId);
        request.setPayloadHash(hash);
        request.setStatus(status);
        request.setResultId(resultId);
        request.setRegionId(query.regionId);
        request.setRulesReleaseId(query.rulesReleaseId);
        request.setBodyJson(body);
        request.setCreateTime(Date.from(clock.instant()));
        if (events.insertRequest(request) != 1) {
            ValueRequestRow winner = events.selectRequest(query.requestId);
            return winner == null ? ValueOutcome.fail(500, "STORE_FAILED", "请求没有写入", envelope("STORE_FAILED"))
                    : actorIdEquals(winner, actorId) ? same(winner, hash) : accountChanged();
        }
        return http == 200 ? ValueOutcome.ok(data) : ValueOutcome.fail(http, error, message, data);
    }

    private ValueOutcome same(ValueRequestRow existing, String hash) {
        if ("CANCELLED".equals(existing.getStatus()) && !hash.equals(existing.getPayloadHash())) {
            return duplicate(existing);
        }
        if (!hash.equals(existing.getPayloadHash())) {
            return duplicate(existing);
        }
        return fromRow(existing);
    }

    private Read read(ValueContracts.Query query, Instant from, Instant to, int limit) {
        List<ValueEventRow> rows = events.selectVisits(query.regionId, query.boardId, query.seasonId, query.rulesReleaseId,
                Date.from(from), Date.from(to), null, null, limit + 1);
        if (rows.size() <= limit) {
            return new Read(rows, false);
        }
        return new Read(new ArrayList<ValueEventRow>(rows.subList(0, limit)), true);
    }

    private Read readFacts(ValueContracts.Query query) {
        List<ValueEventRow> rows = events.selectFacts(query.regionId, query.boardId, query.seasonId, query.rulesReleaseId,
                FACT_CAP + 1);
        if (rows.size() <= FACT_CAP) {
            return new Read(rows, false);
        }
        return new Read(new ArrayList<ValueEventRow>(rows.subList(0, FACT_CAP)), true);
    }

    private ValueOutcome fromRow(ValueRequestRow row) {
        Map<String, Object> data = readMap(row.getBodyJson());
        int http = httpOf(row.getStatus());
        return http == 200 ? ValueOutcome.ok(data) : ValueOutcome.fail(http, row.getStatus(), message(row.getStatus()), data);
    }

    private ValueOutcome duplicate(ValueRequestRow existing) {
        Map<String, Object> data = envelope("DUPLICATE_REQUEST");
        data.put("requestId", existing.getRequestId());
        if (existing.getResultId() != null) {
            data.put("resultId", existing.getResultId());
        }
        data.put("storedStatus", existing.getStatus());
        return ValueOutcome.fail(409, "DUPLICATE_REQUEST", "请求号已存在且内容不同", data);
    }

    private ValueOutcome accountChanged() {
        return ValueOutcome.fail(409, "ACCOUNT_CHANGED", "请求属于另一个账号", envelope("ACCOUNT_CHANGED"));
    }

    private ValueOutcome unauthenticated() {
        return ValueOutcome.fail(401, "UNAUTHENTICATED", "未登录", envelope("UNAUTHENTICATED"));
    }

    private ValueOutcome pageFailure(int http, String code, String cursor) {
        Map<String, Object> data = envelope(code);
        data.put("retryCursor", cursor);
        return http == 200 ? ValueOutcome.ok(data) : ValueOutcome.fail(http, code, message(code), data);
    }

    private static List<Map<String, Object>> views(List<ValueEventRow> rows) {
        List<Map<String, Object>> views = new ArrayList<Map<String, Object>>();
        for (ValueEventRow row : rows) {
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("actionCode", row.getActionCode());
            view.put("amount", row.getAmount());
            view.put("currency", row.getCurrency());
            view.put("goalId", row.getGoalId());
            view.put("id", row.getId());
            view.put("kind", row.getKind());
            view.put("memberId", row.getMemberId());
            view.put("occurredAt", row.getOccurredAt().toInstant().toString());
            view.put("rating", row.getRating());
            view.put("reasonCode", row.getReasonCode());
            view.put("serviceId", row.getServiceId());
            view.put("unit", row.getUnit());
            view.put("visitId", row.getVisitId());
            views.add(view);
        }
        return views;
    }

    private static String cursor(long actorId, ValueContracts.Page page, ValueEventRow row) {
        return "v1." + actorId + "." + page.regionId + "." + page.rulesReleaseId + "."
                + row.getOccurredAt().getTime() + "." + row.getId() + "." + scopeHash(page);
    }

    private static ParsedCursor parse(String raw, long actorId, ValueContracts.Page page) {
        if (raw == null || raw.isEmpty()) {
            return ParsedCursor.start();
        }
        String[] parts = raw.split("\\.");
        if (parts.length != 7 || !"v1".equals(parts[0])) {
            return ParsedCursor.error(400, "INVALID_CURSOR");
        }
        try {
            long owner = Long.parseLong(parts[1]);
            if (owner != actorId) {
                return ParsedCursor.account();
            }
            if (!page.regionId.equals(parts[2]) || !page.rulesReleaseId.equals(parts[3])
                    || !scopeHash(page).equals(parts[6])) {
                return ParsedCursor.error(409, "SCOPE_MISMATCH");
            }
            return ParsedCursor.position(new Date(Long.parseLong(parts[4])), Long.parseLong(parts[5]));
        } catch (NumberFormatException ex) {
            return ParsedCursor.error(400, "INVALID_CURSOR");
        }
    }

    private static String scopeHash(ValueContracts.Page page) {
        return CityBoardHashes.sha256(page.regionId + "\n" + page.boardId + "\n" + page.seasonId + "\n"
                + page.rulesReleaseId + "\n" + page.from.toEpochMilli() + "\n" + page.to.toEpochMilli());
    }

    private static ValueContracts.Page page(ValueContracts.Query query) {
        ValueContracts.Page page = new ValueContracts.Page();
        page.regionId = query.regionId;
        page.boardId = query.boardId;
        page.seasonId = query.seasonId;
        page.rulesReleaseId = query.rulesReleaseId;
        page.from = query.from;
        page.to = query.to;
        page.limit = query.limit;
        return page;
    }

    private static String structural(ValueContracts.Query query) {
        if (!CityBoardDraftDocument.isId(query.regionId) || !CityBoardDraftDocument.isId(query.boardId)
                || !CityBoardDraftDocument.isId(query.seasonId) || !CityBoardDraftDocument.isId(query.rulesReleaseId)
                || query.from == null || query.to == null) {
            return "MISSING_PARAM";
        }
        if (!window(query.from, query.to)) {
            return "INVALID_WINDOW";
        }
        if (query.limit < 1 || query.limit > 200) {
            return "MISSING_PARAM";
        }
        ValueContracts.Definition defined = query.definition == null ? new ValueContracts.Definition() : query.definition;
        String codes = codes(defined.meaningfulActions);
        if (codes != null) {
            return codes;
        }
        codes = codes(defined.effectiveChoices);
        if (codes != null) {
            return codes;
        }
        if ((defined.ratingMin == null) != (defined.ratingMax == null)) {
            return "MISSING_PARAM";
        }
        if (defined.ratingMin != null && (defined.ratingMin < 0 || defined.ratingMax > 100 || defined.ratingMin > defined.ratingMax)) {
            return "MISSING_PARAM";
        }
        if (defined.pointsUnit != null && !"POINT".equals(defined.pointsUnit) && !"MINOR".equals(defined.pointsUnit)) {
            return "MISSING_PARAM";
        }
        if (defined.budgetCurrency != null && !defined.budgetCurrency.matches("[A-Z]{3}")) {
            return "MISSING_PARAM";
        }
        boolean prior = query.priorFrom != null || query.priorTo != null;
        boolean cohort = defined.veteranMinPriorServiceSuccess != null;
        if (prior != cohort) {
            return "MISSING_PARAM";
        }
        if (cohort) {
            int threshold = defined.veteranMinPriorServiceSuccess;
            if (threshold < 1 || threshold > 1000) {
                return "MISSING_PARAM";
            }
            if (!window(query.priorFrom, query.priorTo) || query.priorTo.isAfter(query.from)) {
                return "INVALID_WINDOW";
            }
        }
        return null;
    }

    private static String codes(Set<String> values) {
        if (values == null) {
            return null;
        }
        if (values.isEmpty()) {
            return "MISSING_PARAM";
        }
        for (String value : values) {
            if (ValueKinds.noise(value)) {
                return "NOT_MEANINGFUL";
            }
            if (!ValueKinds.actionCode(value)) {
                return "MISSING_PARAM";
            }
        }
        return null;
    }

    private static boolean window(Instant from, Instant to) {
        if (from == null || to == null) {
            return false;
        }
        Duration span = Duration.between(from, to);
        return !span.isNegative() && !span.isZero() && span.compareTo(Duration.ofDays(31)) <= 0;
    }

    private static String canonical(ValueContracts.Query query) {
        ValueContracts.Definition defined = query.definition == null ? new ValueContracts.Definition() : query.definition;
        return String.join("\n", text(query.regionId), text(query.boardId), text(query.seasonId), text(query.rulesReleaseId),
                String.valueOf(query.from), String.valueOf(query.to), String.valueOf(query.priorFrom), String.valueOf(query.priorTo),
                String.valueOf(query.limit), join(defined.meaningfulActions), join(defined.effectiveChoices),
                String.valueOf(defined.ratingMin), String.valueOf(defined.ratingMax),
                String.valueOf(defined.veteranMinPriorServiceSuccess), text(defined.budgetCurrency), text(defined.pointsUnit));
    }

    private static String hashes(List<ValueEventRow> facts, List<ValueEventRow> visits, List<ValueEventRow> prior) {
        List<String> hashes = new ArrayList<String>();
        addHashes(hashes, facts, "f:");
        addHashes(hashes, visits, "v:");
        addHashes(hashes, prior, "p:");
        hashes.sort(String::compareTo);
        return String.join("\n", hashes);
    }

    private static void addHashes(List<String> hashes, List<ValueEventRow> rows, String prefix) {
        if (rows == null) {
            return;
        }
        for (ValueEventRow row : rows) {
            hashes.add(prefix + row.getPayloadHash());
        }
    }

    private static String join(Set<String> codes) {
        if (codes == null) {
            return "";
        }
        Set<String> sorted = new TreeSet<String>();
        for (String code : codes) {
            String normalized = ValueKinds.norm(code);
            if (normalized != null) {
                sorted.add(normalized);
            }
        }
        return String.join(",", sorted);
    }

    private static Map<String, Object> envelope(String status) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("candidate", Boolean.TRUE);
        data.put("contract", "CITY_VALUE_READ_V1");
        data.put("status", status);
        data.put("transport", "NOT_APPROVED");
        return data;
    }

    private static int httpOf(String status) {
        if ("REPORTED".equals(status) || "INCOMPLETE".equals(status) || "NO_CURRENT_BOARD".equals(status)
                || "CANCELLED".equals(status) || "PAGE".equals(status)) {
            return 200;
        }
        if ("SCOPE_MISMATCH".equals(status) || "DUPLICATE_REQUEST".equals(status) || "ACCOUNT_CHANGED".equals(status)
                || "CANCEL_REJECTED".equals(status)) {
            return 409;
        }
        if ("NOT_FOUND".equals(status)) {
            return 404;
        }
        if ("UNAUTHENTICATED".equals(status)) {
            return 401;
        }
        return 400;
    }

    private static String message(String status) {
        return switch (status) {
            case "NO_CURRENT_BOARD" -> "没有当前发布";
            case "SCOPE_MISMATCH" -> "范围不一致";
            case "INVALID_WINDOW" -> "时间窗无效";
            case "NOT_MEANINGFUL" -> "点击、走路、登录不能当作有意义动作";
            case "ACCOUNT_CHANGED" -> "请求属于另一个账号";
            case "NOT_FOUND" -> "不存在";
            case "INVALID_CURSOR" -> "游标无效";
            default -> "参数无效";
        };
    }

    private static boolean actorIdEquals(ValueRequestRow row, long actorId) {
        return row.getActorUserId() != null && row.getActorUserId() == actorId;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String writeMap(Map<String, Object> data) {
        try {
            return CityBoardDraftDocument.mapper().writeValueAsString(data);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Map<String, Object> readMap(String json) {
        try {
            return CityBoardDraftDocument.mapper().readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final class Read {
        private final List<ValueEventRow> rows;
        private final boolean truncated;

        private Read(List<ValueEventRow> rows, boolean truncated) {
            this.rows = rows;
            this.truncated = truncated;
        }

        private static Read empty() {
            return new Read(List.of(), false);
        }
    }

    private static final class ParsedCursor {
        private final Date at;
        private final Long id;
        private final String error;
        private final int http;
        private final boolean accountChanged;

        private ParsedCursor(Date at, Long id, String error, int http, boolean accountChanged) {
            this.at = at;
            this.id = id;
            this.error = error;
            this.http = http;
            this.accountChanged = accountChanged;
        }

        private static ParsedCursor start() {
            return new ParsedCursor(null, null, null, 200, false);
        }

        private static ParsedCursor position(Date at, long id) {
            return new ParsedCursor(at, id, null, 200, false);
        }

        private static ParsedCursor error(int http, String error) {
            return new ParsedCursor(null, null, error, http, false);
        }

        private static ParsedCursor account() {
            return new ParsedCursor(null, null, null, 409, true);
        }
    }
}
