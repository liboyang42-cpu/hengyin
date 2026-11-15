package com.chengyinhub.business.clubops;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.mapper.ClubOpsMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * club-ops(R8):附近俱乐部(CLB-06)、活动日场次(CLB-08/09)、运营看板(CLB-10)、榜单可用度量(CLB-13)。
 *
 * <p>只读。计算部分是包内静态方法,单测直接喂行数据,不碰库。
 */
@Service
public class ClubOpsService {
    public static final String DATUM = "GCJ-02";
    static final double EARTH_RADIUS_KM = 6371.0088;
    static final double KM_PER_DEGREE_LAT = 111.32;
    public static final int DEFAULT_RADIUS_KM = 30;
    public static final int MAX_RADIUS_KM = 200;
    public static final int DEFAULT_NEARBY_LIMIT = 20;
    public static final int MAX_NEARBY_LIMIT = 50;
    /** 视窗里最多取多少个候选俱乐部再按真实距离排序。 */
    static final int CANDIDATE_LIMIT = 500;
    /** 活动日:今天与之后 14 天。 */
    static final int UPCOMING_DAYS = 14;
    static final int MAX_OCCURRENCES = 40;
    public static final int DEFAULT_MONTHS = 6;
    public static final int MAX_MONTHS = 12;
    /** 回头客回看窗口:看板第一个月之前再看 24 个月。 */
    static final int REPEAT_LOOKBACK_MONTHS = 24;

    static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 不分俱乐部类型都适用的榜单。 */
    public static final List<String> GENERAL_SORTS = List.of("events", "checkins", "hosted");
    /** 跑步类度量:只有用里程的俱乐部才有意义。composite 的加权分里含里程,同样归这一组。 */
    public static final List<String> RUNNING_SORTS = List.of("composite", "mileage", "pace", "duration");

    private final ClubOpsMapper mapper;

    public ClubOpsService(ClubOpsMapper mapper) {
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------- 附近俱乐部

    /**
     * 按真实距离排的附近俱乐部。坐标是 GCJ-02(与场次、门店同一坐标系)。
     *
     * @param excludeIds 不再列出的俱乐部(首页里我创建/加入的),可为 null
     */
    public Map<String, Object> nearby(Object rawLat, Object rawLng, Object rawRadius, Object rawLimit, Set<Long> excludeIds) {
        double lat = coordinate(rawLat, 90, "lat");
        double lng = coordinate(rawLng, 180, "lng");
        int radius = boundedInt(rawRadius, DEFAULT_RADIUS_KM, 1, MAX_RADIUS_KM, "radiusKm");
        int limit = boundedInt(rawLimit, DEFAULT_NEARBY_LIMIT, 1, MAX_NEARBY_LIMIT, "limit");
        double[] box = boundingBox(lat, lng, radius);
        List<Map<String, Object>> rows = mapper.selectLocatedClubs(
                BigDecimal.valueOf(box[0]), BigDecimal.valueOf(box[1]),
                BigDecimal.valueOf(box[2]), BigDecimal.valueOf(box[3]), CANDIDATE_LIMIT);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("datum", DATUM);
        out.put("radiusKm", radius);
        out.put("clubs", rankByDistance(rows, lat, lng, radius, limit, excludeIds));
        return out;
    }

    static List<Map<String, Object>> rankByDistance(List<Map<String, Object>> rows, double lat, double lng,
                                                    int radiusKm, int limit, Set<Long> excludeIds) {
        List<Map<String, Object>> ranked = new ArrayList<>();
        if (rows == null) return ranked;
        for (Map<String, Object> row : rows) {
            Long id = toLong(row.get("id"));
            Double clubLat = toDouble(row.get("lat"));
            Double clubLng = toDouble(row.get("lng"));
            if (id == null || clubLat == null || clubLng == null) continue;
            if (excludeIds != null && excludeIds.contains(id)) continue;
            double km = distanceKm(lat, lng, clubLat, clubLng);
            if (km > radiusKm) continue;
            Map<String, Object> club = new LinkedHashMap<>();
            club.put("id", id);
            club.put("name", row.get("name"));
            club.put("logo", row.get("logo"));
            club.put("cover", row.get("cover"));
            club.put("city", row.get("city"));
            club.put("address", row.get("address"));
            club.put("clubType", row.get("clubType"));
            club.put("memberCount", toLong(row.get("memberCount")));
            club.put("latitude", clubLat);
            club.put("longitude", clubLng);
            club.put("locationSource", "address".equals(row.get("locationSource")) ? "address" : "events");
            club.put("distanceKm", BigDecimal.valueOf(km).setScale(1, RoundingMode.HALF_UP));
            ranked.add(club);
        }
        ranked.sort(Comparator.<Map<String, Object>, BigDecimal>comparing(c -> (BigDecimal) c.get("distanceKm"))
                .thenComparing(c -> (Long) c.get("id")));
        return ranked.size() > limit ? new ArrayList<>(ranked.subList(0, limit)) : ranked;
    }

    /** 大圆距离(km)。 */
    static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** {south, north, west, east},把半径换成经纬度框,只用来让 SQL 先收窄。 */
    static double[] boundingBox(double lat, double lng, int radiusKm) {
        double dLat = radiusKm / KM_PER_DEGREE_LAT;
        double cos = Math.max(0.01, Math.cos(Math.toRadians(lat)));
        double dLng = Math.min(180, radiusKm / (KM_PER_DEGREE_LAT * cos));
        return new double[]{Math.max(-90, lat - dLat), Math.min(90, lat + dLat),
                Math.max(-180, lng - dLng), Math.min(180, lng + dLng)};
    }

    // ---------------------------------------------------------------- 活动日场次

    /** 今天(含进行中)与未来 14 天的场次,每场带报名/到场人数与当前账号能做的动作。 */
    public Map<String, Object> projects(Long clubId, ClubAccessContext access, LocalDateTime now) {
        LocalDateTime todayStart = now.toLocalDate().atStartOfDay();
        LocalDateTime until = todayStart.plusDays(UPCOMING_DAYS + 1L);
        List<Map<String, Object>> occurrences = mapper.selectOccurrences(clubId, todayStart, until, MAX_OCCURRENCES + 1);
        if (occurrences == null) occurrences = List.of();
        LocalDateTime earliest = todayStart;
        for (Map<String, Object> row : occurrences) {
            LocalDateTime start = toDateTime(row.get("startDate"));
            if (start != null && start.isBefore(earliest)) earliest = start;
        }
        List<Map<String, Object>> attendance = occurrences.isEmpty() ? List.of() : mapper.selectAttendance(clubId, earliest, until);
        return projectBoard(clubId, occurrences, attendance, actionsFor(access), now);
    }

    static List<String> actionsFor(ClubAccessContext access) {
        List<String> actions = new ArrayList<>();
        if (access == null) return actions;
        if (access.hasPermission(ClubPermission.EVENT_CHECKIN)) {
            actions.add("scan");
            actions.add("roster");
            actions.add("checkin");
        }
        if (access.hasPermission(ClubPermission.ACTIVITY_MANAGE)) actions.add("proxyEnroll");
        if (access.hasPermission(ClubPermission.FINANCE_READ)) actions.add("settlement");
        return actions;
    }

    static Map<String, Object> projectBoard(Long clubId, List<Map<String, Object>> occurrences,
                                            List<Map<String, Object>> attendance, List<String> actions,
                                            LocalDateTime now) {
        Map<Long, int[]> counts = new HashMap<>();
        if (attendance != null) {
            for (Map<String, Object> row : attendance) {
                Long activityId = toLong(row.get("activityId"));
                if (activityId == null) continue;
                int[] c = counts.computeIfAbsent(activityId, k -> new int[2]);
                c[0]++;
                if (isArrived(row.get("arrived"))) c[1]++;
            }
        }
        LocalDate today = now.toLocalDate();
        List<Map<String, Object>> todayRows = new ArrayList<>();
        List<Map<String, Object>> upcoming = new ArrayList<>();
        int shown = 0;
        for (Map<String, Object> row : occurrences) {
            if (shown >= MAX_OCCURRENCES) break;
            Long activityId = toLong(row.get("activityId"));
            LocalDateTime start = toDateTime(row.get("startDate"));
            if (activityId == null || start == null) continue;
            LocalDateTime end = toDateTime(row.get("endDate"));
            boolean running = !start.isAfter(now) && end != null && !end.isBefore(now);
            boolean isToday = start.toLocalDate().equals(today) || running;
            if (!isToday && end != null && end.isBefore(now)) continue;
            int[] c = counts.getOrDefault(activityId, new int[2]);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("activityId", activityId);
            item.put("topicId", toLong(row.get("topicId")));
            item.put("name", row.get("name"));
            item.put("startDate", start.format(DATE_TIME));
            item.put("endDate", end == null ? null : end.format(DATE_TIME));
            item.put("running", running);
            item.put("state", Long.valueOf(1L).equals(toLong(row.get("status"))) && Long.valueOf(1L).equals(toLong(row.get("publishStatus")))
                    ? "PUBLISHED" : "UNPUBLISHED");
            item.put("enrolled", c[0]);
            item.put("arrived", c[1]);
            item.put("actions", actions);
            (isToday ? todayRows : upcoming).add(item);
            shown++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clubId", clubId);
        out.put("today", todayRows);
        out.put("upcoming", upcoming);
        out.put("actions", actions);
        out.put("truncated", occurrences.size() > MAX_OCCURRENCES);
        out.put("days", UPCOMING_DAYS);
        return out;
    }

    // ---------------------------------------------------------------- 运营看板

    public Map<String, Object> dashboard(Long clubId, Object rawMonths, LocalDateTime now) {
        int months = boundedInt(rawMonths, DEFAULT_MONTHS, 1, MAX_MONTHS, "months");
        YearMonth first = YearMonth.from(now).minusMonths(months - 1L);
        LocalDateTime lookback = first.minusMonths(REPEAT_LOOKBACK_MONTHS).atDay(1).atStartOfDay();
        // 今天的场次明天才进看板:当天还没结束,未到场不能算缺席。
        LocalDateTime until = now.toLocalDate().atStartOfDay();
        List<Map<String, Object>> attendance = until.isAfter(lookback) ? mapper.selectAttendance(clubId, lookback, until) : List.of();
        List<Map<String, Object>> joins = mapper.selectMemberJoins(clubId);
        return computeDashboard(clubId, months, now, attendance, joins);
    }

    static Map<String, Object> computeDashboard(Long clubId, int months, LocalDateTime now,
                                                List<Map<String, Object>> attendance, List<Map<String, Object>> joins) {
        YearMonth current = YearMonth.from(now);
        YearMonth first = current.minusMonths(months - 1L);
        // 每人第一次到场的月份(回看窗口内),判断「回头」。
        Map<Long, YearMonth> firstArrival = new HashMap<>();
        Map<YearMonth, Map<Long, int[]>> perMonthActivity = new LinkedHashMap<>();
        Map<YearMonth, Map<Long, Integer>> arrivalsPerMember = new HashMap<>();
        if (attendance != null) {
            for (Map<String, Object> row : attendance) {
                Long activityId = toLong(row.get("activityId"));
                Long memberId = toLong(row.get("memberId"));
                LocalDateTime start = toDateTime(row.get("startDate"));
                if (activityId == null || memberId == null || start == null) continue;
                YearMonth month = YearMonth.from(start);
                boolean arrived = isArrived(row.get("arrived"));
                if (arrived) {
                    YearMonth seen = firstArrival.get(memberId);
                    if (seen == null || month.isBefore(seen)) firstArrival.put(memberId, month);
                }
                if (month.isBefore(first) || month.isAfter(current)) continue;
                int[] c = perMonthActivity.computeIfAbsent(month, k -> new LinkedHashMap<>())
                        .computeIfAbsent(activityId, k -> new int[2]);
                c[0]++;
                if (arrived) {
                    c[1]++;
                    arrivalsPerMember.computeIfAbsent(month, k -> new HashMap<>()).merge(memberId, 1, Integer::sum);
                }
            }
        }
        List<YearMonth> joinMonths = new ArrayList<>();
        if (joins != null) {
            for (Map<String, Object> row : joins) {
                LocalDateTime joined = toDateTime(row.get("joinedAt"));
                if (joined != null) joinMonths.add(YearMonth.from(joined));
            }
        }
        int unknownJoin = (joins == null ? 0 : joins.size()) - joinMonths.size();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (YearMonth month = first; !month.isAfter(current); month = month.plusMonths(1)) {
            int newMembers = 0;
            int totalMembers = 1 + unknownJoin; // 主理人 + 没有入会时间的老成员
            for (YearMonth joined : joinMonths) {
                if (joined.equals(month)) newMembers++;
                if (!joined.isAfter(month)) totalMembers++;
            }
            Map<Long, int[]> activities = perMonthActivity.getOrDefault(month, Map.of());
            int enrolled = 0, arrived = 0;
            for (int[] c : activities.values()) { enrolled += c[0]; arrived += c[1]; }
            Map<Long, Integer> members = arrivalsPerMember.getOrDefault(month, Map.of());
            int repeat = 0;
            for (Map.Entry<Long, Integer> e : members.entrySet()) {
                YearMonth firstSeen = firstArrival.get(e.getKey());
                if (e.getValue() > 1 || (firstSeen != null && firstSeen.isBefore(month))) repeat++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", month.toString());
            row.put("newMembers", newMembers);
            row.put("totalMembers", totalMembers);
            row.put("sessions", activities.size());
            row.put("enrolled", enrolled);
            row.put("arrived", arrived);
            row.put("noShows", enrolled - arrived);
            row.put("attendanceRate", enrolled == 0 ? null
                    : BigDecimal.valueOf(arrived * 100L).divide(BigDecimal.valueOf(enrolled), 1, RoundingMode.HALF_UP));
            row.put("attendees", members.size());
            row.put("repeatAttendees", repeat);
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clubId", clubId);
        out.put("months", rows);
        // 退出的成员行会被删除,所以成员数 = 现有成员按入会月份累计,不含已退出的人。
        out.put("memberBasis", "CURRENT_MEMBERS");
        out.put("attendanceBasis", "PAID_REGISTRATIONS_BEFORE_TODAY");
        out.put("generatedAt", now.format(DATE_TIME));
        return out;
    }

    // ---------------------------------------------------------------- 榜单度量

    public Map<String, Object> leaderboardSorts(Long clubId) {
        boolean running = mapper.countRunningTopics(clubId) > 0;
        return sortsFor(clubId, running);
    }

    static Map<String, Object> sortsFor(Long clubId, boolean running) {
        List<String> sorts = new ArrayList<>(GENERAL_SORTS);
        if (running) sorts.addAll(RUNNING_SORTS);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clubId", clubId);
        out.put("sorts", sorts);
        out.put("defaultSort", running ? "composite" : "events");
        out.put("runningMetrics", running);
        return out;
    }

    // ---------------------------------------------------------------- 工具

    static boolean isArrived(Object value) {
        if (value instanceof Boolean b) return b;
        Long n = toLong(value);
        return n != null && n == 1L;
    }

    static double coordinate(Object raw, double bound, String field) {
        Double value = toDouble(raw);
        if (value == null || value.isNaN() || value.isInfinite() || Math.abs(value) > bound) {
            throw new ServiceException("位置参数无效:" + field, 400);
        }
        return value;
    }

    static int boundedInt(Object raw, int fallback, int min, int max, String field) {
        if (raw == null) return fallback;
        Long value = raw instanceof Boolean ? null : toLong(raw);
        if (value == null || value < min || value > max) throw new ServiceException("参数无效:" + field, 400);
        return value.intValue();
    }

    static Long toLong(Object value) {
        if (value == null || value instanceof Boolean) return null;
        if (value instanceof Number n) return n.longValue();
        try { return Long.valueOf(String.valueOf(value).trim()); } catch (NumberFormatException e) { return null; }
    }

    static Double toDouble(Object value) {
        if (value == null || value instanceof Boolean) return null;
        if (value instanceof Number n) return n.doubleValue();
        try { return Double.valueOf(String.valueOf(value).trim()); } catch (NumberFormatException e) { return null; }
    }

    static LocalDateTime toDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime t) return t;
        if (value instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        if (value instanceof java.sql.Date d) return d.toLocalDate().atStartOfDay();
        if (value instanceof LocalDate d) return d.atStartOfDay();
        if (value instanceof java.util.Date d) return new java.sql.Timestamp(d.getTime()).toLocalDateTime();
        return null;
    }

    static Set<Long> ids(Collection<Long> values) {
        return values == null ? Set.of() : new HashSet<>(values);
    }
}
