package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.dto.TopicOnsiteTicketFact;
import com.chengyinhub.business.domain.dto.TopicRepeatPaymentFact;
import com.chengyinhub.business.domain.dto.TopicWaitFact;
import com.chengyinhub.business.domain.dto.TopicChapterLoadFact;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface TopicOnsiteMetricsMapper {
    List<TopicOnsiteTicketFact> selectTicketFacts(@Param("topicId") Long topicId);
    List<TopicWaitFact> selectWaitFacts(@Param("topicId") Long topicId);
    List<TopicRepeatPaymentFact> selectRepeatPayments(@Param("topicId") Long topicId);
    List<TopicChapterLoadFact> selectInStoreLoads(@Param("topicId") Long topicId);
    int countEffectiveComplaintUsers(@Param("topicId") Long topicId);
}
