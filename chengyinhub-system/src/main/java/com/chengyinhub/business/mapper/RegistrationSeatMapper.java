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
    /** 只读席位事实（不取锁、不初始化）；仅用于购票人自己的席位摘要。 */
    List<RegistrationSeat> selectByRegistration(@Param("registrationId") Long registrationId);
    int insertSeat(RegistrationSeat seat);
    int markVerified(RegistrationSeat seat);
    CoopGroupMemberVerify selectGroupForUpdate(@Param("registrationId") Long registrationId);
    List<Long> selectUnprovenMultiGroupOrdersForUpdate(@Param("topicId") Long topicId,
            @Param("activityId") Long activityId, @Param("cutoff") Date cutoff);
    List<RegistrationSeat> selectVerifiedForGroupForUpdate(@Param("topicId") Long topicId,
            @Param("activityId") Long activityId, @Param("cutoff") Date cutoff);
}
