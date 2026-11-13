package com.chengyinhub.business.appgaps.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface SupportTicketReadMapper {
    List<Map<String, Object>> selectTickets(@Param("memberId") long memberId, @Param("beforeId") long beforeId, @Param("limit") int limit);
    Map<String, Object> selectTicket(@Param("memberId") long memberId, @Param("ticketNo") String ticketNo);
    List<Map<String, Object>> selectMessages(@Param("ticketId") long ticketId, @Param("afterId") long afterId, @Param("limit") int limit);
    List<Map<String, Object>> selectEvents(@Param("ticketId") long ticketId, @Param("beforeId") long beforeId, @Param("limit") int limit);
}
