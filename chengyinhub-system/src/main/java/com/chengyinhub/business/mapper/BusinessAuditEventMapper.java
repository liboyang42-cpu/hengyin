package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.dto.BusinessAuditLogQueryDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface BusinessAuditEventMapper {
    BusinessAuditEvent selectByDomainActionRequestForUpdate(@Param("domainType") String domainType,
                                                            @Param("domainId") Long domainId,
                                                            @Param("action") String action,
                                                            @Param("requestId") String requestId);

    int insertBusinessAuditEvent(BusinessAuditEvent event);

    /** Append the complaint notice rejection through this sole table owner; immutable replay is checked by the producer. */
    int insertComplaintCloseNoticeRejectionIgnore(BusinessAuditEvent event);

    /** F15 操作员日频控:数「今天」的审计行(每次成功揭示恰好一条)。当天边界由库侧 curdate() 定。 */
    long countActorActionToday(@Param("actorMemberId") Long actorMemberId,
                               @Param("action") String action);

    /** 按目标对象读回不可变审计(核销/撤销留痕),时间倒序。走 idx_business_audit_target。 */
    List<BusinessAuditEvent> selectByTarget(@Param("targetType") String targetType,
                                            @Param("targetId") Long targetId);

    List<BusinessAuditEvent> selectAuditLogPage(BusinessAuditLogQueryDTO query);
}
