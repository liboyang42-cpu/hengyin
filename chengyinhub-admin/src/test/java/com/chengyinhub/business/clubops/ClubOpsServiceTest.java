package com.chengyinhub.business.clubops;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.mapper.ClubOpsMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** club-ops(R8):距离排序、活动日场次分组、按月看板、榜单可用度量。 */
class ClubOpsServiceTest {

    private static Map<String, Object> club(long id, String lat, String lng, String source) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("name", "c" + id);
        m.put("lat", new BigDecimal(lat)); m.put("lng", new BigDecimal(lng));
        m.put("locationSource", source); m.put("memberCount", 3);
        return m;
    }

    @Test
    void nearby_sortsByRealDistance_dropsOutOfRadiusAndExcluded() {
        // 西湖附近 (30.25, 120.15)
        List<Map<String, Object>> rows = List.of(
                club(1, "30.30", "120.20", "events"),   // ~7 km
                club(2, "30.251", "120.151", "address"), // ~0.15 km
                club(3, "31.23", "121.47", "events"),    // 上海 ~165 km
                club(4, "30.26", "120.16", "events"));   // ~1.4 km, excluded
        List<Map<String, Object>> out = ClubOpsService.rankByDistance(rows, 30.25, 120.15, 30, 10, Set.of(4L));
        assertEquals(List.of(2L, 1L), out.stream().map(m -> (Long) m.get("id")).toList());
        assertEquals("address", out.get(0).get("locationSource"));
        assertEquals(new BigDecimal("0.1"), out.get(0).get("distanceKm"));
    }

    @Test
    void nearby_respectsLimit() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 5; i++) rows.add(club(i, "30.25", String.valueOf(120.15 + i * 0.01), "events"));
        assertEquals(2, ClubOpsService.rankByDistance(rows, 30.25, 120.15, 30, 2, null).size());
    }

    @Test
    void nearby_rejectsBadCoordinatesAndRadius() {
        ClubOpsService service = new ClubOpsService(Mockito.mock(ClubOpsMapper.class));
        assertThrows(ServiceException.class, () -> service.nearby(null, 120, null, null, null));
        assertThrows(ServiceException.class, () -> service.nearby(91, 120, null, null, null));
        assertThrows(ServiceException.class, () -> service.nearby(30, 120, 500, null, null));
        assertThrows(ServiceException.class, () -> service.nearby(30, 120, null, 0, null));
    }

    @Test
    void distance_isGreatCircle() {
        // 杭州 → 上海 约 165 km
        double km = ClubOpsService.distanceKm(30.25, 120.15, 31.23, 121.47);
        assertTrue(km > 155 && km < 175, "km=" + km);
        double[] box = ClubOpsService.boundingBox(30, 120, 10);
        assertTrue(box[0] < 30 && box[1] > 30 && box[2] < 120 && box[3] > 120);
    }

    private static Map<String, Object> occurrence(long id, LocalDateTime start, LocalDateTime end) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("activityId", id); m.put("topicId", 9L); m.put("name", "s" + id);
        m.put("startDate", start); m.put("endDate", end); m.put("status", 1); m.put("publishStatus", 1);
        return m;
    }

    private static Map<String, Object> seat(long activityId, LocalDateTime start, long memberId, int arrived) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("activityId", activityId); m.put("startDate", start); m.put("memberId", memberId); m.put("arrived", arrived);
        return m;
    }

    @Test
    void projects_splitsTodayAndUpcoming_withCounts() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 15, 14, 0);
        LocalDateTime running = LocalDateTime.of(2026, 11, 14, 9, 0);
        LocalDateTime tonight = LocalDateTime.of(2026, 11, 15, 19, 0);
        LocalDateTime saturday = LocalDateTime.of(2026, 11, 21, 9, 0);
        LocalDateTime endedThisMorning = LocalDateTime.of(2026, 11, 14, 8, 0);
        List<Map<String, Object>> occ = List.of(
                occurrence(1, running, LocalDateTime.of(2026, 11, 16, 18, 0)),
                occurrence(2, tonight, null),
                occurrence(3, saturday, null),
                occurrence(4, endedThisMorning, LocalDateTime.of(2026, 11, 15, 10, 0)));
        List<Map<String, Object>> seats = List.of(seat(2, tonight, 7, 1), seat(2, tonight, 8, 0), seat(3, saturday, 7, 0));
        Map<String, Object> board = ClubOpsService.projectBoard(5L, occ, seats, List.of("scan", "roster"), now);
        @SuppressWarnings("unchecked") List<Map<String, Object>> today = (List<Map<String, Object>>) board.get("today");
        @SuppressWarnings("unchecked") List<Map<String, Object>> upcoming = (List<Map<String, Object>>) board.get("upcoming");
        assertEquals(List.of(1L, 2L), today.stream().map(m -> (Long) m.get("activityId")).toList());
        assertEquals(true, today.get(0).get("running"));
        assertEquals(2, today.get(1).get("enrolled"));
        assertEquals(1, today.get(1).get("arrived"));
        assertEquals(List.of(3L), upcoming.stream().map(m -> (Long) m.get("activityId")).toList());
        assertEquals(List.of("scan", "roster"), board.get("actions"));
    }

    @Test
    void actions_followPermissions() {
        ClubAccessContext checker = Mockito.mock(ClubAccessContext.class);
        Mockito.when(checker.hasPermission(ClubPermission.EVENT_CHECKIN)).thenReturn(true);
        assertEquals(List.of("scan", "roster", "checkin"), ClubOpsService.actionsFor(checker));
        ClubAccessContext owner = Mockito.mock(ClubAccessContext.class);
        Mockito.when(owner.hasPermission(Mockito.anyString())).thenReturn(true);
        assertEquals(List.of("scan", "roster", "checkin", "proxyEnroll", "settlement"), ClubOpsService.actionsFor(owner));
        assertEquals(List.of(), ClubOpsService.actionsFor(null));
    }

    private static Map<String, Object> join(long memberId, LocalDateTime at) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("memberId", memberId); m.put("joinedAt", at);
        return m;
    }

    @Test
    @SuppressWarnings("unchecked")
    void dashboard_monthlyGrowthAttendanceNoShowsAndRepeat() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 15, 12, 0);
        LocalDateTime aug = LocalDateTime.of(2026, 8, 3, 9, 0);
        LocalDateTime oct1 = LocalDateTime.of(2026, 10, 4, 9, 0);
        LocalDateTime oct2 = LocalDateTime.of(2026, 10, 18, 9, 0);
        LocalDateTime nov = LocalDateTime.of(2026, 11, 8, 9, 0);
        List<Map<String, Object>> seats = List.of(
                seat(10, aug, 1, 1),                       // member 1 first came in August (lookback)
                seat(11, oct1, 1, 1), seat(11, oct1, 2, 1), seat(11, oct1, 3, 0),
                seat(12, oct2, 2, 1), seat(12, oct2, 4, 0),
                seat(13, nov, 3, 1));
        List<Map<String, Object>> joins = List.of(
                join(1, LocalDateTime.of(2026, 7, 1, 0, 0)),
                join(2, LocalDateTime.of(2026, 10, 2, 0, 0)),
                join(3, LocalDateTime.of(2026, 10, 3, 0, 0)),
                join(4, LocalDateTime.of(2026, 11, 1, 0, 0)));
        Map<String, Object> out = ClubOpsService.computeDashboard(5L, 2, now, seats, joins);
        List<Map<String, Object>> months = (List<Map<String, Object>>) out.get("months");
        assertEquals(2, months.size());
        Map<String, Object> oct = months.get(0), novRow = months.get(1);
        assertEquals("2026-10", oct.get("month"));
        assertEquals(2, oct.get("newMembers"));
        assertEquals(4, oct.get("totalMembers"));      // owner + 1, 2, 3
        assertEquals(2, oct.get("sessions"));
        assertEquals(5, oct.get("enrolled"));
        assertEquals(3, oct.get("arrived"));
        assertEquals(2, oct.get("noShows"));
        assertEquals(new BigDecimal("60.0"), oct.get("attendanceRate"));
        assertEquals(2, oct.get("attendees"));
        assertEquals(2, oct.get("repeatAttendees"));   // 1 came back from August, 2 came twice
        assertEquals("2026-11", novRow.get("month"));
        assertEquals(1, novRow.get("newMembers"));
        assertEquals(5, novRow.get("totalMembers"));
        assertEquals(new BigDecimal("100.0"), novRow.get("attendanceRate"));
        assertEquals(0, novRow.get("repeatAttendees")); // member 3 never arrived before
        assertEquals("CURRENT_MEMBERS", out.get("memberBasis"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dashboard_emptyMonthHasNoRate() {
        Map<String, Object> out = ClubOpsService.computeDashboard(5L, 1, LocalDateTime.of(2026, 11, 15, 0, 0), List.of(), List.of());
        Map<String, Object> row = ((List<Map<String, Object>>) out.get("months")).get(0);
        assertNull(row.get("attendanceRate"));
        assertEquals(1, row.get("totalMembers"));
        assertEquals(0, row.get("noShows"));
    }

    @Test
    void sorts_runningMetricsOnlyForClubsThatUseThem() {
        ClubOpsMapper mapper = Mockito.mock(ClubOpsMapper.class);
        Mockito.when(mapper.countRunningTopics(1L)).thenReturn(0);
        Mockito.when(mapper.countRunningTopics(2L)).thenReturn(3);
        ClubOpsService service = new ClubOpsService(mapper);
        Map<String, Object> plain = service.leaderboardSorts(1L);
        assertEquals(List.of("events", "checkins", "hosted"), plain.get("sorts"));
        assertEquals("events", plain.get("defaultSort"));
        Map<String, Object> running = service.leaderboardSorts(2L);
        assertEquals(List.of("events", "checkins", "hosted", "composite", "mileage", "pace", "duration"), running.get("sorts"));
        assertEquals("composite", running.get("defaultSort"));
    }
}
