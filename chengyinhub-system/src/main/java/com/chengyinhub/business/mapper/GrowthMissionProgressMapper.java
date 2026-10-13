package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.GrowthMissionProgress;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * P3 成长进度 Mapper。
 */
public interface GrowthMissionProgressMapper {
    int insertMissionProgress(GrowthMissionProgress progress);

    boolean hasRegistrationAward(@Param("memberId") Long memberId,
                                 @Param("missionCode") String missionCode,
                                 @Param("registrationId") Long registrationId);

    List<GrowthMissionProgress> selectMemberProgress(Long memberId);

    /** 拍板4 冲回:某报名单核销发出的成长经验行(加锁)。 */
    List<GrowthMissionProgress> selectRegistrationAwardsForUpdate(@Param("memberId") Long memberId,
                                                                  @Param("registrationId") Long registrationId);

    int markRegistrationAwardClawed(@Param("id") Long id);

    /** 拍板4:该玩家该主题尚未消费的通关奖励冲回标记(mission_code 集合,加锁当前读)。 */
    List<String> selectCompletionClawbackMarkersForUpdate(@Param("memberId") Long memberId, @Param("topicId") Long topicId);

    /** 拍板4:无锁探测,只在有标记时才走加锁补发路径。 */
    int countCompletionClawbackMarkers(@Param("memberId") Long memberId, @Param("topicId") Long topicId);

    /** 拍板4:补发完某项奖励即消费其标记。 */
    int deleteCompletionClawbackMarker(@Param("memberId") Long memberId, @Param("topicId") Long topicId,
                                       @Param("missionCode") String missionCode);
}
