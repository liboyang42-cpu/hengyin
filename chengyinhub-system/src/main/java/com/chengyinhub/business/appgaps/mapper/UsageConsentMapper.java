package com.chengyinhub.business.appgaps.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** 只读：本人在 app_launch 场景的同意/撤回留痕（consent_record）与对应协议原文（agreement_doc）。 */
public interface UsageConsentMapper {
    List<Map<String, Object>> selectHistory(@Param("memberId") long memberId, @Param("beforeId") Long beforeId);
    Map<String, Object> selectByRequest(@Param("memberId") long memberId, @Param("requestId") String requestId);
    Long selectLatestId(@Param("memberId") long memberId, @Param("docType") String docType);
    Map<String, Object> selectDocument(@Param("docType") String docType, @Param("version") String version);
}
