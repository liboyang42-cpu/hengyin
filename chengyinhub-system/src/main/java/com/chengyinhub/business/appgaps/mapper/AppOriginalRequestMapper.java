package com.chengyinhub.business.appgaps.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface AppOriginalRequestMapper {
    int insertIgnore(Map<String, Object> row);
    Map<String, Object> select(@Param("protocol") String protocol, @Param("actor") Long actor, @Param("requestId") String requestId);
    int finish(@Param("id") Long id, @Param("state") String state, @Param("resultJson") String resultJson,
               @Param("errorCode") Integer errorCode, @Param("errorMessage") String errorMessage);
    List<Map<String, Object>> selectRecent(@Param("protocol") String protocol, @Param("actor") Long actor,
                                           @Param("scope") String scope, @Param("limit") int limit);
}
