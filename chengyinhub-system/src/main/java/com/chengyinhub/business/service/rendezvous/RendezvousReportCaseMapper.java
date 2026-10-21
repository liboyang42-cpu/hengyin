package com.chengyinhub.business.service.rendezvous;

import org.apache.ibatis.annotations.Param;

/** Case rows inside the consent guard transaction. Not a scanned mapper bean and not an authorization API. */
public interface RendezvousReportCaseMapper {
    RendezvousReportCase.Row find(@Param("reporterId") long reporterId, @Param("requestId") String requestId);

    int insert(RendezvousReportCase.Row row);

    int updateReview(@Param("caseId") String caseId, @Param("reporterId") long reporterId,
                     @Param("expectedState") String expectedState, @Param("state") String state,
                     @Param("reviewConnection") String reviewConnection, @Param("reviewRef") String reviewRef,
                     @Param("dispositionCode") String dispositionCode);
}
