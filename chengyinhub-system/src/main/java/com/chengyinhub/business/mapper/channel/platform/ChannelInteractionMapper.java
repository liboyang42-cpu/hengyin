package com.chengyinhub.business.mapper.channel.platform;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/** 频道接龙（PR#728 im_chain*）与每日打卡（im_channel_checkin）读写原语。 */
public interface ChannelInteractionMapper
{
    int insertCardMessage(Map<String, Object> row);

    int setMessageExtra(@Param("id") long id, @Param("extraJson") String extraJson);

    int touchConversation(@Param("conversationId") long conversationId, @Param("text") String text);

    int fanOutUnread(@Param("conversationId") long conversationId, @Param("senderId") long senderId);

    int insertChain(Map<String, Object> row);

    Map<String, Object> selectChain(@Param("id") long id, @Param("conversationId") long conversationId);

    Long selectLatestChainId(@Param("conversationId") long conversationId);

    Map<String, Object> lockChain(@Param("id") long id, @Param("conversationId") long conversationId);

    int updateChain(@Param("id") long id, @Param("expectedVersion") long expectedVersion, @Param("activeCount") int activeCount,
            @Param("status") String status);

    Map<String, Object> lockEntry(@Param("chainId") long chainId, @Param("memberId") long memberId);

    Map<String, Object> selectEntry(@Param("chainId") long chainId, @Param("memberId") long memberId);

    int nextPosition(@Param("chainId") long chainId);

    int insertEntry(@Param("chainId") long chainId, @Param("memberId") long memberId, @Param("content") String content,
            @Param("position") int position, @Param("requestId") String requestId);

    int updateEntry(@Param("chainId") long chainId, @Param("memberId") long memberId, @Param("expectedVersion") long expectedVersion,
            @Param("status") String status, @Param("content") String content, @Param("requestId") String requestId);

    List<Map<String, Object>> listEntries(@Param("chainId") long chainId, @Param("after") long after, @Param("limit") int limit);

    Map<String, Object> selectCheckin(@Param("channelId") long channelId, @Param("memberId") long memberId, @Param("date") String date);

    int insertCheckin(Map<String, Object> row);

    List<String> listHistory(@Param("channelId") long channelId, @Param("memberId") long memberId, @Param("date") String date);

    List<Map<String, Object>> rank(@Param("channelId") long channelId, @Param("date") String date, @Param("limit") int limit);

    int countDays(@Param("channelId") long channelId, @Param("memberId") long memberId, @Param("date") String date);

    int countAhead(@Param("channelId") long channelId, @Param("date") String date, @Param("days") int days,
            @Param("memberId") long memberId);
}
