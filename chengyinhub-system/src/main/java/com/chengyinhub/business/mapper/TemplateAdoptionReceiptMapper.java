package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsTemplateLibrary;
import org.apache.ibatis.annotations.Param;
import java.util.Map;

public interface TemplateAdoptionReceiptMapper {
    int reserve(Map<String, Object> facts);
    Map<String, Object> lock(@Param("actor") Long actor, @Param("requestId") String requestId);
    Map<String, Object> read(@Param("actor") Long actor, @Param("requestId") String requestId);
    Long sourceCanonicalId(@Param("id") Long id);
    CmsTemplateLibrary lockSource(@Param("id") Long id);
    com.chengyinhub.business.domain.CmsMemberTemplate lockDraft(@Param("id") Long id);
    int finish(@Param("actor") Long actor, @Param("requestId") String requestId,
               @Param("status") String status, @Param("draftId") Long draftId, @Param("reason") String reason, @Param("draftVersion") String draftVersion);
}
