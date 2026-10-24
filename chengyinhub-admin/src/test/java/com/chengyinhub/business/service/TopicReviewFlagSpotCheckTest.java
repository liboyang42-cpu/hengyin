package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.impl.AuditTaskServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUDIT-INT-8(#377 × #380):cms_topic.review_flag(#377 加列)= 放行但进入抽检池。
 * 主题先发后审打标(post_review_flag 单)时写 1,抽检通过清 0;驳回不清(内容已下架)。
 */
class TopicReviewFlagSpotCheckTest {

    private AuditTaskMapper taskMapper;
    private CmsTopicMapper topicMapper;
    private IQuotaService quotaService;

    private AuditTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(AuditTaskMapper.class);
        AuditRecordMapper recordMapper = mock(AuditRecordMapper.class);
        topicMapper = mock(CmsTopicMapper.class);
        quotaService = mock(IQuotaService.class);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        ObjectProvider<ICmsTopicService> topicServiceProvider = mock(ObjectProvider.class);
        service = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(service, "auditTaskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "auditRecordMapper", recordMapper);
        com.chengyinhub.business.mapper.BusinessAuditEventMapper auditMapper =
                mock(com.chengyinhub.business.mapper.BusinessAuditEventMapper.class);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", auditMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        com.chengyinhub.business.service.support.AppMediaPublicationGuard mediaGuard =
                com.chengyinhub.business.service.support.MediaPublicationTestFixtures.template(5L);
        com.chengyinhub.business.mapper.CmsTopicChapterMapper mediaChapters =
                mock(com.chengyinhub.business.mapper.CmsTopicChapterMapper.class);
        com.chengyinhub.business.mapper.CmsTopicNodeMapper mediaNodes =
                mock(com.chengyinhub.business.mapper.CmsTopicNodeMapper.class);
        when(mediaChapters.selectCmsTopicChapterList(any())).thenReturn(java.util.Collections.emptyList());
        when(mediaNodes.selectCmsTopicNodeList(any())).thenReturn(java.util.Collections.emptyList());
        ReflectionTestUtils.setField(mediaGuard, "topics", topicMapper);
        ReflectionTestUtils.setField(mediaGuard, "chapters", mediaChapters);
        ReflectionTestUtils.setField(mediaGuard, "nodes", mediaNodes);
        ReflectionTestUtils.setField(service, "mediaPublicationGuard", mediaGuard);
        ReflectionTestUtils.setField(service, "quotaService", quotaService);
        ReflectionTestUtils.setField(service, "cmsTopicServiceProvider", topicServiceProvider);
        when(topicServiceProvider.getObject()).thenReturn(topicService);

        AuditTask task = new AuditTask();
        task.setId(9L);
        task.setBizType(AuditTask.BIZ_TOPIC);
        task.setBizId(77L);
        task.setStatus(AuditTask.STATUS_PENDING);
        task.setVersion(0);
        task.setSourceType("post_review_flag");
        when(taskMapper.selectAuditTaskById(9L)).thenReturn(task);
        when(taskMapper.updateAuditTaskReviewCas(anyLong(), anyInt(), anyInt(), anyLong(), anyInt(), any())).thenReturn(1);
        when(recordMapper.insertAuditRecord(any())).thenReturn(1);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);

        CmsTopic exist = new CmsTopic();
        exist.setId(77L);
        exist.setMemberId(5L);
        when(topicMapper.selectCmsTopicById(77L)).thenReturn(exist);
        when(topicMapper.selectCmsTopicByIdForUpdate(77L)).thenReturn(exist);
        when(topicMapper.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);
        // 配额未满 → 过审自动上架
        when(quotaService.lockAndCheckThemeOnline(5L, 77L)).thenReturn(null);
    }

    @Test
    void passingSpotCheckClearsReviewFlag() {
        AuditReviewDTO decision = new AuditReviewDTO();
        decision.setStatus(AuditTask.STATUS_APPROVED);

        service.review(9L, 1L, decision);

        verify(topicMapper).updateReviewFlag(77L, 0);
    }

    @Test
    void rejectedSpotCheckKeepsReviewFlag() {
        AuditReviewDTO decision = new AuditReviewDTO();
        decision.setStatus(AuditTask.STATUS_REJECTED);
        decision.setReasonText("内容不合规");

        service.review(9L, 1L, decision);

        org.mockito.Mockito.verify(topicMapper, org.mockito.Mockito.never()).updateReviewFlag(anyLong(), anyInt());
    }

    private CmsTopic capturedTopic() {
        ArgumentCaptor<CmsTopic> captor = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).updateCmsTopic(captor.capture());
        return captor.getValue();
    }
}
