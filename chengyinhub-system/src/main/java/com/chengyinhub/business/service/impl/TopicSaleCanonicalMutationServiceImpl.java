package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.TopicSaleCanonicalDraft;
import com.chengyinhub.business.mapper.TopicSaleSnapshotMapper;
import com.chengyinhub.business.service.ITopicSaleCanonicalMutationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

@Service
public class TopicSaleCanonicalMutationServiceImpl implements ITopicSaleCanonicalMutationService {
    private final TopicSaleSnapshotMapper snapshotMapper;

    public TopicSaleCanonicalMutationServiceImpl(TopicSaleSnapshotMapper snapshotMapper) {
        this.snapshotMapper = snapshotMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long invalidateApprovedCanonical(Long topicId, String mutationSource) {
        if (topicId == null) return null;
        TopicSaleCanonicalDraft current = snapshotMapper.selectDraftByTopicIdForUpdate(topicId);
        if (current == null) return null;
        Long expected = current.getParameterVersion();
        if (expected == null || expected <= 0L) {
            throw new IllegalStateException("canonical 草稿版本非法");
        }
        if (snapshotMapper.advanceDraftVersion(topicId, expected, new Date()) != 1) {
            throw new IllegalStateException("canonical live mutation 版本冲突:" + mutationSource);
        }
        return expected + 1L;
    }
}
