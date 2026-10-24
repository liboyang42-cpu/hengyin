package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ConsentRecord;
import org.apache.ibatis.annotations.Param;

public interface ConsentRecordMapper {
    ConsentRecord selectLatestRoamLocationForUpdate(@Param("memberId") Long memberId);
    ConsentRecord selectLatestRoamLocation(@Param("memberId") Long memberId);
    ConsentRecord selectLatestLocationForUpdate(@Param("memberId") Long memberId, @Param("scene") String scene);
    ConsentRecord selectLatestLocation(@Param("memberId") Long memberId, @Param("scene") String scene);

    int insertConsentRecord(ConsentRecord record);

    ConsentRecord selectByRequestId(@Param("memberId") Long memberId, @Param("requestId") String requestId);

    ConsentRecord selectByRequestIdForShare(@Param("requestId") String requestId);

    ConsentRecord selectLatestRoamPresence(@Param("memberId") Long memberId);

    ConsentRecord selectLatest(@Param("memberId") Long memberId,
                               @Param("docType") String docType,
                               @Param("scene") String scene);

    ConsentRecord selectLatestScoped(@Param("memberId") Long memberId,
                                     @Param("docType") String docType,
                                     @Param("scene") String scene,
                                     @Param("scopeType") String scopeType,
                                     @Param("scopeId") Long scopeId);
}
