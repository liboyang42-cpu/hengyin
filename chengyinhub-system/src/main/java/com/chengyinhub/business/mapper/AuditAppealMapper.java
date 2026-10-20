package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.AuditAppeal;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

/** 通用内容申诉 Mapper。 */
public interface AuditAppealMapper {
    int insertAuditAppeal(AuditAppeal appeal);

    AuditAppeal selectByBizMember(@Param("bizType") String bizType, @Param("bizId") Long bizId,
                                  @Param("memberId") Long memberId);

    AuditAppeal selectByTaskIdForUpdate(@Param("taskId") Long taskId);

    /** 认领申诉审核单：SUBMITTED → REVIEWING。 */
    int markReviewing(@Param("taskId") Long taskId);

    /** 申诉裁决：仅未结（SUBMITTED/REVIEWING）可落终态。 */
    int finishAppeal(@Param("id") Long id, @Param("state") String state,
                     @Param("handledBy") Long handledBy, @Param("handledAt") Date handledAt);

    /** 申诉撤回：该审核单对应的未结申诉（SUBMITTED/REVIEWING）→ WITHDRAWN。 */
    int withdrawByTask(@Param("taskId") Long taskId, @Param("handledAt") Date handledAt);
}
