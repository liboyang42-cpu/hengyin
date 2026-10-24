package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.vo.LeaderboardItemVO;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;

/**
 * D-13 排行榜口径(N-02):一主题一榜。
 * <ul>
 *   <li>分支主题(已发布 BRANCH_GRAPH)与个人玩法主题(gameplayRuntime PLAYER quests)按会话排:
 *       到达结局优先 → 结局总用时短者优先;这两类主题的完成事实在 topic_route_session,
 *       个人玩法的扫码/答题只落 status=0 到店凭证,按节点数排恒为 0 分。</li>
 *   <li>线性主题维持原口径:完成节点数降序 → 最后完成时间早者优先。</li>
 *   <li>排序键完全相同的相邻行并列同名次(1,1,3)。</li>
 * </ul>
 */
public final class LeaderboardRankPolicy {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private LeaderboardRankPolicy() {
    }

    public static boolean isRouteRanked(CmsTopic topic) {
        if (topic == null) return false;
        return TopicRouteModePolicy.isEffectiveBranchGraph(topic, null) || personalGameplayTopic(topic);
    }

    /** 与 TopicRouteRuntimeServiceImpl#requireOpeningAccess 的 generic 判据同口径(genericConfig),改动须同步。 */
    static boolean personalGameplayTopic(CmsTopic topic) {
        try {
            if (genericConfig(ThemeRuntimeConfig.parse(topic.getPublishedThemeRuntimeConfigJson()))) return true;
            String graph = topic.getPublishedRouteGraphJson();
            if (graph == null || graph.trim().isEmpty()) return false;
            return genericConfig(JSON.readTree(graph).path("themeConfig"));
        } catch (java.io.IOException | RuntimeException invalid) {
            return false; // 已发布配置不可解析时按线性口径排,不让排行榜因配置异常整页失败
        }
    }

    private static boolean genericConfig(JsonNode config) {
        if (config == null) return false;
        JsonNode game = config.path("gameplayRuntime");
        return "PLAYER".equals(game.path("runtimeScope").asText("PLAYER")) && game.path("quests").size() > 0;
    }

    /** 按列表序补名次;与上一行排序键完全相同则并列。 */
    public static void assignRanks(List<LeaderboardItemVO> list, boolean routeRanked) {
        if (list == null) return;
        for (int i = 0; i < list.size(); i++) {
            LeaderboardItemVO row = list.get(i);
            if (i > 0 && sameKey(list.get(i - 1), row, routeRanked)) {
                row.setRank(list.get(i - 1).getRank());
            } else {
                row.setRank(i + 1);
            }
        }
    }

    private static boolean sameKey(LeaderboardItemVO a, LeaderboardItemVO b, boolean routeRanked) {
        if (routeRanked) {
            boolean endedA = Boolean.TRUE.equals(a.getReachedEnding());
            boolean endedB = Boolean.TRUE.equals(b.getReachedEnding());
            if (endedA != endedB) return false;
            return endedA ? a.getElapsedSeconds() != null && a.getElapsedSeconds().equals(b.getElapsedSeconds())
                    : Objects.equals(a.getScore(), b.getScore());
        }
        return Objects.equals(a.getScore(), b.getScore())
                && a.getFinishedAt() != null && a.getFinishedAt().equals(b.getFinishedAt());
    }
}
