package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.RewardDropLog;
import org.apache.ibatis.annotations.Param;

/**
 * M6 掉落流水 Mapper。
 */
public interface RewardDropLogMapper {
    /** 该(会员,场景,主题,节点,活动)是否已掉过(uk 幂等预检) */
    int countByUk(@Param("memberId") Long memberId, @Param("topicId") Long topicId,
                  @Param("nodeId") Long nodeId, @Param("activityId") Long activityId,
                  @Param("sceneKey") String sceneKey);

    /** 插入掉落流水(uk 冲突交由上层 catch DuplicateKey 兜底并发) */
    int insertRewardDropLog(RewardDropLog log);
}
