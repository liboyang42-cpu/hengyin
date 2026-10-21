package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.PlayTeamHandoffRequest;
import org.apache.ibatis.annotations.Param;

/** Request index for a real play team. Reads and writes stay in the caller's transaction. */
public interface PlayTeamHandoffMapper {
    PlayTeamHandoffRequest lockByRequest(@Param("namespace") String namespace, @Param("requestId") String requestId);

    PlayTeamHandoffRequest lockByMatch(@Param("namespace") String namespace, @Param("matchId") String matchId);

    PlayTeamHandoffRequest find(@Param("namespace") String namespace, @Param("requestId") String requestId);

    int insert(PlayTeamHandoffRequest request);

    int markJoined(@Param("namespace") String namespace, @Param("requestId") String requestId);
}
