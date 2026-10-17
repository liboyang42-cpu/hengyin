package com.chengyinhub.business.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.ImMessage;

/**
 * IM 消息 Mapper
 */
public interface ImMessageMapper
{
    public int insertImMessage(ImMessage message);

    public ImMessage selectImMessageById(Long id);

    ImMessage selectByClientMessageIdForUpdate(@Param("conversationId") Long conversationId,
            @Param("senderId") Long senderId, @Param("clientMessageId") String clientMessageId);

    /** 按内容安全异步检测的 trace_id 反查消息(图片送检回调用) */
    public ImMessage selectByTraceId(@Param("traceId") String traceId);

    /** 内容安全回调终态：必须同时匹配 id + trace，0 行不能当成功。 */
    int applyMediaCheckResult(@Param("id") Long id, @Param("traceId") String traceId,
                              @Param("risky") boolean risky);

    /** 会话消息分页(cursorId>0 时取该id之前的更早消息, 倒序) */
    public List<ImMessage> selectPage(@Param("conversationId") Long conversationId,
                                      @Param("cursorId") Long cursorId, @Param("size") Integer size);

    List<ImMessage> selectVisiblePage(@Param("conversationId") Long conversationId,
                                    @Param("viewerMemberId") Long viewerMemberId,
                                    @Param("cursorId") Long cursorId, @Param("size") Integer size);

    public int updateStatus(@Param("id") Long id, @Param("status") Integer status);
}
