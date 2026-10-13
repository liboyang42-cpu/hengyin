package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.AuditRecord;

import java.util.List;

/**
 * P3 统一审核记录 Mapper。
 */
public interface AuditRecordMapper {
    int insertAuditRecord(AuditRecord auditRecord);

    List<AuditRecord> selectAuditRecordList(AuditRecord auditRecord);

    List<AuditRecord> selectReportsForUpdate(@org.apache.ibatis.annotations.Param("taskId") Long taskId);
}
