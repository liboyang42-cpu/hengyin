package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.RoamExploreDayCandidate;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 漫游导流的窄只读候选端口。 */
public interface RoamExploreDayCandidateMapper {
    List<RoamExploreDayCandidate> selectPotentialCandidates(@Param("minLat") double minLat,
                                                            @Param("maxLat") double maxLat,
                                                            @Param("minLng") double minLng,
                                                            @Param("maxLng") double maxLng);
}
