package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ClubLeadCheckin;
import com.chengyinhub.business.domain.ClubLeadState;
import com.chengyinhub.business.domain.dto.TopicWorkspaceDTO;
import com.chengyinhub.business.domain.dto.ClubFulfillmentWorkspaceDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * P1 俱乐部带领 Mapper(state + checkin)。
 */
public interface ClubLeadMapper {
    ClubLeadState selectByActivity(@Param("activityId") Long activityId);

    ClubLeadState selectByActivityForUpdate(@Param("activityId") Long activityId);

    /** 与领队委派/交接使用相同的 club 行锁，不能使用旧 state 的缓存领队授权。 */
    Long lockClubOwner(@Param("clubId") Long clubId);

    int unlockChapterCas(@Param("activityId") Long activityId,
                         @Param("storedLeaderId") Long storedLeaderId,
                         @Param("leaderId") Long leaderId,
                         @Param("expectedChapterId") Long expectedChapterId,
                         @Param("targetChapterId") Long targetChapterId);

    int insertClubLeadState(ClubLeadState state);

    /** 按 id 更新(leaderMemberId/currentChapterId/broadcast/status,<if> 空不覆盖) */
    int updateClubLeadState(ClubLeadState state);

    int insertCheckin(ClubLeadCheckin checkin);

    List<ClubLeadCheckin> selectCheckins(@Param("activityId") Long activityId);

    /** 主题下所有带队活动的履约状态，供后台主题工作台只读调证。 */
    List<ClubLeadState> selectStatesByTopic(@Param("topicId") Long topicId);

    /** 后台带队场次列表(§4.6 跨主题只读):state × activity × topic × club × 领队昵称 + 报名/到场计数;三筛全可空 */
    List<java.util.Map<String, Object>> selectRunList(@Param("topicId") Long topicId,
                                                       @Param("clubId") Long clubId,
                                                       @Param("status") Integer status);

    /** 后台场次名册(只读):该场报名 × 到场打卡;no-show 由展示层按「已付∧未核销∧未到场」判 */
    List<java.util.Map<String, Object>> selectManifest(@Param("activityId") Long activityId);

    /** 后台场次「流程经过的商家」:该主题各章节**生效**供给(生效两轴口径同 liveOffer,别裸 status=1) */
    List<java.util.Map<String, Object>> selectTopicMerchants(@Param("topicId") Long topicId);

    /** 主题工作台的带队场次投影，含集合事实与责任领队显示名，不返回名册。 */
    List<TopicWorkspaceDTO.ClubFulfillment.Session> selectLeadSessionsByTopic(@Param("topicId") Long topicId);

    /** 主题工作台批量读取到场集合，只用于聚合计数。 */
    List<TopicWorkspaceDTO.ClubFulfillment.AttendanceMarker> selectLeadAttendanceByTopic(@Param("topicId") Long topicId);

    /** 俱乐部 360 批量读取已生效承接下的带队场次。 */
    List<ClubFulfillmentWorkspaceDTO.Session> selectFulfillmentSessionsByClub(@Param("clubId") Long clubId);

    /** 俱乐部 360 批量读取上述场次的集合签到。 */
    List<ClubFulfillmentWorkspaceDTO.Attendance> selectFulfillmentAttendanceByClub(@Param("clubId") Long clubId);
}
