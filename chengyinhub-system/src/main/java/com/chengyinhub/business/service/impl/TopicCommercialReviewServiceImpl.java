package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.TopicSaleCanonicalDraft;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.TopicSaleSnapshotMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ITopicCommercialReviewService;
import com.chengyinhub.business.service.ITopicLifecycleService;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;
import com.chengyinhub.business.service.support.MerchantPublishedTopicTransferGate;
import com.chengyinhub.business.service.support.TopicSaleCanonicalBuilder;
import com.chengyinhub.business.service.support.TopicSaleCanonicalCodec;
import com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1;
import com.chengyinhub.common.constant.ProductType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/** H3/H4 商业主题提审；纯 UGC 不建审核任务，直接走原生命周期。 */
@Service
public class TopicCommercialReviewServiceImpl implements ITopicCommercialReviewService {

    private final CmsTopicMapper topicMapper;
    private final ITopicLifecycleService lifecycleService;
    private final IAuditTaskService auditTaskService;
    private final CommercialTopicPolicy commercialTopicPolicy;
    private final MerchantPublishedTopicTransferGate transferGate;
    private final TopicSaleSnapshotMapper saleSnapshotMapper;
    private final TopicSaleCanonicalBuilder canonicalBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TopicCommercialReviewServiceImpl(CmsTopicMapper topicMapper, ITopicLifecycleService lifecycleService,
                                            IAuditTaskService auditTaskService,
                                            CommercialTopicPolicy commercialTopicPolicy,
                                            MerchantPublishedTopicTransferGate transferGate,
                                            TopicSaleSnapshotMapper saleSnapshotMapper,
                                            TopicSaleCanonicalBuilder canonicalBuilder) {
        this.topicMapper = topicMapper;
        this.lifecycleService = lifecycleService;
        this.auditTaskService = auditTaskService;
        this.commercialTopicPolicy = commercialTopicPolicy;
        this.transferGate = transferGate;
        this.saleSnapshotMapper = saleSnapshotMapper;
        this.canonicalBuilder = canonicalBuilder;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submitProjectReview(Long topicId, Long submitterId, TopicLifecycleState requestedTarget) {
        if (requestedTarget != TopicLifecycleState.RECRUITING && requestedTarget != TopicLifecycleState.PRICING) {
            throw new IllegalArgumentException("立项审核目标只允许 RECRUITING 或 PRICING");
        }
        CmsTopic topic = lockedTopic(topicId);
        TopicLifecycleState state = TopicLifecycleState.fromCode(topic.getLifecycle());
        CommercialTopicPolicy.Evaluation evaluation = commercialTopicPolicy.evaluate(topicId);
        if (!evaluation.isCommercial()) {
            if (state != TopicLifecycleState.DRAFT
                    || !lifecycleService.transition(topicId, TopicLifecycleState.DRAFT, requestedTarget)) {
                throw new IllegalStateException("UGC 主题生命周期已变化，请刷新后重试");
            }
            return null;
        }
        if (state != TopicLifecycleState.DRAFT && state != TopicLifecycleState.PENDING_REVIEW) {
            throw new IllegalStateException("商业主题只有 DRAFT/PENDING_REVIEW 可提交立项审核");
        }
        Long taskId = auditTaskService.submitCommercialReview(AuditTask.BIZ_TOPIC_PROJECT_REVIEW, topicId,
                submitterId, snapshot("PROJECT", requestedTarget.name(), evaluation, topic, null, null));
        if (state == TopicLifecycleState.DRAFT
                && !lifecycleService.transition(topicId, TopicLifecycleState.DRAFT, TopicLifecycleState.PENDING_REVIEW)) {
            throw new IllegalStateException("主题状态已变化，立项审核提交已回滚");
        }
        return taskId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submitSaleReview(Long topicId, Long submitterId) {
        CmsTopic topic = lockedTopic(topicId);
        TopicLifecycleState state = TopicLifecycleState.fromCode(topic.getLifecycle());
        if (state != TopicLifecycleState.PRICING) {
            throw new IllegalStateException("只有 PRICING 主题可提交开售审核");
        }
        CommercialTopicPolicy.Evaluation evaluation = commercialTopicPolicy.evaluate(topicId);
        if (!evaluation.isCommercial()
                && !Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType())) return null;
        transferGate.assertTransferred(topic);
        TopicSaleCanonicalDraft draft = saleSnapshotMapper.selectDraftByTopicIdForUpdate(topicId);
        if (draft == null) throw new IllegalStateException("缺少 canonical15 草稿，拒绝开售提审");
        TopicSaleCanonicalV1 draftCanonical = TopicSaleCanonicalCodec.readDraft(
                draft.getSchemaVersion(), draft.getCanonicalJson());
        TopicSaleCanonicalV1 canonical = canonicalBuilder.rebuild(topic, draftCanonical);
        return auditTaskService.submitCommercialReview(AuditTask.BIZ_TOPIC_SALE_REVIEW, topicId, submitterId,
                snapshot("SALE", TopicLifecycleState.SELLING.name(), evaluation, topic, draft, canonical));
    }

    private CmsTopic lockedTopic(Long topicId) {
        if (topicId == null) throw new IllegalArgumentException("topicId 不能为空");
        CmsTopic topic = topicMapper.selectCmsTopicByIdForUpdate(topicId);
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())) {
            throw new IllegalArgumentException("主题不存在或已删除");
        }
        return topic;
    }

    private String snapshot(String phase, String requestedTarget, CommercialTopicPolicy.Evaluation evaluation,
                            CmsTopic topic, TopicSaleCanonicalDraft draft, TopicSaleCanonicalV1 canonical) {
        Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("phase", phase);
        snapshot.put("requestedTarget", requestedTarget);
        snapshot.put("commercialFacts", evaluation.toSnapshotFacts());
        if ("PROJECT".equals(phase)) {
            snapshot.put("projectParameters", commercialTopicPolicy.projectParameters(topic, evaluation));
        } else if ("SALE".equals(phase)) {
            snapshot.put("saleParameters", commercialTopicPolicy.saleParameters(topic, evaluation));
            snapshot.put("canonicalSchemaVersion", draft.getSchemaVersion());
            snapshot.put("canonicalParameterVersion", draft.getParameterVersion());
            snapshot.put("canonical15", canonical);
        }
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("商业审核快照生成失败", e);
        }
    }
}
