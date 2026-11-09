package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.vo.LeaderboardItemVO;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 第十轮 f2 · j2 F-08:导演台的定向广播收件人、队伍成员与排行榜用报名姓名,不再四个「城瘾用户」。 */
class GameSessionOrganiserNamesTest {

    private static final long PLAYER = 990006L;

    @Test
    void directorRecipientsAndLeaderboardUseTheSameNames() {
        GameSessionRuntimeServiceImpl service = new GameSessionRuntimeServiceImpl();
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("teamId", 1L); a.put("memberId", PLAYER); a.put("memberName", "城瘾用户"); a.put("realNameStored", "林一帆");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("teamId", 1L); b.put("memberId", 990008L); b.put("memberName", "城瘾用户"); b.put("realNameStored", null);
        List<Map<String, Object>> named = service.organiserNamedRows(Arrays.asList(a, b));
        assertEquals("林一帆", named.get(0).get("memberName"));
        assertEquals("城瘾用户", named.get(1).get("memberName"));
        assertFalse(named.get(0).containsKey("realNameStored"), "密文列不下发");

        LeaderboardItemVO first = new LeaderboardItemVO();
        first.setMemberId(PLAYER); first.setNickname("城瘾用户");
        LeaderboardItemVO other = new LeaderboardItemVO();
        other.setMemberId(990009L); other.setNickname("城瘾用户");
        List<LeaderboardItemVO> board = service.organiserNamedLeaderboard(Arrays.asList(first, other), named);
        assertEquals("林一帆", board.get(0).getNickname());
        assertEquals("城瘾用户", board.get(1).getNickname());
    }

    // 第十轮 f2 walk:没组队的玩家不在成员投影里,结束后榜上是「成员#990008」「成员#990009」。
    @Test
    void leaderboardNamesPlayersWithoutATeamFromTheirSignup() {
        GameSessionRuntimeServiceImpl service = new GameSessionRuntimeServiceImpl();
        Map<String, Object> teamed = new LinkedHashMap<>();
        teamed.put("teamId", 1L); teamed.put("memberId", PLAYER); teamed.put("memberName", "城瘾用户"); teamed.put("realNameStored", "林一帆");
        List<Map<String, Object>> named = service.organiserNamedRows(Arrays.asList(teamed));
        Map<String, Object> soloSignup = new LinkedHashMap<>();
        soloSignup.put("memberId", 990008L); soloSignup.put("realNameStored", "陈小满");
        Map<String, Object> olderSignup = new LinkedHashMap<>();
        olderSignup.put("memberId", 990008L); olderSignup.put("realNameStored", "旧名字");
        LeaderboardItemVO first = new LeaderboardItemVO();
        first.setMemberId(PLAYER); first.setNickname("城瘾用户");
        LeaderboardItemVO solo = new LeaderboardItemVO();
        solo.setMemberId(990008L); solo.setNickname("城瘾用户");
        LeaderboardItemVO unknown = new LeaderboardItemVO();
        unknown.setMemberId(990010L); unknown.setNickname("城瘾用户");
        List<LeaderboardItemVO> board = service.organiserNamedLeaderboard(Arrays.asList(first, solo, unknown), named,
                Arrays.asList(soloSignup, olderSignup));
        assertEquals("林一帆", board.get(0).getNickname());
        assertEquals("陈小满", board.get(1).getNickname(), "没组队也用本场最近一张报名的姓名");
        assertEquals("城瘾用户", board.get(2).getNickname());
    }

}
