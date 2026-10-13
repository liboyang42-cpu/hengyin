package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

public interface ThemeBroadcastMapper {
    int enqueue(@Param("sessionId") Long sessionId,@Param("memberId") Long memberId,@Param("eventId") String eventId,@Param("content") String content);
    List<Map<String,Object>> lockDeliveries(@Param("sessionId") Long sessionId,@Param("memberId") Long memberId);
    int reached(@Param("id") Long id,@Param("memberId") Long memberId,@Param("messageId") Long messageId);
    int failed(@Param("id") Long id,@Param("memberId") Long memberId);
    List<Map<String,Object>> receipts(@Param("sessionId") Long sessionId,@Param("memberId") Long memberId);
}
