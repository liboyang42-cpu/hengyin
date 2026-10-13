package com.chengyinhub.business.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.domain.PlayerGrowth;

/**
 * 玩家成长体系(等级/里程/连续打卡 + 徽章)。
 * 接在 ApiPlayProgressController.advanceProgress:节点完成调 onNodeDone,通关调 onTopicComplete。
 *
 * @author cy
 * @date 2026-06-21
 */
public interface IPlayerProgressionService
{
    /** 读成长(无则返回默认值对象,不落库) */
    PlayerGrowth getGrowth(Long memberId);

    /**
     * RUN-13 等级单一真源的批量读口(动态广场/评论/俱乐部名单等「看别人等级」的路径)。
     * 兜底口径与 {@link #getGrowth} 一致:player_growth 无行 = Lv.1,返回 map 覆盖每个入参 id。
     */
    Map<Long, Integer> levelsByMemberIds(Collection<Long> memberIds);

    /** 列出已获徽章 */
    List<PlayerBadge> listBadges(Long memberId);

    /** 节点首次完成:累计+1、连续打卡、等级,发首枚/连续徽章。返回本次新得徽章 */
    List<PlayerBadge> onNodeDone(Long memberId);

    /** 通关:累加主题里程,发通关/里程徽章。返回本次新得徽章 */
    List<PlayerBadge> onTopicComplete(Long memberId, Long topicId);
}
