package com.chengyinhub.business.mapper.opsconsole;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/**
 * R10 BE-25 漫游与游玩运营只读视图。只出聚合与状态列：
 * 不读 roam_session.track_json、不读任何坐标、不读 roam_member_fog_cell.cell_key，也不读局内 state_json。
 */
public interface RoamPlayOpsMapper
{
    List<Map<String, Object>> sessions(@Param("memberId") Long memberId, @Param("status") Integer status,
                                       @Param("since") String since, @Param("until") String until, @Param("limit") int limit);

    Map<String, Object> sessionTotals(@Param("since") String since, @Param("until") String until);

    /** 每人揭雾格数（只有计数与首末时间），按格数降序。 */
    List<Map<String, Object>> fogByMember(@Param("memberId") Long memberId, @Param("limit") int limit);

    Map<String, Object> fogTotals();

    List<Map<String, Object>> teams(@Param("ownerType") Integer ownerType, @Param("ownerId") Long ownerId,
                                    @Param("status") Integer status, @Param("limit") int limit);

    List<Map<String, Object>> playRuns(@Param("memberId") Long memberId, @Param("topicId") Long topicId,
                                       @Param("runState") String runState, @Param("limit") int limit);

    List<Map<String, Object>> runtimeSessions(@Param("topicId") Long topicId, @Param("ownerType") Integer ownerType,
                                              @Param("ownerId") Long ownerId, @Param("status") Integer status,
                                              @Param("limit") int limit);
}
