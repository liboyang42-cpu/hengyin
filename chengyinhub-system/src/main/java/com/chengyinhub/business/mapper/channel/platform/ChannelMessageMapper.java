package com.chengyinhub.business.mapper.channel.platform;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/** 频道消息（im_message 同一存储）的版本、修订、变更流、置顶、提及与检索。 */
public interface ChannelMessageMapper
{
    Map<String, Object> lockMessage(@Param("id") long id);
    List<Map<String, Object>> selectMessages(@Param("ids") List<Long> ids);
    int updateContent(@Param("id") long id, @Param("expected") long expected, @Param("content") String content);
    int recall(@Param("id") long id, @Param("expected") long expected);
    int insertRevision(@Param("messageId") long messageId, @Param("revisionNo") long revisionNo,
            @Param("content") String content, @Param("checkStatus") int checkStatus, @Param("editorId") long editorId);
    List<Map<String, Object>> selectRevisions(@Param("messageId") long messageId, @Param("before") Long before,
            @Param("limit") int limit);
    int insertMutation(Map<String, Object> row);
    List<Map<String, Object>> selectMutations(@Param("conversationId") long conversationId, @Param("after") long after,
            @Param("limit") int limit);
    Long maxMutation(@Param("conversationId") long conversationId);

    List<Map<String, Object>> selectPins(@Param("conversationId") long conversationId);
    List<Map<String, Object>> lockPins(@Param("conversationId") long conversationId);
    int insertPin(Map<String, Object> row);
    int deletePin(@Param("conversationId") long conversationId, @Param("messageId") long messageId);
    int updatePinSort(@Param("id") long id, @Param("sortNo") int sortNo);
    List<Long> selectPinnedIds(@Param("conversationId") long conversationId, @Param("ids") List<Long> ids);

    int deleteMentions(@Param("messageId") long messageId);
    int insertMention(@Param("messageId") long messageId, @Param("type") String type, @Param("targetId") long targetId);
    List<Map<String, Object>> selectMentions(@Param("ids") List<Long> ids);

    List<Long> search(@Param("conversationId") long conversationId, @Param("keyword") String keyword,
            @Param("from") Date from, @Param("to") Date to, @Param("authorId") Long authorId,
            @Param("before") Long before, @Param("limit") int limit);
    Date oldestMessageTime(@Param("conversationId") long conversationId);
    List<Long> contextOlder(@Param("conversationId") long conversationId, @Param("anchor") long anchor, @Param("limit") int limit);
    List<Long> contextNewer(@Param("conversationId") long conversationId, @Param("anchor") long anchor, @Param("limit") int limit);
    Date lastSentAt(@Param("conversationId") long conversationId, @Param("senderId") long senderId);
}
