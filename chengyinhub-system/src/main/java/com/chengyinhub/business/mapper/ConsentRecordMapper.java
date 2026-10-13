package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ConsentRecord;
import org.apache.ibatis.annotations.Param;

public interface ConsentRecordMapper {
    int insertConsentRecord(ConsentRecord record);

    ConsentRecord selectByRequestIdForShare(@Param("requestId") String requestId);

    ConsentRecord selectLatest(@Param("memberId") Long memberId,
                               @Param("docType") String docType,
                               @Param("scene") String scene);

    ConsentRecord selectLatestScoped(@Param("memberId") Long memberId,
                                     @Param("docType") String docType,
                                     @Param("scene") String scene,
                                     @Param("scopeType") String scopeType,
                                     @Param("scopeId") Long scopeId);
}
