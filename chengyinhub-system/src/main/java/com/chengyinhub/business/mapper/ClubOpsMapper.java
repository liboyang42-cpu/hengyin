package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * club-ops(R8)只读投影:附近俱乐部、活动日场次、运营看板、榜单可用度量。不写任何表。
 */
public interface ClubOpsMapper {
    /**
     * 公开可见的已通过俱乐部及其位置。位置先取俱乐部自己的坐标(club.location_lat/lng,由地址定点),
     * 没有时取该俱乐部最近一场已发布、未取消且带坐标的场次坐标。视窗外与无位置的不返回。
     */
    List<Map<String, Object>> selectLocatedClubs(@Param("south") BigDecimal south, @Param("north") BigDecimal north,
                                                 @Param("west") BigDecimal west, @Param("east") BigDecimal east,
                                                 @Param("limit") int limit);

    /** [since, until) 内开始、未取消的本俱乐部场次(含待审,治理者视角),按开始时间升序。 */
    List<Map<String, Object>> selectOccurrences(@Param("clubId") Long clubId,
                                                @Param("since") LocalDateTime since,
                                                @Param("until") LocalDateTime until,
                                                @Param("limit") int limit);

    /**
     * [since, until) 内开始、未取消的本俱乐部场次里,每个已付款报名人一行:activityId, startDate, memberId, arrived。
     * 到场口径与 ClubEventOpsMapper.selectPaidRoster 一致,最近一次出勤更正优先。
     */
    List<Map<String, Object>> selectAttendance(@Param("clubId") Long clubId,
                                               @Param("since") LocalDateTime since,
                                               @Param("until") LocalDateTime until);

    /** 当前在册成员的入会时间(club_member status=1),不含主理人。 */
    List<Map<String, Object>> selectMemberJoins(@Param("clubId") Long clubId);

    /** 本俱乐部名下带里程的主题数:>0 说明俱乐部在用跑步类度量(里程/配速/用时)。 */
    int countRunningTopics(@Param("clubId") Long clubId);
}
