package com.chengyinhub.business.appgaps.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.Map;

/** 只读：按原 requestId 读已有的俱乐部业务审计事实（business_audit_event）与委派行。 */
public interface ClubOriginalReceiptMapper {
    Map<String, Object> selectAudit(@Param("clubId") Long clubId, @Param("action") String action, @Param("requestId") String requestId);
    Map<String, Object> selectAssignment(@Param("id") Long id);
}
