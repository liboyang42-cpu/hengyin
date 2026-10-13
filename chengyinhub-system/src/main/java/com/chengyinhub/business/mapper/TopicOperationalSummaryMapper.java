package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.dto.TopicOperationalListSummaryDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 主题列表的批量只读运营投影。 */
public interface TopicOperationalSummaryMapper {

    List<TopicOperationalListSummaryDTO> selectByTopicIds(@Param("topicIds") List<Long> topicIds);
}
