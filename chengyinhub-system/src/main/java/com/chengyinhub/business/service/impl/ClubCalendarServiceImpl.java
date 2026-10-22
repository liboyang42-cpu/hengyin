package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.ClubCalendarMapper;
import com.chengyinhub.business.service.IClubCalendarService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 俱乐部月历场次(W10a #19)。
 *
 * <p>月历以前只拿 {@code /api/club/topics} 的主题列表本地铺格子 —— 主题不是场次,
 * 一个主题可以开多场,也可能一场都没有,月历因此会把「已加载主题」当成「排期」。
 * 这里按月直接读 cms_activity 场次,带 updatedAt,截断时如实标 truncated。
 */
@Service
public class ClubCalendarServiceImpl implements IClubCalendarService {
    private static final Pattern MONTH = Pattern.compile("^\\d{4}-\\d{2}$");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ClubCalendarMapper mapper;

    public ClubCalendarServiceImpl(ClubCalendarMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> monthSessions(Long clubId, String month, boolean includeUnpublished) {
        if (clubId == null || clubId <= 0) throw new IllegalArgumentException("缺少俱乐部ID");
        YearMonth yearMonth = parseMonth(month);
        LocalDateTime rangeStart = yearMonth.atDay(1).atStartOfDay();
        LocalDateTime rangeEnd = yearMonth.plusMonths(1).atDay(1).atStartOfDay();
        List<Map<String, Object>> rows = mapper.selectClubSessionsInRange(
                clubId, rangeStart, rangeEnd, includeUnpublished, MAX_SESSIONS_PER_MONTH + 1);
        boolean truncated = rows != null && rows.size() > MAX_SESSIONS_PER_MONTH;
        List<Map<String, Object>> sessions = new ArrayList<Map<String, Object>>();
        LocalDateTime latest = null;
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                if (sessions.size() >= MAX_SESSIONS_PER_MONTH) break;
                LocalDateTime start = toLocalDateTime(row.get("start_date"));
                // 没有开始时间的场次落不进任何一格;SQL 已按区间过滤,这里只是防御。
                if (start == null || start.isBefore(rangeStart) || !start.isBefore(rangeEnd)) continue;
                LocalDateTime updated = toLocalDateTime(row.get("updated_at"));
                if (updated != null && (latest == null || updated.isAfter(latest))) latest = updated;
                Map<String, Object> session = new LinkedHashMap<String, Object>();
                session.put("activityId", toLong(row.get("activity_id")));
                session.put("topicId", toLong(row.get("topic_id")));
                session.put("name", row.get("name") == null ? null : String.valueOf(row.get("name")));
                session.put("date", start.toLocalDate().format(DATE));
                session.put("startDate", start.format(DATE_TIME));
                LocalDateTime end = toLocalDateTime(row.get("end_date"));
                session.put("endDate", end == null ? null : end.format(DATE_TIME));
                session.put("state", stateOf(row));
                session.put("updatedAt", updated == null ? null : updated.format(DATE_TIME));
                sessions.add(session);
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("clubId", clubId);
        out.put("month", yearMonth.toString());
        out.put("scope", includeUnpublished ? "GOVERNOR" : "PUBLIC");
        out.put("sessions", sessions);
        out.put("truncated", truncated);
        out.put("updatedAt", latest == null ? null : latest.format(DATE_TIME));
        return out;
    }

    private static YearMonth parseMonth(String month) {
        String value = month == null ? "" : month.trim();
        if (!MONTH.matcher(value).matches()) throw new IllegalArgumentException("月份格式不合法");
        int year = Integer.parseInt(value.substring(0, 4));
        int monthValue = Integer.parseInt(value.substring(5, 7));
        if (year < 1970 || monthValue < 1 || monthValue > 12) throw new IllegalArgumentException("月份格式不合法");
        return YearMonth.of(year, monthValue);
    }

    /** 公开视角 SQL 只放行 PUBLISHED;治理者能看到待审/已取消,必须带状态,前端不能当成已开售。 */
    private static String stateOf(Map<String, Object> row) {
        if (toLong(row.get("cancelled")) != null && toLong(row.get("cancelled")) == 1L) return "CANCELLED";
        Long status = toLong(row.get("status"));
        Long publishStatus = toLong(row.get("publish_status"));
        if (Long.valueOf(1L).equals(status) && Long.valueOf(1L).equals(publishStatus)) return "PUBLISHED";
        return "UNPUBLISHED";
    }

    private static Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** MySQL 驱动按版本可能给 Timestamp 或 LocalDateTime,H2 给 Timestamp;都按库里的本地时间读,不做时区换算。 */
    private static LocalDateTime toLocalDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime) return (LocalDateTime) value;
        if (value instanceof java.sql.Timestamp) return ((java.sql.Timestamp) value).toLocalDateTime();
        if (value instanceof java.sql.Date) return ((java.sql.Date) value).toLocalDate().atStartOfDay();
        if (value instanceof LocalDate) return ((LocalDate) value).atStartOfDay();
        if (value instanceof Date) return new java.sql.Timestamp(((Date) value).getTime()).toLocalDateTime();
        return null;
    }
}
