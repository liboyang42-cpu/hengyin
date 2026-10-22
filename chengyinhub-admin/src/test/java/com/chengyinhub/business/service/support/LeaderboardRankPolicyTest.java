package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.vo.LeaderboardItemVO;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaderboardRankPolicyTest {

    @Test
    void onlyBranchAndPersonalGameplayTopicsRankByRoute() {
        assertFalse(LeaderboardRankPolicy.isRouteRanked(null));
        CmsTopic linear = new CmsTopic();
        assertFalse(LeaderboardRankPolicy.isRouteRanked(linear), "无发布快照 = 线性,口径不变");
        linear.setPublishedRouteGraphJson("{\"runtimeSnapshotVersion\":1,\"routeMode\":\"LINEAR\"}");
        assertFalse(LeaderboardRankPolicy.isRouteRanked(linear));

        CmsTopic branch = new CmsTopic();
        branch.setPublishedRouteGraphJson("{\"runtimeSnapshotVersion\":1,\"routeMode\":\"BRANCH_GRAPH\"}");
        assertTrue(LeaderboardRankPolicy.isRouteRanked(branch));

        CmsTopic gameplay = new CmsTopic();
        gameplay.setPublishedRouteGraphJson("{\"runtimeSnapshotVersion\":1,\"routeMode\":\"LINEAR\","
                + "\"themeConfig\":{\"gameplayRuntime\":{\"quests\":[{\"key\":\"q1\"}]}}}");
        assertTrue(LeaderboardRankPolicy.isRouteRanked(gameplay), "个人玩法完成事实在会话,节点数恒 0");

        CmsTopic shared = new CmsTopic();
        shared.setPublishedRouteGraphJson("{\"runtimeSnapshotVersion\":1,\"routeMode\":\"LINEAR\","
                + "\"themeConfig\":{\"gameplayRuntime\":{\"runtimeScope\":\"SESSION\",\"quests\":[{\"key\":\"q1\"}]}}}");
        assertFalse(LeaderboardRankPolicy.isRouteRanked(shared), "与 genericConfig 同口径:只认 PLAYER 作用域");

        CmsTopic broken = new CmsTopic();
        broken.setPublishedThemeRuntimeConfigJson("{not json");
        assertFalse(LeaderboardRankPolicy.isRouteRanked(broken), "配置坏了按线性排,不整页失败");
    }

    private static LeaderboardItemVO route(long member, boolean ended, Long elapsed, long score) {
        LeaderboardItemVO row = new LeaderboardItemVO();
        row.setMemberId(member);
        row.setReachedEnding(ended);
        row.setElapsedSeconds(elapsed);
        row.setScore(score);
        return row;
    }

    private static LeaderboardItemVO linear(long member, long score, Date finishedAt) {
        LeaderboardItemVO row = new LeaderboardItemVO();
        row.setMemberId(member);
        row.setScore(score);
        row.setFinishedAt(finishedAt);
        return row;
    }

    private static List<Integer> ranks(List<LeaderboardItemVO> rows) {
        return rows.stream().map(LeaderboardItemVO::getRank).collect(Collectors.toList());
    }

    @Test
    void routeTiesOnElapsedShareRankAndUnfinishedTieOnScore() {
        List<LeaderboardItemVO> rows = Arrays.asList(
                route(1, true, 600L, 1), route(2, true, 600L, 5), route(3, true, 900L, 2),
                route(4, false, null, 3), route(5, false, null, 3), route(6, false, null, 1));
        LeaderboardRankPolicy.assignRanks(rows, true);
        assertEquals(Arrays.asList(1, 1, 3, 4, 4, 6), ranks(rows));
    }

    @Test
    void linearRanksStayPositionalUnlessScoreAndFinishTimeAreIdentical() {
        Date t1 = new Date(1000L);
        Date t2 = new Date(2000L);
        List<LeaderboardItemVO> rows = Arrays.asList(
                linear(1, 4, t1), linear(2, 4, t1), linear(3, 4, t2), linear(4, 2, null), linear(5, 2, null));
        LeaderboardRankPolicy.assignRanks(rows, false);
        assertEquals(Arrays.asList(1, 1, 3, 4, 5), ranks(rows), "同数先完成者靠前不变;完全同刻才并列");
    }
}
