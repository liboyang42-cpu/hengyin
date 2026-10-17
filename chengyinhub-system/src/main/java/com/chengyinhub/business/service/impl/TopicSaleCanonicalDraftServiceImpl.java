package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.TopicSaleCanonicalDraft;
import com.chengyinhub.business.domain.dto.TopicSaleCanonicalDraftDTO;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.TopicSaleSnapshotMapper;
import com.chengyinhub.business.service.ITopicSaleCanonicalDraftService;
import com.chengyinhub.business.service.support.TopicSaleCanonicalCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

@Service
public class TopicSaleCanonicalDraftServiceImpl implements ITopicSaleCanonicalDraftService {
    private final CmsTopicMapper topicMapper;
    private final TopicSaleSnapshotMapper snapshotMapper;
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.chengyinhub.business.service.ITopicCommercialReviewService commercialReviewService;

    public TopicSaleCanonicalDraftServiceImpl(CmsTopicMapper topicMapper, TopicSaleSnapshotMapper snapshotMapper) {
        this.topicMapper = topicMapper;
        this.snapshotMapper = snapshotMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TopicSaleCanonicalDraft save(Long topicId, Long operatorId, TopicSaleCanonicalDraftDTO request) {
        if (topicId == null || operatorId == null || request == null) {
            throw new IllegalArgumentException("canonical 草稿参数不完整");
        }
        if (!TopicSaleCanonicalV1.SCHEMA_VERSION.equals(request.getSchemaVersion())) {
            throw new IllegalStateException("SALE_SNAPSHOT_UNKNOWN_SCHEMA_VERSION:" + request.getSchemaVersion());
        }
        CmsTopic topic = topicMapper.selectCmsTopicByIdForUpdate(topicId);
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())) throw new IllegalStateException("主题不存在");
        if (!Integer.valueOf(TopicLifecycleState.PRICING.getCode()).equals(topic.getLifecycle())) {
            throw new IllegalStateException("canonical 草稿只允许在 PRICING 编辑");
        }
        String json = TopicSaleCanonicalCodec.writeDraft(request.getCanonical());
        TopicSaleCanonicalDraft current = snapshotMapper.selectDraftByTopicIdForUpdate(topicId);
        TopicSaleCanonicalDraft write = new TopicSaleCanonicalDraft();
        write.setTopicId(topicId);
        write.setSchemaVersion(request.getSchemaVersion());
        write.setCanonicalJson(json);
        write.setUpdatedBy(operatorId);
        write.setUpdateTime(new Date());
        if (current == null) {
            if (request.getExpectedParameterVersion() != null
                    && request.getExpectedParameterVersion().longValue() != 0L) {
                throw new IllegalStateException("canonical 草稿版本冲突");
            }
            write.setParameterVersion(1L);
            if (snapshotMapper.insertDraft(write) != 1) throw new IllegalStateException("canonical 草稿创建失败");
        } else {
            Long expected = request.getExpectedParameterVersion();
            if (expected == null || !expected.equals(current.getParameterVersion())) {
                throw new IllegalStateException("canonical 草稿版本冲突");
            }
            write.setParameterVersion(expected + 1L);
            if (snapshotMapper.updateDraftVersioned(write, expected) != 1) {
                throw new IllegalStateException("canonical 草稿版本冲突");
            }
        }
        TopicSaleCanonicalDraft readback = snapshotMapper.selectDraftByTopicId(topicId);
        if (readback == null || !write.getSchemaVersion().equals(readback.getSchemaVersion())
                || !write.getParameterVersion().equals(readback.getParameterVersion())
                || !write.getCanonicalJson().equals(readback.getCanonicalJson())) {
            throw new IllegalStateException("canonical 草稿写后回读不一致");
        }
        readback.setSaleReviewProgress(commercialReviewService.submitSaleReviewIfReady(topicId, topic.getMemberId()));
        return readback;
    }

    @Override
    public TopicSaleCanonicalDraft get(Long topicId) {
        return snapshotMapper.selectDraftByTopicId(topicId);
    }
}
