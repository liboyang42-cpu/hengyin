package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CoopGroupMemberVerify;
import com.chengyinhub.business.domain.RegistrationSeat;
import org.apache.ibatis.annotations.Param;
import java.util.Date;
import java.util.List;

/** Current reads share topic -> activity -> club -> registration -> seat lock order. */
public interface RegistrationSeatMapper {
    Long lockRegistration(@Param("id") Long id);
    Long lockClubOwner(@Param("id") Long id);
    List<RegistrationSeat> selectByRegistrationForUpdate(@Param("registrationId") Long registrationId);
    int insertSeat(RegistrationSeat seat);
    int markVerified(RegistrationSeat seat);
    CoopGroupMemberVerify selectGroupForUpdate(@Param("registrationId") Long registrationId);
    List<Long> selectUnprovenMultiGroupOrdersForUpdate(@Param("topicId") Long topicId,
            @Param("activityId") Long activityId, @Param("cutoff") Date cutoff);
    List<RegistrationSeat> selectVerifiedForGroupForUpdate(@Param("topicId") Long topicId,
            @Param("activityId") Long activityId, @Param("cutoff") Date cutoff);
}
