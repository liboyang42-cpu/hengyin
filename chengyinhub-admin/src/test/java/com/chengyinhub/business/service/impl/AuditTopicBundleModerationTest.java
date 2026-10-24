package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IQuotaService;
import com.chengyinhub.business.service.support.TopicBundleReviewService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditTopicBundleModerationTest {
    @Test
    void approvalLocksFreshTaskAndVerifiesBundleBeforePublishingTopic() {
        AuditTaskMapper tasks = mock(AuditTaskMapper.class);
        AuditRecordMapper records = mock(AuditRecordMapper.class);
        BusinessAuditEventMapper events = mock(BusinessAuditEventMapper.class);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        TopicBundleReviewService bundles = mock(TopicBundleReviewService.class);
        IQuotaService quota = mock(IQuotaService.class);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        IImService im = mock(IImService.class);

        AuditTask task = new AuditTask();
        task.setId(9L);
        task.setBizType(AuditTask.BIZ_TOPIC);
        task.setBizId(77L);
        task.setSubmitterId(5L);
        task.setSourceType("topic_bundle_v2");
        task.setSnapshotJson("{\"schemaVersion\":2}");
        task.setStatus(AuditTask.STATUS_PENDING);
        task.setVersion(3);
        AuditTask locked = new AuditTask();
        locked.setId(9L);
        locked.setBizType(AuditTask.BIZ_TOPIC);
        locked.setBizId(77L);
        locked.setSubmitterId(5L);
        locked.setSourceType("topic_bundle_v2");
        locked.setSnapshotJson(task.getSnapshotJson());
        locked.setStatus(AuditTask.STATUS_APPROVED);
        locked.setVersion(4);
        when(tasks.selectAuditTaskById(9L)).thenReturn(task);
        when(tasks.selectAuditTaskByIdForUpdate(9L)).thenReturn(locked);
        when(tasks.updateAuditTaskReviewCas(anyLong(), anyInt(), anyInt(), anyLong(), anyInt(), any())).thenReturn(1);
        when(records.insertAuditRecord(any())).thenReturn(1);
        when(events.insertBusinessAuditEvent(any())).thenReturn(1);
        CmsTopic topic = new CmsTopic();
        topic.setId(77L);
        topic.setMemberId(5L);
        when(topics.selectCmsTopicById(77L)).thenReturn(topic);
        when(topics.selectCmsTopicByIdForUpdate(77L)).thenReturn(topic);
        when(topics.updateCmsTopic(any())).thenReturn(1);
        when(quota.lockAndCheckThemeOnline(5L, 77L)).thenReturn(null);
        when(im.sendSystem(anyLong(), anyInt(), anyString(), anyString())).thenReturn(1L);

        AuditTaskServiceImpl service = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(service, "auditTaskMapper", tasks);
        ReflectionTestUtils.setField(service, "auditRecordMapper", records);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", events);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        com.chengyinhub.business.service.support.AppMediaPublicationGuard mediaGuard =
                com.chengyinhub.business.service.support.MediaPublicationTestFixtures.template(5L);
        com.chengyinhub.business.mapper.CmsTopicChapterMapper mediaChapters =
                mock(com.chengyinhub.business.mapper.CmsTopicChapterMapper.class);
        com.chengyinhub.business.mapper.CmsTopicNodeMapper mediaNodes =
                mock(com.chengyinhub.business.mapper.CmsTopicNodeMapper.class);
        when(mediaChapters.selectCmsTopicChapterList(any())).thenReturn(java.util.Collections.emptyList());
        when(mediaNodes.selectCmsTopicNodeList(any())).thenReturn(java.util.Collections.emptyList());
        ReflectionTestUtils.setField(mediaGuard, "topics", topics);
        ReflectionTestUtils.setField(mediaGuard, "chapters", mediaChapters);
        ReflectionTestUtils.setField(mediaGuard, "nodes", mediaNodes);
        ReflectionTestUtils.setField(service, "mediaPublicationGuard", mediaGuard);
        ReflectionTestUtils.setField(service, "topicBundleReviewService", bundles);
        ReflectionTestUtils.setField(service, "quotaService", quota);
        ReflectionTestUtils.setField(service, "cmsTopicServiceProvider", provider(topicService));
        ReflectionTestUtils.setField(service, "imServiceProvider", provider(im));

        AuditReviewDTO request = new AuditReviewDTO();
        request.setStatus(AuditTask.STATUS_APPROVED);
        request.setExpectedVersion(3);
        request.setRequestId("topic-bundle-version-3");
        service.review(9L, 100L, request);

        org.mockito.InOrder order = inOrder(bundles, topics, topicService);
        order.verify(bundles).applyDecision(77L, 5L, task.getSnapshotJson(), true);
        order.verify(topics).updateCmsTopic(any());
        order.verify(topicService).publishApprovedRouteSnapshot(77L);

        ArgumentCaptor<BusinessAuditEvent> event = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(events).insertBusinessAuditEvent(event.capture());
        when(tasks.selectAuditTaskById(9L)).thenReturn(locked);
        when(events.selectByDomainActionRequestForUpdate("PLATFORM", 9L,
                "AUDIT_DECISION_APPROVE", request.getRequestId())).thenReturn(event.getValue());
        assertEquals(1, service.review(9L, 100L, request));
        request.setExpectedVersion(2);
        ServiceException conflict = assertThrows(ServiceException.class, () -> service.review(9L, 100L, request));
        assertEquals(HttpStatus.CONFLICT, conflict.getCode());
        verify(tasks).updateAuditTaskReviewCas(anyLong(), anyInt(), anyInt(), anyLong(), anyInt(), any());
        verify(records).insertAuditRecord(any());
        verify(events).insertBusinessAuditEvent(any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {2, 4})
    void missingOrStaleReviewerVersionCannotDecideCurrentBundle(Integer expectedVersion) {
        AuditTaskMapper tasks = mock(AuditTaskMapper.class);
        AuditRecordMapper records = mock(AuditRecordMapper.class);
        BusinessAuditEventMapper events = mock(BusinessAuditEventMapper.class);
        TopicBundleReviewService bundles = mock(TopicBundleReviewService.class);
        AuditTask task = new AuditTask();
        task.setId(9L);
        task.setBizType(AuditTask.BIZ_TOPIC);
        task.setSourceType("topic_bundle_v2");
        task.setStatus(AuditTask.STATUS_PENDING);
        task.setVersion(3);
        when(tasks.selectAuditTaskById(9L)).thenReturn(task);
        AuditTaskServiceImpl service = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(service, "auditTaskMapper", tasks);
        ReflectionTestUtils.setField(service, "auditRecordMapper", records);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", events);
        ReflectionTestUtils.setField(service, "topicBundleReviewService", bundles);
        for (Integer status : new Integer[] {AuditTask.STATUS_APPROVED, AuditTask.STATUS_REJECTED}) {
            AuditReviewDTO request = new AuditReviewDTO();
            request.setStatus(status);
            request.setExpectedVersion(expectedVersion);
            request.setReasonText("local decision");
            request.setRequestId("bundle-version-" + expectedVersion + "-" + status);
            ServiceException conflict = assertThrows(ServiceException.class,
                    () -> service.review(9L, 100L, request));
            assertEquals(HttpStatus.CONFLICT, conflict.getCode());
        }
        verify(tasks, never()).updateAuditTaskReviewCas(anyLong(), anyInt(), anyInt(), anyLong(), anyInt(), any());
        verifyNoInteractions(records, bundles);
        verify(events, never()).insertBusinessAuditEvent(any());
    }

    private <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(value);
        return provider;
    }
}
