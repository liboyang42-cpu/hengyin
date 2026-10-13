package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.GrowthMission;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * P3 成长任务 Mapper。
 */
public interface GrowthMissionMapper {
    GrowthMission selectActiveByCode(String missionCode);

    List<GrowthMission> selectActiveByEvent(String triggerEvent);

    Long selectVerifiedStoreMember(@Param("registrationId") Long registrationId);

    List<GrowthMission> selectActiveMissionList();

    List<GrowthMission> selectAdminMissionList(GrowthMission mission);

    int insertGrowthMission(GrowthMission mission);

    int updateGrowthMission(GrowthMission mission);
}
