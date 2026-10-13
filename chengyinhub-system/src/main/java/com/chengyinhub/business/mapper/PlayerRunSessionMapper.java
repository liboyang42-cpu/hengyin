package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 玩家进行中的游戏会话(第二轮拍板 22,跨设备续玩)。表 player_run_session。
 * 作用域:activityId&gt;0 = 活动场次(topicId 由服务端按活动回填);activityId=0 = 自玩(按 topicId)。
 */
public interface PlayerRunSessionMapper {
    int upsertPaused(@Param("memberId") Long memberId, @Param("activityId") Long activityId,
                     @Param("topicId") Long topicId, @Param("elapsedSeconds") Integer elapsedSeconds,
                     @Param("savedAt") Long savedAt);

    Map<String, Object> selectScope(@Param("memberId") Long memberId, @Param("activityId") Long activityId,
                                    @Param("topicId") Long topicId);

    /** 结束墓碑:只改已有行(从没同步过的会话没有别的设备能还魂)。 */
    int markEnded(@Param("memberId") Long memberId, @Param("activityId") Long activityId,
                  @Param("topicId") Long topicId, @Param("savedAt") Long savedAt);

    /** 仍可继续的会话(PAUSED):活动未删/未取消/未过结束日且报名成功;自玩通行证有效。按最近落盘倒序。 */
    List<Map<String, Object>> selectContinuableByMember(@Param("memberId") Long memberId, @Param("limit") int limit);
}
