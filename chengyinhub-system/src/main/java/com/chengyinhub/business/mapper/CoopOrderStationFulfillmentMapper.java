package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.CoopSettlementCandidate;
import com.chengyinhub.business.domain.dto.CoopSettlementSnapshot;
import org.apache.ibatis.annotations.Param;
import java.util.Date;
import java.util.List;

/** Only the claim owner writes; routing reads never authorize a write. */
public interface CoopOrderStationFulfillmentMapper {
    CmsRegistration selectAuditRegistrationLocation(@Param("auditId") Long auditId);
    CmsRegistration selectRegistrationForUpdate(@Param("registrationId") Long registrationId);
    CmsRegistrationMerchant selectStationForUpdate(@Param("stationId") Long stationId);
    BusinessAuditEvent selectAuditForUpdate(@Param("auditId") Long auditId);
    List<BusinessAuditEvent> selectVerificationEventsForUpdate(@Param("registrationId") Long registrationId);
    List<CoopOrder> selectEffectiveOrdersForUpdate(@Param("topicId") Long topicId);
    CoopOrderStationFulfillment selectByAuditForUpdate(@Param("auditId") Long auditId);
    List<CoopOrderStationFulfillment> selectByOrderForUpdate(@Param("orderId") Long orderId);
    int insertConfirmed(CoopOrderStationFulfillment receipt);
    List<CoopSettlementCandidate> selectV1CandidatesForUpdate(@Param("snapshot") CoopSettlementSnapshot snapshot,
            @Param("settlementCutoff") Date settlementCutoff);
}
