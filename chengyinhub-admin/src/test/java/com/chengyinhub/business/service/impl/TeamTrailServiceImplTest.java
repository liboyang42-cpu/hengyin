package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.common.core.redis.RedisCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 第九轮 m2 第 2 项:同场路线的读写只给这一场已付费、自己开着分享的人。Redis 用内存表替身。 */
class TeamTrailServiceImplTest {

    private final Map<String, Map<String, Object>> hashes = new HashMap<>();
    private final Map<String, Long> ttl = new HashMap<>();
    /** activityId → 已付费成员 */
    private final Map<Long, Set<Long>> paid = new HashMap<>();
    /** memberId → 本场队伍 id */
    private final Map<Long, Long> teamOf = new HashMap<>();
    private TeamTrailServiceImpl service;
    private CmsTopic topic;
    private CmsActivity activity;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RedisCache redis = mock(RedisCache.class);
        doAnswer(inv -> { hashes.computeIfAbsent(inv.getArgument(0), k -> new LinkedHashMap<>()).put(inv.getArgument(1), inv.getArgument(2)); return null; })
                .when(redis).setCacheMapValue(anyString(), anyString(), any());
        when(redis.getCacheMapValue(anyString(), anyString())).thenAnswer(inv -> {
            Map<String, Object> h = hashes.get(inv.<String>getArgument(0));
            return h == null ? null : h.get(inv.<String>getArgument(1));
        });
        when(redis.getCacheMap(anyString())).thenAnswer(inv -> {
            Map<String, Object> h = hashes.get(inv.<String>getArgument(0));
            return h == null ? new HashMap<>() : new LinkedHashMap<>(h);
        });
        when(redis.deleteCacheMapValue(anyString(), anyString())).thenAnswer(inv -> {
            Map<String, Object> h = hashes.get(inv.<String>getArgument(0));
            return h != null && h.remove(inv.<String>getArgument(1)) != null;
        });
        when(redis.expire(anyString(), anyLong(), any(TimeUnit.class))).thenAnswer(inv -> {
            ttl.put(inv.getArgument(0), inv.<TimeUnit>getArgument(2).toMillis(inv.<Long>getArgument(1)));
            return true;
        });

        activity = new CmsActivity();
        activity.setId(10L);
        activity.setTopicId(7L);
        activity.setDelFlag(0);
        activity.setEndDate(new Date(System.currentTimeMillis() + 3L * 3600_000L));
        topic = new CmsTopic();
        topic.setId(7L);
        CmsActivityMapper activities = mock(CmsActivityMapper.class);
        when(activities.selectCmsActivityById(10L)).thenAnswer(inv -> activity);
        CmsActivity other = new CmsActivity();
        other.setId(11L); other.setTopicId(7L); other.setDelFlag(0);
        when(activities.selectCmsActivityById(11L)).thenReturn(other);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        when(topics.selectCmsTopicById(7L)).thenAnswer(inv -> topic);

        PlayTeamMapper teams = mock(PlayTeamMapper.class);
        when(teams.hasPaidRegistration(any(), eq(2), any())).thenAnswer(inv ->
                paid.getOrDefault(inv.<Long>getArgument(2), new HashSet<>()).contains(inv.<Long>getArgument(0)) ? 1 : 0);
        when(teams.selectTrailParticipants(any(), any())).thenAnswer(inv -> {
            Long aid = inv.getArgument(0);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Long id : (Collection<Long>) inv.getArgument(1)) {
                if (!paid.getOrDefault(aid, new HashSet<>()).contains(id)) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("memberId", id);
                row.put("nickname", "玩家" + id);
                row.put("avatar", "https://img/" + id + ".png");
                row.put("teamId", teamOf.get(id));
                row.put("teamTitle", teamOf.get(id) == null ? null : "队" + teamOf.get(id));
                row.put("done", serverDone.getOrDefault(id, 0L));
                rows.add(row);
            }
            return rows;
        });
        service = new TeamTrailServiceImpl();
        ReflectionTestUtils.setField(service, "redisCache", redis);
        ReflectionTestUtils.setField(service, "activityMapper", activities);
        ReflectionTestUtils.setField(service, "topicMapper", topics);
        ReflectionTestUtils.setField(service, "playTeamMapper", teams);

        paid.put(10L, new HashSet<>(Arrays.asList(1L, 2L, 3L, 4L)));
        paid.put(11L, new HashSet<>(Arrays.asList(9L)));
        teamOf.put(1L, 100L); teamOf.put(2L, 100L); teamOf.put(3L, 200L);
        serverDone.put(3L, 2L);
    }

    /** 第十二轮 k2(j6 N5):本场已完成站数的服务端事实(player_node_progress),按会员。 */
    private final Map<Long, Long> serverDone = new java.util.HashMap<>();

    private static List<List<Double>> walk(double lat0, int n) {
        List<List<Double>> pts = new ArrayList<>();
        for (int i = 0; i < n; i++) pts.add(Arrays.asList(lat0 + i * 0.0003, 121.47 + i * 0.0001));
        return pts;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> teams(Map<String, Object> out) {
        Object t = out.get("teams");
        return t == null ? new ArrayList<>() : (List<Map<String, Object>>) t;
    }

    @Test
    void sameSessionTeamsSeeEachOtherButNotThemselves() {
        service.sync(3L, 10L, walk(31.20, 5), 2, false);
        service.sync(2L, 10L, walk(31.21, 5), 1, false);
        Map<String, Object> out = service.sync(1L, 10L, walk(31.22, 3), 1, false);
        assertEquals(true, out.get("available"));
        List<Map<String, Object>> rows = teams(out);
        assertEquals(1, rows.size(), "own team (member 2) is not returned, team 200 is");
        Map<String, Object> t = rows.get(0);
        assertEquals("队200", t.get("name"));
        assertEquals(2, t.get("done"));
        assertFalse(t.containsKey("memberId"));
        assertFalse(String.valueOf(t).contains("200=") || String.valueOf(t.get("key")).equals("200"));
        assertEquals(12, String.valueOf(t.get("key")).length());
    }

    @Test
    void progressComesFromServerNotFromTheClient() {
        service.sync(3L, 10L, walk(31.20, 5), 999, false);
        Map<String, Object> out = service.sync(1L, 10L, walk(31.22, 3), 1, false);
        assertEquals(2, teams(out).get(0).get("done"), "客户端报 999,别队看到的仍是服务端完成的 2 站");
    }

    @Test
    void nothingIsTakenOrServedBeforeTheSessionStarts() {
        activity.setStartDate(new Date(System.currentTimeMillis() + 12L * 24L * 3600_000L));
        assertEquals(false, service.sync(3L, 10L, walk(31.20, 5), 2, false).get("available"));
        assertEquals(false, service.state(3L, 10L).get("available"));
        assertFalse(hashes.containsKey(TeamTrailServiceImpl.PTS + 10L));
        activity.setStartDate(new Date(System.currentTimeMillis() - 60_000L));
        assertEquals(true, service.sync(3L, 10L, walk(31.20, 5), 2, false).get("available"));
    }

    @Test
    void pointsAreStoredCoarselyAndThinned() {
        List<List<Double>> pts = new ArrayList<>();
        pts.add(Arrays.asList(31.123456789, 121.987654321));
        pts.add(Arrays.asList(31.123457, 121.987655)); // < 10 m, dropped
        pts.add(Arrays.asList(Double.NaN, 121.0));
        pts.add(Arrays.asList(95.0, 121.0));
        service.sync(3L, 10L, pts, 0, false);
        String raw = String.valueOf(hashes.get(TeamTrailServiceImpl.PTS + 10L).get("3"));
        assertTrue(raw.contains("[31.1235,121.9877]"), raw);
        assertFalse(raw.contains("31.123457"));
        com.alibaba.fastjson2.JSONArray kept = new com.alibaba.fastjson2.JSONArray();
        for (int i = 0; i < 30; i++) TeamTrailServiceImpl.appendPoints(kept, walk(31 + i * 0.02, 40));
        assertTrue(kept.size() <= TeamTrailServiceImpl.MAX_POINTS);
    }

    @Test
    void retentionEndsWithTheSession() {
        service.sync(3L, 10L, walk(31.20, 2), 0, false);
        long left = ttl.get(TeamTrailServiceImpl.PTS + 10L);
        assertTrue(left <= 4L * 3600_000L + 1000L && left > 3L * 3600_000L, "end + 1h, got " + left);
        activity.setEndDate(new Date(System.currentTimeMillis() - 2L * 3600_000L));
        assertEquals(false, service.sync(3L, 10L, walk(31.20, 2), 0, false).get("available"));
    }

    @Test
    void playerFromAnotherSessionGetsNothing() {
        service.sync(3L, 10L, walk(31.20, 5), 1, false);
        Map<String, Object> out = service.sync(9L, 10L, walk(31.20, 5), 1, false);
        assertEquals(false, out.get("available"));
        assertNull(out.get("teams"));
        assertFalse(hashes.get(TeamTrailServiceImpl.PTS + 10L).containsKey("9"), "outsider's points are not stored");
        assertEquals(false, service.state(9L, 10L).get("available"));
    }

    @Test
    void guestGetsNothing() {
        assertEquals(false, service.sync(null, 10L, walk(31.20, 5), 1, false).get("available"));
        assertEquals(false, service.sync(0L, 10L, walk(31.20, 5), 1, false).get("available"));
        assertEquals(false, service.state(null, 10L).get("available"));
    }

    @Test
    void unpaidMemberGetsNothing() {
        service.sync(3L, 10L, walk(31.20, 5), 1, false);
        Map<String, Object> out = service.sync(5L, 10L, walk(31.20, 5), 1, false);
        assertEquals(false, out.get("available"));
        assertNull(out.get("teams"));
    }

    @Test
    void optedOutPersonGetsNothingAndShowsNowhere() {
        service.sync(3L, 10L, walk(31.20, 5), 1, false);
        service.sync(1L, 10L, walk(31.22, 5), 1, false);
        service.consent(3L, 10L, false);
        assertFalse(hashes.get(TeamTrailServiceImpl.PTS + 10L).containsKey("3"), "past points deleted");
        Map<String, Object> mine = service.sync(3L, 10L, walk(31.20, 5), 1, false);
        assertEquals(false, mine.get("share"));
        assertTrue(teams(mine).isEmpty());
        assertFalse(hashes.get(TeamTrailServiceImpl.PTS + 10L).containsKey("3"));
        assertTrue(teams(service.sync(1L, 10L, walk(31.22, 1), 1, false)).isEmpty(), "member 3 shows nowhere");
    }

    @Test
    void leftOrFinishedShowsNowhere() {
        service.sync(3L, 10L, walk(31.20, 5), 1, false);
        service.sync(4L, 10L, walk(31.25, 5), 1, false);
        assertEquals(2, teams(service.sync(1L, 10L, walk(31.22, 1), 1, false)).size());
        paid.get(10L).remove(4L); // refunded / left
        service.sync(3L, 10L, null, 3, true); // finished
        assertFalse(hashes.get(TeamTrailServiceImpl.PTS + 10L).containsKey("3"));
        assertTrue(teams(service.sync(1L, 10L, walk(31.22, 1), 1, false)).isEmpty());
    }

    @Test
    void authorCanTurnItOff() {
        topic.setPublishedThemeRuntimeConfigJson("{\"schemaVersion\":1,\"teamTrail\":false}");
        assertEquals(false, service.state(1L, 10L).get("available"));
        assertEquals(false, service.sync(1L, 10L, walk(31.2, 2), 0, false).get("available"));
        topic.setPublishedThemeRuntimeConfigJson("{\"schemaVersion\":1}");
        assertEquals(true, service.state(1L, 10L).get("available"));
    }

    @Test
    void soloThemeIsOffUnlessAuthorTurnsItOn() {
        topic.setPublishedThemeRuntimeConfigJson("{\"schemaVersion\":1,\"gameplayRuntime\":{\"runtimeScope\":\"PLAYER\"}}");
        assertEquals(false, service.state(1L, 10L).get("available"));
        topic.setPublishedThemeRuntimeConfigJson("{\"schemaVersion\":1,\"teamTrail\":true,\"gameplayRuntime\":{\"runtimeScope\":\"PLAYER\"}}");
        assertEquals(true, service.state(1L, 10L).get("available"));
        topic.setPublishedThemeRuntimeConfigJson("{\"schemaVersion\":1,\"gameplayRuntime\":{\"runtimeScope\":\"TEAM\"}}");
        assertEquals(true, service.state(1L, 10L).get("available"));
    }

    @Test
    void authorSwitchOffDeletesTheCallersStoredPoints() {
        service.sync(1L, 10L, walk(31.2, 3), 0, false);
        service.sync(3L, 10L, walk(31.3, 3), 0, false);
        assertTrue(hashes.get("team_trail:pts:10").containsKey("1"));
        topic.setPublishedThemeRuntimeConfigJson("{\"schemaVersion\":1,\"teamTrail\":false}");
        assertEquals(false, service.sync(1L, 10L, walk(31.2, 1), 0, true).get("available"));
        assertFalse(hashes.get("team_trail:pts:10").containsKey("1"));
        assertEquals(false, service.state(3L, 10L).get("available"));
        assertFalse(hashes.get("team_trail:pts:10").containsKey("3"));
    }

    @Test
    void defaultIsOnAndIsOneConfigValue() {
        Map<String, Object> s = service.state(1L, 10L);
        assertEquals(true, s.get("share"));
        assertEquals(false, s.get("decided"));
        ReflectionTestUtils.setField(service, "defaultShare", false);
        assertEquals(false, service.state(1L, 10L).get("share"));
        assertEquals(false, service.sync(1L, 10L, walk(31.2, 2), 0, false).get("share"));
    }
}
