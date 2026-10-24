package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.impl.AuditTaskServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R19-PENDINGSHELL 接线守卫:内容审核通过才是自办活动壳的建壳时点。
 *
 * <p>壳(status=1/publish_status=1)会立刻进公开活动列表并可下单 —— pro 主题发布后在待审期不得有壳,
 * 必须由过审回调补建(幂等)。驳回分支不得建壳。壳本身的建/不建判据见
 * {@link TopicPendingReviewSelfHostShellTest}。
 */
class AuditTopicApprovalSelfHostShellTest {

    private static final Long TASK = 9L;
    private static final Long TOPIC = 77L;

    private AuditTaskServiceImpl service;
    private ICmsTopicService topicService;

    @BeforeEach
    void setUp() {
        AuditTaskMapper taskMapper = mock(AuditTaskMapper.class);
        AuditRecordMapper recordMapper = mock(AuditRecordMapper.class);
        BusinessAuditEventMapper auditEventMapper = mock(BusinessAuditEventMapper.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        IQuotaService quotaService = mock(IQuotaService.class);
        topicService = mock(ICmsTopicService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ICmsTopicService> topicServiceProvider = mock(ObjectProvider.class);

        service = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(service, "auditTaskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "auditRecordMapper", recordMapper);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", auditEventMapper);
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
        task.setId(TASK);
        task.setBizType(AuditTask.BIZ_TOPIC);
        task.setBizId(TOPIC);
        task.setStatus(AuditTask.STATUS_PENDING);
        task.setVersion(0);
        when(taskMapper.selectAuditTaskById(TASK)).thenReturn(task);
        when(taskMapper.updateAuditTaskReviewCas(anyLong(), anyInt(), anyInt(), anyLong(), anyInt(), any()))
                .thenReturn(1);
        when(recordMapper.insertAuditRecord(any())).thenReturn(1);
        when(auditEventMapper.insertBusinessAuditEvent(any())).thenReturn(1);

        CmsTopic exist = new CmsTopic();
        exist.setId(TOPIC);
        exist.setMemberId(5L);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(exist);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(exist);
        when(topicMapper.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);
        // 配额未满 → 过审自动上架
        when(quotaService.lockAndCheckThemeOnline(5L, TOPIC)).thenReturn(null);
    }

    /** 过审:主题 status 写 1 之后,必须回调补建自办活动壳(否则过审后玩家无票可买)。 */
    @Test
    void approvalEnsuresSelfHostActivityShell() {
        AuditReviewDTO decision = new AuditReviewDTO();
        decision.setStatus(AuditTask.STATUS_APPROVED);

        service.review(TASK, 1L, decision);

        verify(topicService).ensureSelfHostActivityShell(TOPIC);
    }

    /** 负控:驳回不出壳(驳回后主题 status=2,补建口自己也会按未过审提前返回,这里同时守住调用点)。 */
    @Test
    void rejectionDoesNotEnsureSelfHostActivityShell() {
        AuditReviewDTO decision = new AuditReviewDTO();
        decision.setStatus(AuditTask.STATUS_REJECTED);
        decision.setReasonText("内容不合规");

        service.review(TASK, 1L, decision);

        verify(topicService, never()).ensureSelfHostActivityShell(anyLong());
    }
}
