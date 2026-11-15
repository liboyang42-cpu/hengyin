package com.chengyinhub.business.mapper.channel.messaging;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/** W5 C2：表情回应、回复/话题引用、发送时提及、角色提及展开（im_message_reaction / im_message_ref / im_message_mention）。 */
public interface ImMessagingMapper
{
    /** 消息所属会话与状态（引用/回应前校验）。 */
    Map<String, Object> selectMessageHead(@Param("id") long id);

    // ---------------------------------------------------------------- mentions on send

    int insertMention(@Param("messageId") long messageId, @Param("type") String type, @Param("targetId") long targetId);

    /** 频道空间角色（提及前校验归属与 mentionable）。 */
    List<Map<String, Object>> selectRoles(@Param("ids") List<Long> ids);

    /** 角色提及展开成成员（推送用）。 */
    List<Long> selectRoleMemberIds(@Param("roleIds") List<Long> roleIds);

    /** 会话中仍有效的成员 id（单聊/群聊 @ 校验）。 */
    List<Long> selectActiveMemberIds(@Param("conversationId") long conversationId, @Param("ids") List<Long> ids);

    // ---------------------------------------------------------------- reply / thread refs

    int insertRef(@Param("messageId") long messageId, @Param("conversationId") long conversationId,
            @Param("replyToId") long replyToId, @Param("threadRootId") long threadRootId);

    Map<String, Object> selectRef(@Param("messageId") long messageId);

    List<Map<String, Object>> selectRefs(@Param("ids") List<Long> ids);

    /** 话题回复数与最后回复 id（按话题首条批量）。 */
    List<Map<String, Object>> selectThreadSummaries(@Param("conversationId") long conversationId, @Param("rootIds") List<Long> rootIds);

    /** 话题内回复（可见、正序、键集分页：after 之后）。 */
    List<Long> selectThreadMessageIds(@Param("conversationId") long conversationId, @Param("rootId") long rootId,
            @Param("after") long after, @Param("limit") int limit);

    // ---------------------------------------------------------------- reactions

    int insertReaction(@Param("messageId") long messageId, @Param("memberId") long memberId, @Param("emoji") String emoji,
            @Param("conversationId") long conversationId);

    int deleteReaction(@Param("messageId") long messageId, @Param("memberId") long memberId, @Param("emoji") String emoji);

    int countMemberReactions(@Param("messageId") long messageId, @Param("memberId") long memberId);

    /** 每条消息每个表情的人数，以及本人是否点过。 */
    List<Map<String, Object>> selectReactionCounts(@Param("ids") List<Long> ids, @Param("viewer") long viewer);

    // ---------------------------------------------------------------- erasure

    int deleteMemberReactions(@Param("memberId") long memberId);
}
