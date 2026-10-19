package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;

/** Read-only recognition of CMS rows reserved for the W18 provenance path. */
public interface LegacyCmsProvenanceMapper {
    /** Returns 0 for legacy, 1 for blocked, and null when the canonical row is missing. */
    Integer selectLegacyBlockState(@Param("templateId") Long templateId);
}
