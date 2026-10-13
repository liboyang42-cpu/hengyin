package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.TopicSaleCanonicalDraft;
import com.chengyinhub.business.domain.dto.TopicSaleCanonicalDraftDTO;

public interface ITopicSaleCanonicalDraftService {
    TopicSaleCanonicalDraft save(Long topicId, Long operatorId, TopicSaleCanonicalDraftDTO request);
    TopicSaleCanonicalDraft get(Long topicId);
}
