package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.PlayArriveGuardLog;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * O-01 /arrive 位置守卫留痕。只在守卫开关非 off 时被调用;off 时不读不写此表。
 */
public interface PlayArriveGuardLogMapper {
    /** Exact original actor only; null/invalid parameters yield no row. No latest/current-run fallback. */
    PlayArriveGuardLog selectByIdAndMemberId(@Param("id") Long id, @Param("memberId") Long memberId);

    int insertGuardLog(PlayArriveGuardLog log);

    /** 节流参照:该玩家最近一次「被评估」的到达(不含节流拒绝本身,防连点把窗口无限顺延)。 */
    PlayArriveGuardLog selectLastEvaluated(@Param("memberId") Long memberId);

    /** 速度/位移锚点:该玩家最近一次被放行的到达(PASS 或 SHADOW)。 */
    PlayArriveGuardLog selectLastAccepted(@Param("memberId") Long memberId);

    /** 窗口累计:since 之后被放行的到达,按时间倒序取最近 limit 行(调用方自行转升序)。 */
    List<PlayArriveGuardLog> selectAcceptedSince(@Param("memberId") Long memberId, @Param("since") Date since,
                                                 @Param("limit") int limit);
}
