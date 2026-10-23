package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.AuditProblem;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** O-05 #4:审核逐项驳回原因。 */
public interface AuditProblemMapper {
    int insertAuditProblem(AuditProblem problem);

    /** 作者读:只返回归属该作者的行(调用方已按内容作者校验,这里再按 owner 收口)。 */
    List<AuditProblem> selectByTaskForOwner(@Param("taskId") Long taskId, @Param("ownerMemberId") Long ownerMemberId);
}
