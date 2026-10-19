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

<<<<<<< public
    ImMessage selectLatestViewerVisibleForShare(@Param("conversationId") Long conversationId,
            @Param("viewerMemberId") Long viewerMemberId);

    /** 显式已读位：只接受当前读者在该会话可见的消息作为截止点。 */
    ImMessage selectViewerVisibleByIdForShare(@Param("conversationId") Long conversationId,
            @Param("viewerMemberId") Long viewerMemberId, @Param("id") Long id);
=======
    /** 已读消费校验使用当前消息状态，且只返回指定会话的消息。 */
    ImMessage selectByConversationAndIdForUpdate(@Param("conversationId") Long conversationId,
            @Param("id") Long id);

    /** 摘要回读真正最后消息；审核阻断的消息不能被较早消息替代。 */
    ImMessage selectLatestMessage(@Param("conversationId") Long conversationId);
>>>>>>> private-codex/batch1

    ImMessage selectByClientMessageIdForUpdate(@Param("conversationId") Long conversationId,
            @Param("senderId") Long senderId, @Param("clientMessageId") String clientMessageId);

    ImMessage selectByClientMessageId(@Param("conversationId") Long conversationId,
            @Param("senderId") Long senderId, @Param("clientMessageId") String clientMessageId);

    /** 按内容安全异步检测的 trace_id 反查消息(图片送检回调用) */
    public ImMessage selectByTraceId(@Param("traceId") String traceId);

    /** 内容安全回调终态：必须同时匹配 id + trace，0 行不能当成功。 */
    int applyMediaCheckResult(@Param("id") Long id, @Param("traceId") String traceId,
                              @Param("risky") boolean risky);

    /** 会话消息分页(cursorId>0 时取该id之前的更早消息, 倒序) */
    public List<ImMessage> selectPage(@Param("conversationId") Long conversationId,
                                      @Param("cursorId") Long cursorId, @Param("size") Integer size);

<<<<<<< public
    List<ImMessage> selectVisiblePage(@Param("conversationId") Long conversationId,
                                    @Param("viewerMemberId") Long viewerMemberId,
                                    @Param("cursorId") Long cursorId, @Param("size") Integer size);

    /** 增量补拉：按可见序(visible_at,id)正序取 afterId 之后当前读者可见的消息。 */
    List<ImMessage> selectVisibleAfter(@Param("conversationId") Long conversationId,
                                       @Param("viewerMemberId") Long viewerMemberId,
                                       @Param("afterId") Long afterId, @Param("size") Integer size);
=======
    /** afterId 增量页，watermarkId 固定本轮同步上界，按 id 升序。 */
    List<ImMessage> selectAfterPage(@Param("conversationId") Long conversationId,
                                    @Param("afterId") Long afterId,
                                    @Param("watermarkId") Long watermarkId,
                                    @Param("size") Integer size);

    Long selectLatestVisibleIdForUpdate(@Param("conversationId") Long conversationId);

    Long selectMaxVisibleId(@Param("conversationId") Long conversationId);
>>>>>>> private-codex/batch1

    public int updateStatus(@Param("id") Long id, @Param("status") Integer status);
}
