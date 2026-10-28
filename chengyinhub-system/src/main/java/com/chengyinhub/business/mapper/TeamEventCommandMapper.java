package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;
import java.util.Map;

public interface TeamEventCommandMapper {
    int reserve(@Param("actor") Long actor, @Param("key") String key, @Param("domain") String domain,
                @Param("target") Long target, @Param("action") String action, @Param("fingerprint") String fingerprint);
    Map<String, Object> read(@Param("actor") Long actor, @Param("key") String key);
    Map<String, Object> lock(@Param("actor") Long actor, @Param("key") String key);
    int finish(@Param("actor") Long actor, @Param("key") String key,
               @Param("state") String state, @Param("result") String result);
}
