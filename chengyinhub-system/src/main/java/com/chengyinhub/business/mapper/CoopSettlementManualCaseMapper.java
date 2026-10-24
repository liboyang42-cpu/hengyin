package com.chengyinhub.business.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface CoopSettlementManualCaseMapper {
    int insertIgnore(Map<String,Object> original);
    Map<String,Object> selectByKeyForUpdate(@Param("key") String key);
    Long selectOpenTopicForUpdate(@Param("topicId") Long topicId);
}
