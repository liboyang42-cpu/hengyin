package com.chengyinhub.business.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** Immutable provider facts and bindings; recovery completion updates only the existing outbox. */
public interface WechatVerifiedPaymentFactMapper {
    int insertIgnore(Map<String,Object> fact);
    List<Map<String,Object>> selectMatching(@Param("kind") String kind, @Param("mchid") String mchid,
            @Param("providerId") String providerId, @Param("eventNo") String eventNo);
    List<Map<String,Object>> selectMatchingCurrent(@Param("kind") String kind, @Param("mchid") String mchid,
            @Param("providerId") String providerId, @Param("eventNo") String eventNo);
    List<Map<String,Object>> selectSuccessesByBusiness(@Param("businessNo") String businessNo);
    Map<String,Object> selectById(@Param("id") long id);
    int insertRejection(Map<String,Object> rejection);
    int insertRecovery(Map<String,Object> recovery);
    Map<String,Object> selectRecovery(@Param("key") String key);
    Map<String,Object> selectRecoveryCurrent(@Param("key") String key);
    int completeRecovery(@Param("key") String key,@Param("businessId") long businessId);
    int insertInboxLink(Map<String,Object> link);
    Map<String,Object> selectInboxLink(@Param("notifyHash") String notifyHash);
}
