package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.OrientSessionClock;
import com.chengyinhub.business.domain.OrientSessionRow;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/** Doc2 F1a 定向会话:phase 列与 orient_session_clock 的唯一读写入口,所有语句都带 member_id 归属。 */
public interface OrientSessionMapper {
    List<OrientSessionRow> selectOwnedSessions(@Param("memberId") Long memberId, @Param("topicId") Long topicId,
                                              @Param("activityId") Long activityId);
    OrientSessionRow selectSession(@Param("id") Long id, @Param("memberId") Long memberId);
    OrientSessionRow selectSessionForUpdate(@Param("id") Long id, @Param("memberId") Long memberId);
    /** 仅进行中且尚未进入终态的局可改 phase。 */
    int updateRunningPhase(@Param("id") Long id, @Param("memberId") Long memberId, @Param("phase") String phase,
                           @Param("updateTime") Date updateTime);
    /** status=0 → 终态;并发下只有一个成功。 */
    int terminate(@Param("id") Long id, @Param("memberId") Long memberId, @Param("status") int status,
                  @Param("phase") String phase, @Param("endedAt") Date endedAt);
    OrientSessionClock selectClock(@Param("sessionId") Long sessionId, @Param("memberId") Long memberId);
    int insertClock(OrientSessionClock clock);
    int closeClock(@Param("sessionId") Long sessionId, @Param("memberId") Long memberId,
                   @Param("endedAt") Date endedAt, @Param("elapsedMs") long elapsedMs);
    /** 服务端已认定(status=1)且在 since 之后完成的节点进度时间;没有返回 null。 */
    Date selectVerifiedCompletion(@Param("memberId") Long memberId, @Param("activityId") Long activityId,
                                  @Param("topicId") Long topicId, @Param("nodeId") Long nodeId,
                                  @Param("playInstanceId") String playInstanceId, @Param("since") Date since);
}
