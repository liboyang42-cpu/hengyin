package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import org.apache.ibatis.annotations.Param;

/** Narrow append-only adapter to the existing audit ledger; no notice/financial table changes. */
public interface CommittedNoticeRejectionMapper {
    int insertIgnore(BusinessAuditEvent event);
    BusinessAuditEvent selectForUpdate(@Param("complaintId") Long complaintId, @Param("eventKey") String eventKey);
}
