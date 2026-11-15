package com.chengyinhub.business.growthboard;

import com.chengyinhub.business.domain.dto.LeaderboardQueryDTO;
import com.chengyinhub.business.domain.vo.LeaderboardItemVO;
import com.chengyinhub.business.mapper.GrowthScopedBoardMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** G1 game-loop (GRO-15): friends and district growth boards. */
class GrowthScopedLeaderboardTest {
    private static final long ME = 7L;
    private GrowthScopedBoardMapper mapper;
    private GrowthScopedLeaderboard board;

    @BeforeEach
    void setUp() {
        mapper = mock(GrowthScopedBoardMapper.class);
        board = new GrowthScopedLeaderboard(mapper);
        board.setClock(Clock.fixed(Instant.parse("2026-11-18T04:00:00Z"), ZoneId.of("Asia/Shanghai")));
    }

    private static Map<String, Object> row(long id, long score, long created) {
        Map<String, Object> row = new HashMap<>();
        row.put("memberId", id);
        row.put("nickname", "p" + id);
        row.put("score", score);
        row.put("createdAt", new Date(created));
        return row;
    }

    private static LeaderboardQueryDTO query(String metric, String period, String scope) {
        LeaderboardQueryDTO q = new LeaderboardQueryDTO();
        q.setMetric(metric); q.setPeriod(period); q.setScope(scope);
        return q;
    }

    @Test
    void scopeIsOnlyFriendsOrDistrictElseTheGlobalBoard() {
        assertEquals("friends", GrowthScopedLeaderboard.normalizeScope(" Friends "));
        assertEquals("district", GrowthScopedLeaderboard.normalizeScope("district"));
        assertNull(GrowthScopedLeaderboard.normalizeScope(null));
        assertNull(GrowthScopedLeaderboard.normalizeScope("global"));
        assertNull(GrowthScopedLeaderboard.normalizeScope("everyone"));
    }

    @Test
    void rankingMatchesTheGlobalOrderAndSkipsZeroScores() {
        List<Map<String, Object>> rows = List.of(row(3, 50, 2_000), row(ME, 50, 1_000), row(9, 80, 5_000), row(4, 0, 10));
        GrowthScopedLeaderboard.Ranked ranked = GrowthScopedLeaderboard.rank(rows, ME, 50);
        assertEquals(List.of(9L, ME, 3L), ranked.list.stream().map(LeaderboardItemVO::getMemberId).toList());
        assertEquals(List.of(1, 2, 3), ranked.list.stream().map(LeaderboardItemVO::getRank).toList());
        assertEquals(2, ranked.me.getRank());
        assertEquals(50L, ranked.me.getScore());
        assertEquals("TOP80%", ranked.me.getRankPercentage());
    }

    @Test
    void viewerWithZeroScoreHasNoRankButStillSeesTheirRow() {
        GrowthScopedLeaderboard.Ranked ranked = GrowthScopedLeaderboard.rank(List.of(row(ME, 0, 1), row(3, 10, 1)), ME, 50);
        assertEquals(1, ranked.list.size());
        assertNull(ranked.me.getRank());
        assertEquals(0L, ranked.me.getScore());
        assertEquals(ME, ranked.me.getMemberId());
    }

    @Test
    void limitCutsTheListButNotTheViewerRank() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (long id = 100; id < 110; id++) rows.add(row(id, 1000 - id, 1));
        rows.add(row(ME, 1, 1));
        GrowthScopedLeaderboard.Ranked ranked = GrowthScopedLeaderboard.rank(rows, ME, 3);
        assertEquals(3, ranked.list.size());
        assertEquals(11, ranked.me.getRank());
    }

    @Test
    void friendsBoardScoresOnlyVisibleFriendsPlusTheViewer() {
        when(mapper.selectFriendCandidates(ME, GrowthScopedLeaderboard.CANDIDATE_CAP)).thenReturn(List.of(3L, 9L, ME));
        when(mapper.selectScores(eq("point"), eq("total"), any(), any(), any())).thenAnswer(call -> {
            Collection<Long> ids = call.getArgument(4);
            assertEquals(List.of(ME, 3L, 9L), new ArrayList<>(ids));
            return List.of(row(3, 30, 1), row(9, 90, 1), row(ME, 60, 1));
        });
        Map<String, Object> out = board.read(ME, query(null, null, "friends"), "friends");
        assertEquals("ready", out.get("state"));
        assertEquals("friends", out.get("scope"));
        assertEquals(Boolean.FALSE, out.get("visible"));
        @SuppressWarnings("unchecked") List<LeaderboardItemVO> list = (List<LeaderboardItemVO>) out.get("list");
        assertEquals(List.of(9L, ME, 3L), list.stream().map(LeaderboardItemVO::getMemberId).toList());
        assertEquals(2, ((LeaderboardItemVO) out.get("me")).getRank());
        verify(mapper, never()).selectDistrictCandidates(anyLong(), anyLong(), anyInt());
    }

    @Test
    void districtBoardWithoutAnAreaSaysSoInsteadOfInventingOne() {
        when(mapper.selectAreaId(ME)).thenReturn(null);
        Map<String, Object> out = board.read(ME, query("exp", "week", "district"), "district");
        assertEquals("noArea", out.get("state"));
        assertEquals(List.of(), out.get("list"));
        verify(mapper, never()).selectScores(any(), any(), any(), any(), any());
    }

    @Test
    void districtWeekBoardUsesTheAreaAndTheCurrentMondayToMonday() {
        when(mapper.selectAreaId(ME)).thenReturn(310115L);
        when(mapper.selectDistrictCandidates(ME, 310115L, GrowthScopedLeaderboard.CANDIDATE_CAP)).thenReturn(List.of(5L));
        when(mapper.selectScores(eq("exp"), eq("week"), any(), any(), any())).thenAnswer(call -> {
            Date start = call.getArgument(2), end = call.getArgument(3);
            assertEquals(Instant.parse("2026-11-15T16:00:00Z"), start.toInstant()); // Monday 16 Nov, 00:00 Beijing
            assertEquals(Instant.parse("2026-11-22T16:00:00Z"), end.toInstant());
            return List.of(row(5, 12, 1));
        });
        Map<String, Object> out = board.read(ME, query("exp", "week", "district"), "district");
        assertEquals("ready", out.get("state"));
        assertEquals("exp", out.get("metric"));
        assertEquals("week", out.get("period"));
        assertNull(((LeaderboardItemVO) out.get("me")).getRank());
    }

    @Test
    void visibilityIsOptInAndWritesThrough() {
        assertFalse(board.isVisible(ME));
        when(mapper.selectVisible(ME)).thenReturn(1);
        assertTrue(board.setVisible(ME, true));
        verify(mapper).upsertVisible(eq(ME), eq(1), any(Date.class));
    }

    private String sql(String id, Map<String, Object> params) throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/business/GrowthScopedBoardMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration.getMappedStatement("com.chengyinhub.business.mapper.GrowthScopedBoardMapper." + id)
                .getBoundSql(params).getSql().replaceAll("\\s+", " ").toLowerCase();
    }

    @Test
    void candidateSqlRequiresTheOptInAndHonoursLeaderboardOut() throws Exception {
        for (String id : List.of("selectFriendCandidates", "selectDistrictCandidates")) {
            Map<String, Object> p = new HashMap<>();
            p.put("memberId", ME); p.put("areaId", 1L); p.put("cap", 10);
            String text = sql(id, p);
            assertTrue(text.contains("join growth_board_visibility v on v.member_id = c.mid and v.visible = 1"), text);
            assertTrue(text.contains("ifnull(p.leaderboard_scope, 'in') <> 'out'"), text);
            assertTrue(text.contains("c.mid <> ?"), text);
        }
        Map<String, Object> p = new HashMap<>();
        p.put("memberId", ME); p.put("cap", 10);
        String friends = sql("selectFriendCandidates", p);
        assertTrue(friends.contains("from ums_member_follow f where f.member_id = ?"), friends);
        assertTrue(friends.contains("join club_member mate on mate.club_id = mine.club_id and mate.status = 1 and mate.del_flag = 0"), friends);
    }

    @Test
    void scoreSqlPicksTheRightSourcePerMetricAndPeriod() throws Exception {
        Map<String, String> expected = Map.of(
                "point/total", "ifnull(m.point, 0) as score",
                "point/week", "from ums_member_points_detail",
                "exp/total", "left join member_growth g on g.member_id = m.id",
                "exp/week", "from member_exp_earned");
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String[] parts = entry.getKey().split("/");
            Map<String, Object> p = new HashMap<>();
            p.put("metric", parts[0]); p.put("period", parts[1]);
            p.put("weekStart", new Date(0)); p.put("weekEnd", new Date(1));
            p.put("ids", List.of(ME, 3L));
            String text = sql("selectScores", p);
            assertTrue(text.contains(entry.getValue()), entry.getKey() + ": " + text);
            assertTrue(text.contains("where m.id in ( ? , ? ) and m.del_flag = 0 and m.role = 'player'"), text);
        }
    }
}
