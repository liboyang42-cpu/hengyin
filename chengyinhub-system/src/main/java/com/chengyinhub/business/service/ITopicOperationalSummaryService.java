package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.TopicOperationalListSummaryDTO;

import java.util.List;
import java.util.Map;

/** 主题列表运营摘要的唯一聚合入口。 */
public interface ITopicOperationalSummaryService {

    Map<Long, TopicOperationalListSummaryDTO> summarize(List<CmsTopic> topics);
}
