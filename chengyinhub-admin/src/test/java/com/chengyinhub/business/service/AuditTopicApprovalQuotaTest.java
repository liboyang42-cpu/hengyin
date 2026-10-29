package com.chengyinhub.business.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审核过审自动上架的配额闸单测(补 #2 遗留旁路)。
 *
 * 规则:BIZ_TOPIC 过审(APPROVE)自动上架前必须过 quotaService.lockAndCheckThemeOnline
 * (行锁+持锁复查的单一实现);配额满时审核结论照旧生效(status=1)但不自动上架
 * (userStatus=0),留给主人走自助上架(同一把锁)。无归属会员(官方/存量脏数据)
 * 不占个人配额,保持旧行为直接上架。驳回(REJECT)不碰配额。
 */
class AuditTopicApprovalQuotaTest {

    private static final Long TASK_ID = 9L;
    private static final Long TOPIC_ID = 77L;
    private static final Long OWNER_ID = 100L;

    private AuditTaskMapper auditTaskMapper;
    private AuditRecordMapper auditRecordMapper;
    private CmsTopicMapper cmsTopicMapper;
    private IQuotaService quotaService;
    private ICmsTopicService cmsTopicService;
    private ObjectProvider<ICmsTopicService> cmsTopicServiceProvider;
    private AuditTaskServiceImpl svc;
    private CmsTopic topic;

    @BeforeEach
    void setUp() {
        auditTaskMapper = mock(AuditTaskMapper.class);
        auditRecordMapper = mock(AuditRecordMapper.class);
        cmsTopicMapper = mock(CmsTopicMapper.class);
        quotaService = mock(IQuotaService.class);
        cmsTopicService = mock(ICmsTopicService.class);
        cmsTopicServiceProvider = mock(ObjectProvider.class);

        svc = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(svc, "auditTaskMapper", auditTaskMapper);
        ReflectionTestUtils.setField(svc, "auditRecordMapper", auditRecordMapper);
        com.chengyinhub.business.mapper.BusinessAuditEventMapper auditMapper =
                mock(com.chengyinhub.business.mapper.BusinessAuditEventMapper.class);
        ReflectionTestUtils.setField(svc, "businessAuditEventMapper", auditMapper);
        ReflectionTestUtils.setField(svc, "cmsTopicMapper", cmsTopicMapper);
        com.chengyinhub.business.service.support.AppMediaPublicationGuard mediaGuard =
                com.chengyinhub.business.service.support.MediaPublicationTestFixtures.template(100L);
        com.chengyinhub.business.mapper.CmsTopicChapterMapper mediaChapters =
                mock(com.chengyinhub.business.mapper.CmsTopicChapterMapper.class);
        com.chengyinhub.business.mapper.CmsTopicNodeMapper mediaNodes =
                mock(com.chengyinhub.business.mapper.CmsTopicNodeMapper.class);
        when(mediaChapters.selectCmsTopicChapterList(any())).thenReturn(java.util.Collections.emptyList());
        when(mediaNodes.selectCmsTopicNodeList(any())).thenReturn(java.util.Collections.emptyList());
        ReflectionTestUtils.setField(mediaGuard, "topics", cmsTopicMapper);
        ReflectionTestUtils.setField(mediaGuard, "chapters", mediaChapters);
        ReflectionTestUtils.setField(mediaGuard, "nodes", mediaNodes);
        ReflectionTestUtils.setField(svc, "mediaPublicationGuard", mediaGuard);
        ReflectionTestUtils.setField(svc, "quotaService", quotaService);
        ReflectionTestUtils.setField(svc, "cmsTopicServiceProvider", cmsTopicServiceProvider);
        when(cmsTopicServiceProvider.getObject()).thenReturn(cmsTopicService);

        AuditTask task = new AuditTask();
        task.setId(TASK_ID);
        task.setBizType(AuditTask.BIZ_TOPIC);
        task.setBizId(TOPIC_ID);
        task.setStatus(AuditTask.STATUS_PENDING);
        task.setVersion(0);
        when(auditTaskMapper.selectAuditTaskById(TASK_ID)).thenReturn(task);
        when(auditTaskMapper.updateAuditTaskReviewCas(anyLong(), anyInt(), anyInt(), anyLong(), anyInt(), any())).thenReturn(1);
        when(auditRecordMapper.insertAuditRecord(any())).thenReturn(1);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);

        topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setMemberId(OWNER_ID);
        when(cmsTopicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
        when(cmsTopicMapper.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);
    }

    private AuditReviewDTO decision(Integer status) {
        AuditReviewDTO dto = new AuditReviewDTO();
        dto.setStatus(status);
        dto.setReasonText("审核依据");
        return dto;
    }

    private CmsTopic capturedUpdate() {
        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(cmsTopicMapper).updateCmsTopic(cap.capture());
        return cap.getValue();
    }

    @Test
    void postReviewApprovalPreservesOwnersOfflineChoiceAndPublishedRoute() {
        topic.setStatus(1);
        topic.setUserStatus(0);
        topic.setPublishedConfigVersion(1L);
        svc.review(TASK_ID, 1L, decision(AuditTask.STATUS_APPROVED));
        assertEquals(1, capturedUpdate().getCheckStatus());
        assertEquals(0, capturedUpdate().getUserStatus());
        verify(cmsTopicService, never()).publishApprovedRouteSnapshot(TOPIC_ID);
    }

    @Test
    void approveUnderQuota_gatesThenOnlines() {
        when(quotaService.lockAndCheckThemeOnline(OWNER_ID, TOPIC_ID)).thenReturn(null);

        List<ILoggingEvent> events = captureServiceLogs(
                () -> svc.review(TASK_ID, 1L, decision(AuditTask.STATUS_APPROVED)));

        // 必须先过闸(锁+复查)再写库
        InOrder order = inOrder(quotaService, cmsTopicMapper);
        order.verify(quotaService).lockAndCheckThemeOnline(OWNER_ID, TOPIC_ID);
        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        order.verify(cmsTopicMapper).updateCmsTopic(cap.capture());
        assertEquals(1, cap.getValue().getStatus());
        assertEquals(1, cap.getValue().getCheckStatus());
        assertEquals("", cap.getValue().getReason());
        assertEquals(1, cap.getValue().getUserStatus());
        // 合法 egg 路径:上架后照旧放行彩蛋(0→1),且不产生任何告警噪音
        assertTrue(events.stream().noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN)),
                events.toString());
    }

    @Test
    void approveBranchGraph_publishesSnapshotOnlyAfterTopicStatusSucceeds() {
        topic.setRouteMode("BRANCH_GRAPH");
        when(quotaService.lockAndCheckThemeOnline(OWNER_ID, TOPIC_ID)).thenReturn(null);

        svc.review(TASK_ID, 1L, decision(AuditTask.STATUS_APPROVED));

        InOrder order = inOrder(cmsTopicMapper, cmsTopicService);
        order.verify(cmsTopicMapper).updateCmsTopic(any(CmsTopic.class));
        order.verify(cmsTopicService).publishApprovedRouteSnapshot(TOPIC_ID);
    }

    @Test
    void approveLinearTopic_publishesNullGraphSnapshotVersionAfterStatusSucceeds() {
        topic.setRouteMode("LINEAR");
        when(quotaService.lockAndCheckThemeOnline(OWNER_ID, TOPIC_ID)).thenReturn(null);

        svc.review(TASK_ID, 1L, decision(AuditTask.STATUS_APPROVED));

        InOrder order = inOrder(cmsTopicMapper, cmsTopicService);
        order.verify(cmsTopicMapper).updateCmsTopic(any(CmsTopic.class));
        order.verify(cmsTopicService).publishApprovedRouteSnapshot(TOPIC_ID);
    }

    @Test
    void approveBranchGraph_statusWriteFailureNeverPublishesSnapshot() {
        topic.setRouteMode("BRANCH_GRAPH");
        when(quotaService.lockAndCheckThemeOnline(OWNER_ID, TOPIC_ID)).thenReturn(null);
        when(cmsTopicMapper.updateCmsTopic(any(CmsTopic.class))).thenReturn(0);

        assertThrows(RuntimeException.class,
                () -> svc.review(TASK_ID, 1L, decision(AuditTask.STATUS_APPROVED)));

        verify(cmsTopicService, never()).publishApprovedRouteSnapshot(anyLong());
    }

    @Test
    void approveOverQuota_approvesButStaysOffline() {
        when(quotaService.lockAndCheckThemeOnline(OWNER_ID, TOPIC_ID)).thenReturn("同时上架的主题已达上限（2）");

        svc.review(TASK_ID, 1L, decision(AuditTask.STATUS_APPROVED));

        CmsTopic upd = capturedUpdate();
        assertEquals(1, upd.getStatus());     // 审核结论生效
        assertEquals(0, upd.getUserStatus()); // 但不自动上架 = 不超发
    }

    @Test
    void approveOwnerlessTopic_onlinesWithoutQuota() {
        // 官方/存量脏数据主题无归属会员:不占个人配额,保持旧行为直接上架,不许被闸永久卡死
        topic.setMemberId(null);

        svc.review(TASK_ID, 1L, decision(AuditTask.STATUS_APPROVED));

        verify(quotaService, never()).lockAndCheckThemeOnline(anyLong(), anyLong());
        CmsTopic upd = capturedUpdate();
        assertEquals(1, upd.getStatus());
        assertEquals(1, upd.getUserStatus());
    }

    /** 在捕获 AuditTaskServiceImpl logger 事件的前提下执行动作,返回本次产生的事件。 */
    private List<ILoggingEvent> captureServiceLogs(Runnable action) {
        Logger svcLogger = (Logger) LoggerFactory.getLogger(AuditTaskServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        svcLogger.addAppender(appender);
        try {
            action.run();
        } finally {
            svcLogger.detachAppender(appender);
        }
        return appender.list;
    }

    @Test
    void reject_neverTouchesQuota() {
        topic.setRouteMode("BRANCH_GRAPH");
        svc.review(TASK_ID, 1L, decision(AuditTask.STATUS_REJECTED));

        verify(quotaService, never()).lockAndCheckThemeOnline(anyLong(), anyLong());
        verify(cmsTopicService, never()).publishApprovedRouteSnapshot(anyLong());
        CmsTopic upd = capturedUpdate();
        assertEquals(2, upd.getStatus());
        assertEquals(2, upd.getCheckStatus());
        assertEquals("审核依据", upd.getReason());
        assertEquals(0, upd.getUserStatus());
    }
}
