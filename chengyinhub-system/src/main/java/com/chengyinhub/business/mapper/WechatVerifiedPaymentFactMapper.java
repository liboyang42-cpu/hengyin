package com.chengyinhub.business.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** Append/insert only. There is deliberately no update, delete, expiry or lease operation. */
public interface WechatVerifiedPaymentFactMapper {
    int insertIgnore(Map<String,Object> fact);
    List<Map<String,Object>> selectMatching(@Param("kind") String kind, @Param("mchid") String mchid,
            @Param("providerId") String providerId, @Param("eventNo") String eventNo);
    Map<String,Object> selectById(@Param("id") long id);
    int insertRejection(Map<String,Object> rejection);
    int insertInboxLink(Map<String,Object> link);
    Map<String,Object> selectInboxLink(@Param("notifyHash") String notifyHash);
}
