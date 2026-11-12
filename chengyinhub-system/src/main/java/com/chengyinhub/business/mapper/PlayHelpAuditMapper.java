package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.TopicRouteSession;
import org.apache.ibatis.annotations.Param;

/** qx-c1 #4: admin read-only run lookup for the permission-gated play-help audit; never used for player writes. */
public interface PlayHelpAuditMapper {
    TopicRouteSession selectRunById(@Param("id") Long id);
}
