package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.PlayAttemptLog;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * M9 节点级游玩尝试日志 Mapper。
 */
public interface PlayAttemptLogMapper {
    int insertPlayAttemptLog(PlayAttemptLog log);

    /** 城市定向谜题已使用的最高提示级别；0 表示尚未使用提示。 */
    int selectMaxPuzzleHintLevel(@Param("memberId") Long memberId,
            @Param("topicId") Long topicId,
            @Param("nodeId") Long nodeId,
            @Param("activityId") Long activityId);

    /** 一次读回当前主题各谜题已使用的最高提示级别，供重进页面恢复。 */
    List<Map<String, Object>> selectPuzzleHintLevels(@Param("memberId") Long memberId,
            @Param("topicId") Long topicId,
            @Param("activityId") Long activityId);

    /** 返回持久原请求指纹；调用方必须先校验读取授权，再比较指纹后回放。 */
    PlayAttemptLog selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    /** Photo: current read, including a prior cached miss, after the original session lock. */
    PlayAttemptLog selectByIdempotencyKeyForUpdate(@Param("idempotencyKey") String idempotencyKey);

    List<PlayAttemptLog> selectPreferenceResults(@Param("memberId") Long memberId,
                                                 @Param("topicId") Long topicId);
}
