package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.PersonalExplorationGoal;
import com.chengyinhub.business.mapper.PersonalExplorationGoalMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class PersonalExplorationGoalService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter WALL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private final PersonalExplorationGoalMapper mapper;
    public PersonalExplorationGoalService(PersonalExplorationGoalMapper mapper) { this.mapper = mapper; }
    protected long now() { return System.currentTimeMillis(); }
    private static void owner(Long value) { if (value == null || value <= 0) throw new ServiceException("请先登录", 401); }
    private static void id(Long value) { if (value == null || value <= 0) throw new ServiceException("目标编号不合法", 400); }
    private static void request(String value) { if (value == null || !value.matches("[A-Za-z0-9_-]{16,64}")) throw new ServiceException("请保留有效的原请求编号", 400); }
    private static LocalDate date(String value) {
        try {
            if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
            LocalDate result = LocalDate.parse(value);
            if (result.getYear() < 2000 || result.getYear() > 9998) throw new IllegalArgumentException();
            return result;
        } catch (RuntimeException error) { throw new ServiceException("日期应为有效的 YYYY-MM-DD", 400); }
    }
    /** Unique owner/request key, autocommit insert: concurrent duplicate reads the committed winner. */
    public Map<String,Object> create(Long owner, String requestId, String metric, Integer target, String startDate, String endDateExclusive) {
        owner(owner); request(requestId);
        if (!Arrays.asList("DISTINCT_ROUTES", "EXPLORATION_DAYS").contains(metric)) throw new ServiceException("目标指标不合法", 400);
        LocalDate start = date(startDate), end = date(endDateExclusive);
        long days = ChronoUnit.DAYS.between(start, end);
        if (days < 1 || days > 366 || target == null || target < 1 || target > 100
                || ("EXPLORATION_DAYS".equals(metric) && target > days)) throw new ServiceException("目标为1至100，时间范围1至366天，探索天数不能超过范围", 400);
        PersonalExplorationGoal existing = mapper.byRequest(owner, requestId);
        if (existing != null) return replay(existing, metric, target, startDate, endDateExclusive);
        LocalDate today = Instant.ofEpochMilli(now()).atZone(ZONE).toLocalDate();
        if (start.isBefore(today) || end.isAfter(today.plusDays(366))) throw new ServiceException("开始日不能早于今天，结束日须在未来366天内", 400);
        PersonalExplorationGoal row = new PersonalExplorationGoal();
        row.setMemberId(owner); row.setRequestId(requestId); row.setMetric(metric); row.setTarget(target);
        row.setStartDate(startDate); row.setEndDateExclusive(endDateExclusive); row.setCreatedAtMs(now()); row.setCancelled(0);
        try {
            if (mapper.insert(row) != 1) throw new IllegalStateException("目标创建未确认");
        } catch (DuplicateKeyException collision) {
            existing = mapper.byRequest(owner, requestId);
            if (existing == null) throw collision;
            return replay(existing, metric, target, startDate, endDateExclusive);
        }
        return view(row, now());
    }
    private Map<String,Object> replay(PersonalExplorationGoal row, String metric, Integer target, String start, String end) {
        if (!metric.equals(row.getMetric()) || !target.equals(row.getTarget()) || !start.equals(row.getStartDate()) || !end.equals(row.getEndDateExclusive()))
            throw new ServiceException("原请求编号已用于不同目标", 409);
        return view(row, now());
    }
    public Map<String,Object> receipt(Long owner, String requestId) {
        owner(owner); request(requestId);
        PersonalExplorationGoal row = mapper.byRequest(owner, requestId);
        if (row == null) throw new ServiceException("尚未查到原请求，请保留编号重试", 404);
        return view(row, now());
    }
    public Map<String,Object> detail(Long owner, Long id) {
        owner(owner); id(id); return view(require(owner, id), now());
    }
    private PersonalExplorationGoal require(Long owner, Long id) {
        PersonalExplorationGoal row = mapper.byId(owner, id);
        if (row == null) throw new ServiceException("目标不存在", 404);
        return row;
    }
    public Map<String,Object> cancel(Long owner, Long id) {
        owner(owner); id(id); require(owner, id);
        mapper.cancel(owner, id); // Idempotent monotonic CAS; no update/create method can revive a cancelled goal.
        return view(require(owner, id), now());
    }
    public Map<String,Object> list(Long owner, Long beforeId, Integer requestedLimit) {
        owner(owner); if (beforeId != null) id(beforeId);
        int limit = requestedLimit == null ? 20 : requestedLimit;
        if (limit < 1 || limit > 50) throw new ServiceException("每页应为1至50条", 400);
        List<PersonalExplorationGoal> rows = mapper.page(owner, beforeId, limit + 1);
        List<Map<String,Object>> items = new ArrayList<>(); long asOf = now();
        for (int i = 0; i < Math.min(limit, rows.size()); i++) items.add(view(rows.get(i), asOf));
        boolean more = rows.size() > limit; Map<String,Object> out = new LinkedHashMap<>();
        out.put("items", items); out.put("hasMore", more); out.put("nextBeforeId", more ? String.valueOf(rows.get(limit - 1).getId()) : null);
        return out;
    }
    private Map<String,Object> view(PersonalExplorationGoal row, long asOf) {
        long progress = mapper.progress(row.getMemberId(), row.getMetric(), row.getStartDate() + " 00:00:00",
                row.getEndDateExclusive() + " 00:00:00", Instant.ofEpochMilli(asOf).atZone(ZONE).format(WALL));
        LocalDate today = Instant.ofEpochMilli(asOf).atZone(ZONE).toLocalDate();
        String state = Integer.valueOf(1).equals(row.getCancelled()) ? "CANCELLED" : progress >= row.getTarget() ? "ACHIEVED"
                : !today.isBefore(date(row.getEndDateExclusive())) ? "ENDED" : today.isBefore(date(row.getStartDate())) ? "SCHEDULED" : "ACTIVE";
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("id", String.valueOf(row.getId())); out.put("requestId", row.getRequestId()); out.put("metric", row.getMetric()); out.put("target", row.getTarget());
        out.put("startDate", row.getStartDate()); out.put("endDateExclusive", row.getEndDateExclusive());
        out.put("progress", progress); out.put("state", state); out.put("privateCommemoration", "ACHIEVED".equals(state));
        out.put("timeZone", "Asia/Shanghai"); out.put("asOfMs", asOf); out.put("createdAtMs", row.getCreatedAtMs());
        out.put("countingVersion", "COMPLETED_SESSION_AGGREGATE_V1");
        out.put("coverage", "HISTORICAL_OWN_COMPLETIONS_ROUTE_ID_ACROSS_VERSIONS_NONCONSECUTIVE_DAYS");
        return out;
    }
}
