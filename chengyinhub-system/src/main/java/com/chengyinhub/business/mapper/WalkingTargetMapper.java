package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.navigation.WalkingTargetContracts.CityTargetRow;
import com.chengyinhub.business.navigation.WalkingTargetContracts.Provenance;

/** Dormant read and UNREVIEWED-only staging, with no approval/promotion operation. */
public interface WalkingTargetMapper {
    int insertUnreviewed(@Param("candidate") Provenance candidate);
    CityTargetRow selectCurrentPublicCityTarget(@Param("poiId") long poiId);
}
