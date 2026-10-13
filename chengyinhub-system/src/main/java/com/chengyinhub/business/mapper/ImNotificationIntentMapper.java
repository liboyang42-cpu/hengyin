package com.chengyinhub.business.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 原站内通知意图；仅业务内部调用，不增加用户渠道。 */
public interface ImNotificationIntentMapper {
    int insert(@Param("eventKey") String eventKey, @Param("recipient") Long recipient,
               @Param("msgType") Integer msgType, @Param("content") String content,
               @Param("extraJson") String extraJson, @Param("requestHash") String requestHash);
    Map<String, Object> selectByKeyForUpdate(@Param("eventKey") String eventKey);
    Map<String, Object> selectByIdForUpdate(@Param("id") Long id);
    Long maxPendingId();
    List<Long> selectPendingPage(@Param("afterId") Long afterId, @Param("maxId") Long maxId);
    int delivered(@Param("id") Long id, @Param("messageId") Long messageId);
}
