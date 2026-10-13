package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.TopicSaleCanonicalDraft;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.TopicSaleSnapshotMapper;
import com.chengyinhub.business.service.impl.TopicCommercialReviewServiceImpl;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;
import com.chengyinhub.business.service.support.MerchantPublishedTopicTransferGate;
import com.chengyinhub.business.service.support.TopicSaleCanonicalBuilder;
import com.chengyinhub.business.service.support.TopicSaleCanonicalCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopicCommercialReviewServiceImplTest {

    private CmsTopicMapper topicMapper;
    private ITopicLifecycleService lifecycleService;
    private IAuditTaskService auditTaskService;
    private CommercialTopicPolicy policy;
    private MerchantPublishedTopicTransferGate transferGate;
    private TopicSaleSnapshotMapper saleSnapshotMapper;
    private TopicSaleCanonicalBuilder canonicalBuilder;
    private TopicCommercialReviewServiceImpl service;

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        lifecycleService = mock(ITopicLifecycleService.class);
        auditTaskService = mock(IAuditTaskService.class);
        policy = mock(CommercialTopicPolicy.class);
        transferGate = mock(MerchantPublishedTopicTransferGate.class);
        saleSnapshotMapper = mock(TopicSaleSnapshotMapper.class);
        canonicalBuilder = mock(TopicSaleCanonicalBuilder.class);
        service = new TopicCommercialReviewServiceImpl(topicMapper, lifecycleService, auditTaskService, policy,
                transferGate, saleSnapshotMapper, canonicalBuilder);
        when(topicMapper.selectCmsTopicByIdForUpdate(9L)).thenReturn(topic(TopicLifecycleState.DRAFT));
    }

    @Test
    void commercialProjectSubmissionMovesToPendingAndKeepsRequestedExitInSnapshot() {
        CmsTopic topic = topic(TopicLifecycleState.DRAFT);
        when(topicMapper.selectCmsTopicByIdForUpdate(9L)).thenReturn(topic);
        CommercialTopicPolicy.Evaluation evaluation = CommercialTopicPolicy.Evaluation.commercial("paidTicket", 11L);
        when(policy.evaluate(9L)).thenReturn(evaluation);
        when(policy.projectParameters(same(topic), same(evaluation)))
                .thenReturn(java.util.Collections.<String, Object>singletonMap("topicId", 9L));
        when(lifecycleService.transition(9L, TopicLifecycleState.DRAFT, TopicLifecycleState.PENDING_REVIEW))
                .thenReturn(true);
        when(auditTaskService.submitCommercialReview(eq(AuditTask.BIZ_TOPIC_PROJECT_REVIEW), eq(9L), eq(3L),
                contains("RECRUITING"))).thenReturn(70L);

        assertEquals(70L, service.submitProjectReview(9L, 3L, TopicLifecycleState.RECRUITING));
        verify(lifecycleService).transition(9L, TopicLifecycleState.DRAFT, TopicLifecycleState.PENDING_REVIEW);
        verify(auditTaskService).submitCommercialReview(eq(AuditTask.BIZ_TOPIC_PROJECT_REVIEW), eq(9L), eq(3L),
                contains("projectParameters"));
    }

    @Test
    void pureUgcTakesRequestedDraftExitWithoutCreatingAuditTask() {
        when(policy.evaluate(9L)).thenReturn(CommercialTopicPolicy.Evaluation.ugc());
        when(lifecycleService.transition(9L, TopicLifecycleState.DRAFT, TopicLifecycleState.PRICING)).thenReturn(true);

        assertNull(service.submitProjectReview(9L, 3L, TopicLifecycleState.PRICING));
        verify(auditTaskService, never()).submitCommercialReview(eq(AuditTask.BIZ_TOPIC_PROJECT_REVIEW), eq(9L),
                eq(3L), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void freeExplorePricingExitCannotBypassProjectReviewBeforeTicketsOrOffersExist() {
        CmsTopic topic = topic(TopicLifecycleState.DRAFT);
        CommercialTopicPolicy.Evaluation evaluation = CommercialTopicPolicy.Evaluation.freeExplore();
        when(topicMapper.selectCmsTopicByIdForUpdate(9L)).thenReturn(topic);
        when(policy.evaluate(9L)).thenReturn(evaluation);
        when(policy.projectParameters(same(topic), same(evaluation)))
                .thenReturn(java.util.Collections.<String, Object>singletonMap("topicId", 9L));
        when(lifecycleService.transition(9L, TopicLifecycleState.DRAFT, TopicLifecycleState.PENDING_REVIEW))
                .thenReturn(true);
        when(auditTaskService.submitCommercialReview(eq(AuditTask.BIZ_TOPIC_PROJECT_REVIEW), eq(9L), eq(3L),
                contains("PRICING"))).thenReturn(72L);

        assertEquals(72L, service.submitProjectReview(9L, 3L, TopicLifecycleState.PRICING));
        verify(lifecycleService, never()).transition(9L, TopicLifecycleState.DRAFT, TopicLifecycleState.PRICING);
        verify(lifecycleService).transition(9L, TopicLifecycleState.DRAFT, TopicLifecycleState.PENDING_REVIEW);
    }

    @Test
    void freeExploreRecruitingExitCannotBypassProjectReviewBeforeTicketsOrOffersExist() {
        CmsTopic topic = topic(TopicLifecycleState.DRAFT);
        CommercialTopicPolicy.Evaluation evaluation = CommercialTopicPolicy.Evaluation.freeExplore();
        when(topicMapper.selectCmsTopicByIdForUpdate(9L)).thenReturn(topic);
        when(policy.evaluate(9L)).thenReturn(evaluation);
        when(policy.projectParameters(same(topic), same(evaluation)))
                .thenReturn(java.util.Collections.<String, Object>singletonMap("topicId", 9L));
        when(lifecycleService.transition(9L, TopicLifecycleState.DRAFT, TopicLifecycleState.PENDING_REVIEW))
                .thenReturn(true);
        when(auditTaskService.submitCommercialReview(eq(AuditTask.BIZ_TOPIC_PROJECT_REVIEW), eq(9L), eq(3L),
                contains("RECRUITING"))).thenReturn(73L);

        assertEquals(73L, service.submitProjectReview(9L, 3L, TopicLifecycleState.RECRUITING));
        verify(lifecycleService, never()).transition(9L, TopicLifecycleState.DRAFT, TopicLifecycleState.RECRUITING);
        verify(lifecycleService).transition(9L, TopicLifecycleState.DRAFT, TopicLifecycleState.PENDING_REVIEW);
    }

    @Test
    void saleReviewRequiresPricingAndCommercialFacts() {
        when(topicMapper.selectCmsTopicByIdForUpdate(9L)).thenReturn(topic(TopicLifecycleState.RECRUITING));
        assertThrows(IllegalStateException.class, () -> service.submitSaleReview(9L, 3L));
    }

    @Test
    void saleReviewChecksMerchantTransferBeforeCreatingAuditTask() {
        CmsTopic topic = topic(TopicLifecycleState.PRICING);
        when(topicMapper.selectCmsTopicByIdForUpdate(9L)).thenReturn(topic);
        when(policy.evaluate(9L)).thenReturn(CommercialTopicPolicy.Evaluation.commercial("paidTicket", 11L));
        org.mockito.Mockito.doThrow(new IllegalStateException("商家主题未转交俱乐部"))
                .when(transferGate).assertTransferred(topic);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.submitSaleReview(9L, 3L));

        assertTrue(error.getMessage().contains("未转交俱乐部"));
        verify(auditTaskService, never()).submitCommercialReview(eq(AuditTask.BIZ_TOPIC_SALE_REVIEW), eq(9L),
                eq(3L), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void validMerchantTransferAllowsSaleReviewSubmission() {
        CmsTopic topic = topic(TopicLifecycleState.PRICING);
        CommercialTopicPolicy.Evaluation evaluation =
                CommercialTopicPolicy.Evaluation.commercial("paidTicket", 11L);
        when(topicMapper.selectCmsTopicByIdForUpdate(9L)).thenReturn(topic);
        when(policy.evaluate(9L)).thenReturn(evaluation);
        TopicSaleCanonicalV1 canonical = completeCanonical();
        TopicSaleCanonicalDraft draft = new TopicSaleCanonicalDraft();
        draft.setTopicId(9L);
        draft.setSchemaVersion(TopicSaleCanonicalV1.SCHEMA_VERSION);
        draft.setParameterVersion(3L);
        draft.setCanonicalJson(TopicSaleCanonicalCodec.write(canonical));
        when(saleSnapshotMapper.selectDraftByTopicIdForUpdate(9L)).thenReturn(draft);
        when(canonicalBuilder.rebuild(eq(topic), org.mockito.ArgumentMatchers.any(TopicSaleCanonicalV1.class)))
                .thenReturn(canonical);
        when(auditTaskService.submitCommercialReview(eq(AuditTask.BIZ_TOPIC_SALE_REVIEW), eq(9L), eq(3L),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(71L);

        assertEquals(71L, service.submitSaleReview(9L, 3L));

        verify(transferGate).assertTransferred(topic);
        verify(auditTaskService).submitCommercialReview(eq(AuditTask.BIZ_TOPIC_SALE_REVIEW), eq(9L), eq(3L),
                contains("canonicalParameterVersion"));
    }

    @Test
    void saleReviewWithoutCanonicalDraftFailsClosed() {
        CmsTopic topic = topic(TopicLifecycleState.PRICING);
        when(topicMapper.selectCmsTopicByIdForUpdate(9L)).thenReturn(topic);
        when(policy.evaluate(9L)).thenReturn(CommercialTopicPolicy.Evaluation.commercial("paidTicket", 11L));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.submitSaleReview(9L, 3L));

        assertTrue(error.getMessage().contains("缺少 canonical15 草稿"));
        verify(auditTaskService, never()).submitCommercialReview(eq(AuditTask.BIZ_TOPIC_SALE_REVIEW), eq(9L),
                eq(3L), org.mockito.ArgumentMatchers.anyString());
    }

    private static CmsTopic topic(TopicLifecycleState state) {
        CmsTopic topic = new CmsTopic();
        topic.setId(9L);
        topic.setLifecycle(state.getCode());
        topic.setDelFlag(0);
        return topic;
    }

    private static TopicSaleCanonicalV1 completeCanonical() {
        return TopicSaleCanonicalTestFixture.canonical();
    }
    @Test
    void autoSaleWithoutCanonicalKeepsPricingAndReturnsAnExplicitNextStep() {
        when(topicMapper.selectCmsTopicByIdForUpdate(9L)).thenReturn(topic(TopicLifecycleState.PRICING));
        com.chengyinhub.business.domain.vo.TopicSaleReviewProgress result = service.submitSaleReviewIfReady(9L, 3L);
        assertNull(result.getTaskId());
        assertTrue(result.getNextStep().contains("保存完整开售参数"));
        verify(auditTaskService, never()).submitCommercialReview(eq(AuditTask.BIZ_TOPIC_SALE_REVIEW), eq(9L), eq(3L), org.mockito.ArgumentMatchers.anyString());
        verify(lifecycleService, never()).transition(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void autoSaleWaitsForNormalTransferButDoesNotSwallowInvalidTransferFacts() {
        CmsTopic topic = topic(TopicLifecycleState.PRICING);
        when(topicMapper.selectCmsTopicByIdForUpdate(9L)).thenReturn(topic);
        when(transferGate.pendingTransferStep(topic)).thenReturn("请等待俱乐部接受主题合作邀请");
        assertTrue(service.submitSaleReviewIfReady(9L, 3L).getNextStep().contains("等待俱乐部"));
        when(transferGate.pendingTransferStep(topic)).thenThrow(new IllegalStateException("坏合同"));
        assertThrows(IllegalStateException.class, () -> service.submitSaleReviewIfReady(9L, 3L));
        verify(auditTaskService, never()).submitCommercialReview(eq(AuditTask.BIZ_TOPIC_SALE_REVIEW), eq(9L), eq(3L), org.mockito.ArgumentMatchers.anyString());
    }

}
