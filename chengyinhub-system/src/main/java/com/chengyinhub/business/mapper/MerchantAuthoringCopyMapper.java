package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;

/** W05 authoring provenance is separate from paid-license provenance. */
public interface MerchantAuthoringCopyMapper {
    /** Existing canonical row: 1 if bound, 0 if unbound; absent row: null. */
    Integer selectCopyBlockState(@Param("templateId") Long templateId);
}
