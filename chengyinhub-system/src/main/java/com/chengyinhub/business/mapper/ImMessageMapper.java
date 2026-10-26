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

    ImMessage selectLatestViewerVisibleForShare(@Param("conversationId") Long conversationId,
            @Param("viewerMemberId") Long viewerMemberId);

    /** 显式已读位：只接受当前读者在该会话可见的消息作为截止点。 */
    ImMessage selectViewerVisibleByIdForShare(@Param("conversationId") Long conversationId,
            @Param("viewerMemberId") Long viewerMemberId, @Param("id") Long id);

    /** 原发送回执只读查询：完整唯一键范围，不加行锁或改变消息状态。 */
    ImMessage selectByClientMessageId(@Param("conversationId") Long conversationId,
            @Param("senderId") Long senderId, @Param("clientMessageId") String clientMessageId);

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

    /** 增量补拉：按可见序(visible_at,id)正序取 afterId 之后当前读者可见的消息。 */
    List<ImMessage> selectVisibleAfter(@Param("conversationId") Long conversationId,
                                       @Param("viewerMemberId") Long viewerMemberId,
                                       @Param("afterId") Long afterId, @Param("size") Integer size);

    public int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    /** 条件迁移 status:仅当前为 fromStatus 时改成 toStatus;返回命中行数(0/1)。 */
    int transitionStatus(@Param("id") Long id, @Param("fromStatus") Integer fromStatus,
                         @Param("toStatus") Integer toStatus);
}
