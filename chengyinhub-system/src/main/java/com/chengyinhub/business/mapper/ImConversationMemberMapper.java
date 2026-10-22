package com.chengyinhub.business.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.ImConversationMember;

/**
 * IM 会话参与者 Mapper
 */
public interface ImConversationMemberMapper
{
    public int insertImConversationMember(ImConversationMember member);

    public ImConversationMember selectByConvAndMember(@Param("conversationId") Long conversationId,
                                                      @Param("memberId") Long memberId);

    ImConversationMember selectByConvAndMemberForUpdate(@Param("conversationId") Long conversationId,
                                                       @Param("memberId") Long memberId);

    int addUnreadForActiveMember(@Param("conversationId") Long conversationId,
                                 @Param("memberId") Long memberId, @Param("delta") int delta);

    /** 我的会话(按会话最后消息时间倒序) */
    public List<ImConversationMember> selectMyConversations(@Param("memberId") Long memberId);

    /** 某会话的所有参与者(用于找对方) */
    public List<ImConversationMember> selectByConversation(@Param("conversationId") Long conversationId);

    /** 处置持有业务行锁后当前读成员，包含历史行，避免 RR 旧快照漏掉刚入群者。 */
    public List<ImConversationMember> selectByConversationForUpdate(@Param("conversationId") Long conversationId);

    /** 给指定参与者未读 +delta */
    public int addUnread(@Param("conversationId") Long conversationId,
                         @Param("memberId") Long memberId, @Param("delta") int delta);

    /** 已读:清零未读并更新已读位 */
    public int markRead(@Param("conversationId") Long conversationId,
                        @Param("memberId") Long memberId, @Param("lastReadMsgId") Long lastReadMsgId);

    int markReadVisible(@Param("conversationId") Long conversationId, @Param("memberId") Long memberId,
                        @Param("lastReadMsgId") Long lastReadMsgId, @Param("visibleOrderAt") String visibleOrderAt);

    /** 消息被屏蔽(delta=-1)/恢复(delta=+1)时修正仍把它计在未读里的读者;返回影响行数。 */
    int adjustUnreadForModeratedMessage(@Param("messageId") Long messageId, @Param("delta") int delta);

    /** 未读总数 */
    public Integer sumUnread(@Param("memberId") Long memberId);

    /** 删除会话(仅对当前用户隐藏) */
    public int markDeleted(@Param("conversationId") Long conversationId, @Param("memberId") Long memberId);

    /** 解散业务群时对全部参与者隐藏。 */
    public int markAllDeleted(@Param("conversationId") Long conversationId);

    /** 设置免打扰(1开 0关) */
    public int updateMuted(@Param("conversationId") Long conversationId,
                           @Param("memberId") Long memberId, @Param("muted") Integer muted);
}
