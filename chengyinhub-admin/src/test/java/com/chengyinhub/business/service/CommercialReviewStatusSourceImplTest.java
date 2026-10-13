package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.support.CommercialReviewStatusSourceImpl;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommercialReviewStatusSourceImplTest {

    private AuditTaskMapper mapper;
    private CmsTopicMapper topicMapper;
    private CommercialTopicPolicy policy;
    private CommercialReviewStatusSourceImpl source;

    @BeforeEach
    void setUp() {
        mapper = mock(AuditTaskMapper.class);
        topicMapper = mock(CmsTopicMapper.class);
        policy = mock(CommercialTopicPolicy.class);
        source = new CommercialReviewStatusSourceImpl(mapper, topicMapper, policy);
    }

    @Test
    void missingPendingRejectedAndUnknownSaleReviewAllFailClosed() {
        when(mapper.selectAuditTaskList(any())).thenReturn(Collections.emptyList());
        assertThrows(IllegalStateException.class, () -> source.assertSaleApproved(9L));

        when(mapper.selectAuditTaskList(any())).thenReturn(Collections.singletonList(
                task(AuditTask.BIZ_TOPIC_SALE_REVIEW, AuditTask.STATUS_REJECTED, "{}")));
        assertThrows(IllegalStateException.class, () -> source.assertSaleApproved(9L));

        when(mapper.selectAuditTaskList(any())).thenReturn(Collections.singletonList(
                task(AuditTask.BIZ_TOPIC_SALE_REVIEW, 99, "{}")));
        assertThrows(IllegalStateException.class, () -> source.assertSaleApproved(9L));
    }

    @Test
    void onlyApprovedSaleReviewPasses() {
        CmsTopic topic = new CmsTopic();
        topic.setId(9L);
        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        parameters.put("topicId", 9L);
        when(topicMapper.selectCmsTopicById(9L)).thenReturn(topic);
        when(policy.evaluate(9L)).thenReturn(CommercialTopicPolicy.Evaluation.ugc());
        when(policy.saleParameters(org.mockito.ArgumentMatchers.same(topic), any())).thenReturn(parameters);
        when(mapper.selectAuditTaskList(any())).thenReturn(Collections.singletonList(
                task(AuditTask.BIZ_TOPIC_SALE_REVIEW, AuditTask.STATUS_APPROVED,
                        "{\"saleParameters\":{\"topicId\":9}}")));

        assertDoesNotThrow(() -> source.assertSaleApproved(9L));
    }

    @Test
    void approvedSaleReviewFailsClosedWhenCommercialParametersChanged() {
        CmsTopic topic = new CmsTopic();
        topic.setId(9L);
        Map<String, Object> changed = new LinkedHashMap<String, Object>();
        changed.put("topicId", 9L);
        changed.put("price", "101");
        when(topicMapper.selectCmsTopicById(9L)).thenReturn(topic);
        when(policy.evaluate(9L)).thenReturn(CommercialTopicPolicy.Evaluation.ugc());
        when(policy.saleParameters(org.mockito.ArgumentMatchers.same(topic), any())).thenReturn(changed);
        when(mapper.selectAuditTaskList(any())).thenReturn(Collections.singletonList(
                task(AuditTask.BIZ_TOPIC_SALE_REVIEW, AuditTask.STATUS_APPROVED,
                        "{\"saleParameters\":{\"topicId\":9,\"price\":\"100\"}}")));

        assertThrows(IllegalStateException.class, () -> source.assertSaleApproved(9L));
    }

    @Test
    void projectApprovalMustMatchRequestedDraftExit() {
        CmsTopic topic = new CmsTopic();
        topic.setId(9L);
        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        parameters.put("topicId", 9L);
        when(topicMapper.selectCmsTopicByIdForUpdate(9L)).thenReturn(topic);
        when(policy.evaluate(9L)).thenReturn(CommercialTopicPolicy.Evaluation.ugc());
        when(policy.projectParameters(org.mockito.ArgumentMatchers.same(topic), any())).thenReturn(parameters);
        when(mapper.selectAuditTaskList(any())).thenReturn(Collections.singletonList(
                task(AuditTask.BIZ_TOPIC_PROJECT_REVIEW, AuditTask.STATUS_APPROVED,
                        "{\"requestedTarget\":\"RECRUITING\",\"projectParameters\":{\"topicId\":9}}")));

        assertDoesNotThrow(() -> source.assertProjectApproved(9L, TopicLifecycleState.RECRUITING));
        assertThrows(IllegalStateException.class,
                () -> source.assertProjectApproved(9L, TopicLifecycleState.PRICING));
    }

    @Test
    void publicReadOnlyProjectApprovalRevalidatesSnapshotWithoutForUpdateLock() {
        CmsTopic topic = new CmsTopic();
        topic.setId(9L);
        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        parameters.put("topicId", 9L);
        when(topicMapper.selectCmsTopicById(9L)).thenReturn(topic);
        when(policy.evaluate(9L)).thenReturn(CommercialTopicPolicy.Evaluation.ugc());
        when(policy.projectParameters(org.mockito.ArgumentMatchers.same(topic), any())).thenReturn(parameters);
        when(mapper.selectAuditTaskList(any())).thenReturn(Collections.singletonList(
                task(AuditTask.BIZ_TOPIC_PROJECT_REVIEW, AuditTask.STATUS_APPROVED,
                        "{\"requestedTarget\":\"PRICING\",\"projectParameters\":{\"topicId\":9}}")));

        assertDoesNotThrow(() -> source.assertProjectApprovedReadOnly(9L, TopicLifecycleState.PRICING));
        verify(topicMapper).selectCmsTopicById(9L);
        verify(topicMapper, never()).selectCmsTopicByIdForUpdate(any());
    }

    @Test
    void approvedProjectReviewFailsClosedWhenFrozenProjectFactsCannotBeRevalidated() {
        when(mapper.selectAuditTaskList(any())).thenReturn(Collections.singletonList(
                task(AuditTask.BIZ_TOPIC_PROJECT_REVIEW, AuditTask.STATUS_APPROVED,
                        "{\"requestedTarget\":\"RECRUITING\",\"projectParameters\":{\"topicId\":9}}")));
        when(topicMapper.selectCmsTopicByIdForUpdate(9L)).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> source.assertProjectApproved(9L, TopicLifecycleState.RECRUITING));
    }

    @Test
    void approvedProjectReviewFailsClosedWhenProjectFactsChanged() {
        CmsTopic topic = new CmsTopic();
        topic.setId(9L);
        Map<String, Object> changed = new LinkedHashMap<String, Object>();
        changed.put("topicId", 9L);
        changed.put("addressName", "新商圈");
        when(topicMapper.selectCmsTopicByIdForUpdate(9L)).thenReturn(topic);
        when(policy.evaluate(9L)).thenReturn(CommercialTopicPolicy.Evaluation.ugc());
        when(policy.projectParameters(org.mockito.ArgumentMatchers.same(topic), any())).thenReturn(changed);
        when(mapper.selectAuditTaskList(any())).thenReturn(Collections.singletonList(
                task(AuditTask.BIZ_TOPIC_PROJECT_REVIEW, AuditTask.STATUS_APPROVED,
                        "{\"requestedTarget\":\"RECRUITING\",\"projectParameters\":"
                                + "{\"topicId\":9,\"addressName\":\"旧商圈\"}}")));

        assertThrows(IllegalStateException.class,
                () -> source.assertProjectApproved(9L, TopicLifecycleState.RECRUITING));
    }

    private static AuditTask task(String bizType, Integer status, String snapshot) {
        AuditTask task = new AuditTask();
        task.setId(1L);
        task.setBizType(bizType);
        task.setBizId(9L);
        task.setStatus(status);
        task.setSnapshotJson(snapshot);
        return task;
    }
}
