package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * G1 game-loop (GRO-15):好友榜 / 同区榜的候选与分数。
 * 候选只含已开启「在好友与同区榜上显示我」(growth_board_visibility.visible = 1)且未在进度隐私里选「不上榜」的玩家;
 * 看榜的本人不在候选里,由服务层单独补进排名(本人永远看得到自己)。
 */
public interface GrowthScopedBoardMapper {

    /** 我关注的人 ∪ 同一俱乐部的成员,已开启可见,去重,不含本人。 */
    List<Long> selectFriendCandidates(@Param("memberId") long memberId, @Param("cap") int cap);

    /** 与我同一所在地(ums_member.area_id)的玩家,已开启可见,不含本人。 */
    List<Long> selectDistrictCandidates(@Param("memberId") long memberId, @Param("areaId") long areaId, @Param("cap") int cap);

    /** 本人所在地;未设置为 null。 */
    Long selectAreaId(@Param("memberId") long memberId);

    /**
     * 给定玩家的分数行 {memberId, nickname, avatar, score, createdAt}。只返回未注销的玩家账号。
     * metric: point / exp;period: total / week(week 需 weekStart/weekEnd)。
     */
    List<Map<String, Object>> selectScores(@Param("metric") String metric, @Param("period") String period,
                                           @Param("weekStart") Date weekStart, @Param("weekEnd") Date weekEnd,
                                           @Param("ids") Collection<Long> ids);

    /** 本人的开关;无行为 null(= 未开启)。 */
    Integer selectVisible(@Param("memberId") long memberId);

    int upsertVisible(@Param("memberId") long memberId, @Param("visible") int visible, @Param("now") Date now);
}
