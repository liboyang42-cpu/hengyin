package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.domain.dto.LeaderboardQueryDTO;
import com.chengyinhub.business.domain.vo.LeaderboardItemVO;
import com.chengyinhub.business.domain.vo.MemberLeaderboardVO;
import com.chengyinhub.business.service.IGrowthService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GrowthAnonymousLeaderboardTest {
    static LeaderboardItemVO item(Long member, String nick, long score, Integer rank) {
        LeaderboardItemVO v = new LeaderboardItemVO();
        v.setMemberId(member); v.setNickname(nick); v.setAvatar("https://a/" + member); v.setScore(score); v.setRank(rank);
        return v;
    }
    static MemberLeaderboardVO board(List<LeaderboardItemVO> list, LeaderboardItemVO me) {
        MemberLeaderboardVO b = new MemberLeaderboardVO(); b.setMetric("point"); b.setPeriod("total"); b.setList(list); b.setMe(me); return b;
    }
    @SuppressWarnings("unchecked")
    @Test void othersAreAnonymousAndSelfRowMatchesMe() {
        IGrowthService growth = mock(IGrowthService.class);
        LeaderboardItemVO me = item(9L, "me", 50, 2); me.setRankPercentage("TOP20%");
        when(growth.getLeaderboard(eq(9L), any())).thenReturn(board(List.of(item(3L, "x", 80, 1), item(9L, "me", 50, 2)), me));
        Map<String, Object> out = new GrowthAnonymousLeaderboard(growth).read(9L, new LeaderboardQueryDTO());
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("list");
        assertEquals(Map.of("rank", 1, "score", 80L, "nickname", "匿名玩家", "self", false), rows.get(0));
        assertEquals(Map.of("rank", 2, "score", 50L, "self", true), rows.get(1));
        assertFalse(rows.toString().contains("https://"), "头像与昵称不得外泄");
        assertEquals(Map.of("rank", 2, "score", 50L, "rankPercentage", "TOP20%", "self", true), out.get("me"));
        assertEquals("point", out.get("metric")); assertEquals("total", out.get("period"));
    }
    @SuppressWarnings("unchecked")
    @Test void unrankedMeHasNoRankAndInconsistentTieIsNotInvented() {
        IGrowthService growth = mock(IGrowthService.class);
        when(growth.getLeaderboard(eq(9L), any())).thenReturn(board(List.of(item(3L, "x", 80, 1)), item(9L, "me", 0, null)));
        Map<String, Object> out = new GrowthAnonymousLeaderboard(growth).read(9L, null);
        assertFalse(((Map<String, Object>) out.get("me")).containsKey("rank"));
        when(growth.getLeaderboard(eq(9L), any())).thenReturn(board(List.of(item(3L, "x", 80, 1)), item(9L, "me", 80, 1)));
        out = new GrowthAnonymousLeaderboard(growth).read(9L, null);
        assertFalse(((Map<String, Object>) out.get("me")).containsKey("rank"));
        when(growth.getLeaderboard(eq(9L), any())).thenReturn(board(List.of(item(3L, "x", 80, 1)), item(9L, "me", 5, 7)));
        out = new GrowthAnonymousLeaderboard(growth).read(9L, null);
        assertEquals(7, ((Map<String, Object>) out.get("me")).get("rank"));
    }
}
