package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.TopicOnsiteMetricsVO;
import com.chengyinhub.business.domain.vo.TopicChapterWaitVO;
import java.util.List;

public interface ITopicOnsiteMetricsService {
    TopicOnsiteMetricsVO get(Long topicId);
    List<TopicChapterWaitVO> chapterWaits(Long topicId);
}
