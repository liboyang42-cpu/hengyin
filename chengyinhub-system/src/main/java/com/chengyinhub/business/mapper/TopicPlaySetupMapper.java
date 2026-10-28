package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.TopicPlaySetup;
import org.apache.ibatis.annotations.Param;

/** 主题可玩时段与开局前准备(cms_topic_play_setup)。 */
public interface TopicPlaySetupMapper {

    TopicPlaySetup selectByTopicId(@Param("topicId") Long topicId);

    /** 整行覆盖:没有就插,有就改(create_time 保留首次)。 */
    int upsert(TopicPlaySetup setup);
}
