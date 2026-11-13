package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** Player reads/writes the app needed on existing tables (follow list, route action receipt, route eggs). */
public interface PlayerGapsMapper {
    List<Map<String, Object>> selectFollowing(@Param("owner") long owner, @Param("cursor") long cursor, @Param("limit") int limit);
    List<Map<String, Object>> selectFollowers(@Param("owner") long owner, @Param("cursor") long cursor, @Param("limit") int limit);

    Map<String, Object> selectRouteAction(@Param("sessionId") long sessionId, @Param("memberId") long memberId, @Param("actionId") String actionId);

    Map<String, Object> selectApprovedEgg(@Param("eggId") long eggId);
    int insertEggCollectible(@Param("memberId") long memberId, @Param("topicId") Long topicId, @Param("eggId") long eggId,
                             @Param("content") String content);
}
