package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.vo.LeaderboardItemVO;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 成长中心排行榜 Mapper。
 * 4 种榜单(积分/成长 × 总/周)均从现有表实时计算,无需新表。
 * top 查询返回有序前 N 名(rank 由服务层按序号回填);myRank 查询返回 {userRank,totalUsers,score}。
 */
public interface MemberLeaderboardMapper {

    /** 积分总榜:ums_member.point 降序 */
    List<LeaderboardItemVO> selectPointTotalTop(@Param("limit") int limit);

    /** 积分周榜:本周收入积分(ums_member_points_detail, change_type=1)汇总降序 */
    List<LeaderboardItemVO> selectPointWeekTop(@Param("weekStart") Date weekStart, @Param("limit") int limit);

    /** 成长总榜:member_growth.exp_value 降序 */
    List<LeaderboardItemVO> selectExpTotalTop(@Param("limit") int limit);

    /** 成长周榜:本周经验(growth_mission_progress.reward_exp)汇总降序 */
    List<LeaderboardItemVO> selectExpWeekTop(@Param("weekStart") Date weekStart, @Param("weekEnd") Date weekEnd,
                                              @Param("limit") int limit);

    Map<String, Object> selectPointTotalMyRank(@Param("memberId") Long memberId);

    Map<String, Object> selectPointWeekMyRank(@Param("memberId") Long memberId, @Param("weekStart") Date weekStart);

    Map<String, Object> selectExpTotalMyRank(@Param("memberId") Long memberId);

    Map<String, Object> selectExpWeekMyRank(@Param("memberId") Long memberId, @Param("weekStart") Date weekStart,
                                             @Param("weekEnd") Date weekEnd);
}
