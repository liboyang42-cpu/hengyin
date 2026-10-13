package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

public interface StoreVerifyGrowthDeliveryMapper {
    int enqueue(@Param("registrationId") Long registrationId, @Param("memberId") Long memberId,
                @Param("missionsJson") String missionsJson);
    Map<String, Object> selectForUpdate(Long registrationId);
    List<Long> selectDue();
    int markProcessed(Long registrationId);
    int recordFailure(@Param("registrationId") Long registrationId, @Param("error") String error);
    int deleteByRegistration(@Param("registrationId") Long registrationId);
}
