package com.chengyinhub.business.mapper.channel.platform;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/** 频道进度榜：绑定行 + 完成事实（player_node_progress，只计玩家可见节点）。 */
public interface ChannelProgressMapper
{
    List<Map<String, Object>> selectBoards(@Param("channelId") long channelId);
    Map<String, Object> selectBoard(@Param("channelId") long channelId, @Param("boardKey") String boardKey);
    int insertBoard(Map<String, Object> row);
    int countListedNodes(@Param("topicId") long topicId);
    int countTopic(@Param("topicId") long topicId);
    List<Map<String, Object>> selectMemberProgress(@Param("topicId") long topicId, @Param("activityId") long activityId,
            @Param("memberIds") List<Long> memberIds);
    List<Long> selectMemberNodes(@Param("topicId") long topicId, @Param("activityId") long activityId,
            @Param("memberId") long memberId);
    Long maxProgressId(@Param("topicId") long topicId, @Param("activityId") long activityId);
}
