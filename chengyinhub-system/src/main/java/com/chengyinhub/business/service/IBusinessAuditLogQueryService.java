package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.BusinessAuditLogQueryDTO;
import com.chengyinhub.business.domain.vo.BusinessAuditLogPageVO;

public interface IBusinessAuditLogQueryService {
    BusinessAuditLogPageVO query(BusinessAuditLogQueryDTO query, boolean platformAdmin);
}
