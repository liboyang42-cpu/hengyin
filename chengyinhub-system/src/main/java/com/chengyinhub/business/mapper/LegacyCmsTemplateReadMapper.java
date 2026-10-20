package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.CmsMemberTemplate;

/** Locks the canonical CMS row before deciding whether its legacy body may be read. */
public interface LegacyCmsTemplateReadMapper {
    /** One snapshot statement returns only an unmarked body without any sidecar; no row locks. */
    CmsMemberTemplate selectLegacySnapshotById(@Param("templateId") Long templateId);

    /** Returns 0 for legacy, 1 for blocked, or null when the canonical row is absent. */
    Integer selectCanonicalStateForUpdate(@Param("templateId") Long templateId);
}
