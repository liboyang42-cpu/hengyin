package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditRecord;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.service.ITopicLifecycleService;
import com.chengyinhub.business.service.ITopicSaleFinalizationService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditTaskCommercialReviewTest {

    private AuditTaskMapper taskMapper;
    private AuditRecordMapper recordMapper;
    private ITopicLifecycleService lifecycleService;
    private ITopicSaleFinalizationService saleFinalizationService;
    private CmsTopicMapper cmsTopicMapper;
    private com.chengyinhub.business.service.ITopicCommercialReviewService commercialReviewService;
    private CoopInviteMapper coopInviteMapper;
    private INotifyService notifyService;
    private CoopInviteServiceImpl coopInviteService;
    private AuditTaskServiceImpl service;
    private com.chengyinhub.business.service.IImService imService;

    @BeforeEach
    void setUp() {
        taskMapper = mock(AuditTaskMapper.class);
        recordMapper = mock(AuditRecordMapper.class);
        lifecycleService = mock(ITopicLifecycleService.class);
        saleFinalizationService = mock(ITopicSaleFinalizationService.class);
        cmsTopicMapper = mock(CmsTopicMapper.class);
        coopInviteMapper = mock(CoopInviteMapper.class);
        notifyService = mock(INotifyService.class);
        coopInviteService = spy(new CoopInviteServiceImpl());
        org.springframework.test.util.ReflectionTestUtils.setField(coopInviteService, "commercialReviewService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.ITopicCommercialReviewService.class));
        ReflectionTestUtils.setField(coopInviteService, "cmsTopicMapper", cmsTopicMapper);
        ReflectionTestUtils.setField(coopInviteService, "coopInviteMapper", coopInviteMapper);
        ReflectionTestUtils.setField(coopInviteService, "notifyService", notifyService);
        ObjectProvider<ICoopInviteService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(coopInviteService);
        service = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(service, "auditTaskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "auditRecordMapper", recordMapper);
        com.chengyinhub.business.mapper.BusinessAuditEventMapper auditMapper =
                mock(com.chengyinhub.business.mapper.BusinessAuditEventMapper.class);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", auditMapper);
        ReflectionTestUtils.setField(service, "topicLifecycleService", lifecycleService);
        ReflectionTestUtils.setField(service, "topicSaleFinalizationService", saleFinalizationService);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", cmsTopicMapper);
        commercialReviewService = mock(com.chengyinhub.business.service.ITopicCommercialReviewService.class);
        ObjectProvider<com.chengyinhub.business.service.ITopicCommercialReviewService> commercialProvider = mock(ObjectProvider.class);
        when(commercialProvider.getObject()).thenReturn(commercialReviewService);
        ReflectionTestUtils.setField(service, "commercialReviewServiceProvider", commercialProvider);
        ReflectionTestUtils.setField(service, "coopInviteServiceProvider", provider);
        imService = mock(com.chengyinhub.business.service.IImService.class);
        ObjectProvider<com.chengyinhub.business.service.IImService> imProvider = mock(ObjectProvider.class);
        when(imProvider.getObject()).thenReturn(imService);
        ReflectionTestUtils.setField(service, "imServiceProvider", imProvider);
        CmsTopic ownerTopic = new CmsTopic();
        ownerTopic.setId(9L);
        ownerTopic.setMemberId(990009L);
        when(cmsTopicMapper.selectCmsTopicById(9L)).thenReturn(ownerTopic);
        when(cmsTopicMapper.updateTopicDraftState(any(), any())).thenReturn(1);
        when(recordMapper.insertAuditRecord(any())).thenReturn(1);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);
    }

    @Test
    void commercialSubmissionAppendsSubmitRecordAndReusesOpenTaskIdempotently() {
        when(taskMapper.selectAuditTaskList(any())).thenReturn(Collections.emptyList());
        doAnswer(invocation -> {
            AuditTask task = invocation.getArgument(0);
            task.setId(71L);
            return 1;
        }).when(taskMapper).insertAuditTask(any(AuditTask.class));

        Long id = service.submitCommercialReview(AuditTask.BIZ_TOPIC_PROJECT_REVIEW, 9L, 3L,
                "{\"requestedTarget\":\"RECRUITING\"}");

        assertEquals(71L, id);
        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(recordMapper).insertAuditRecord(record.capture());
        assertEquals("SUBMIT", record.getValue().getAction());

        AuditTask open = new AuditTask();
        open.setId(71L);
        open.setStatus(AuditTask.STATUS_PENDING);
        when(taskMapper.selectAuditTaskList(any())).thenReturn(Collections.singletonList(open));
        when(taskMapper.refreshPendingSnapshot(eq(71L), any(), eq(3L), any())).thenReturn(1);
        assertEquals(71L, service.submitCommercialReview(AuditTask.BIZ_TOPIC_PROJECT_REVIEW, 9L, 3L,
                "{\"requestedTarget\":\"RECRUITING\"}"));
        verify(taskMapper).insertAuditTask(any(AuditTask.class));
        verify(taskMapper).refreshPendingSnapshot(eq(71L), eq("{\"requestedTarget\":\"RECRUITING\"}"), eq(3L), any());
    }

    @Test
    void topicBundleSubmissionRefreshesTheLockedOpenTaskSnapshot() {
        AuditTask open = new AuditTask();
        open.setId(91L);
        open.setBizType(AuditTask.BIZ_TOPIC);
        open.setBizId(9L);
        open.setStatus(AuditTask.STATUS_ESCALATED);
        open.setSourceType("topic_bundle_v2");
        when(taskMapper.selectOpenAuditTaskForUpdate(AuditTask.BIZ_TOPIC, 9L)).thenReturn(open);
        when(taskMapper.refreshOpenAuditSnapshot(eq(91L), eq(AuditTask.BIZ_TOPIC), eq("topic_bundle_v2"),
                eq("{\"schemaVersion\":2}"), eq(3L), any()))
                .thenReturn(1);

        assertEquals(91L, service.submitOrRefreshTopicBundle(9L, 3L, "{\"schemaVersion\":2}"));

        verify(taskMapper).refreshOpenAuditSnapshot(eq(91L), eq(AuditTask.BIZ_TOPIC), eq("topic_bundle_v2"),
                eq("{\"schemaVersion\":2}"), eq(3L), any());
        verify(taskMapper, never()).insertAuditTask(any());
    }

    @Test
    void topicBundleSubmissionNeverOverwritesAnOpenReportTask() {
        AuditTask report = new AuditTask();
        report.setId(92L);
        report.setBizType(AuditTask.BIZ_TOPIC);
        report.setBizId(9L);
        report.setStatus(AuditTask.STATUS_PENDING);
        report.setSourceType("report");
        when(taskMapper.selectOpenAuditTaskForUpdate(AuditTask.BIZ_TOPIC, 9L)).thenReturn(report);

        assertThrows(IllegalStateException.class,
                () -> service.submitOrRefreshTopicBundle(9L, 3L, "{\"schemaVersion\":2}"));

        verify(taskMapper, never()).refreshOpenAuditSnapshot(any(), any(), any(), any(), any(), any());
        verify(taskMapper, never()).insertAuditTask(any());
    }

    @Test
    void rejectedHistoricalTaskDoesNotBlockResubmissionAndCreatesNewAppendOnlyRecord() {
        AuditTask rejected = new AuditTask();
        rejected.setId(70L);
        rejected.setStatus(AuditTask.STATUS_REJECTED);
        when(taskMapper.selectAuditTaskList(any())).thenReturn(Collections.singletonList(rejected));
        doAnswer(invocation -> {
            AuditTask task = invocation.getArgument(0);
            task.setId(72L);
            return 1;
        }).when(taskMapper).insertAuditTask(any(AuditTask.class));

        Long id = service.submitCommercialReview(AuditTask.BIZ_TOPIC_PROJECT_REVIEW, 9L, 3L,
                "{\"requestedTarget\":\"RECRUITING\"}");

        assertEquals(72L, id);
        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(recordMapper).insertAuditRecord(record.capture());
        assertEquals(72L, record.getValue().getTaskId());
        assertEquals("SUBMIT", record.getValue().getAction());
    }

    @Test
    void concurrentInsertLosingUniqueGuardReusesWinnerWithoutDuplicateRecord() {
        AuditTask winner = new AuditTask();
        winner.setId(73L);
        winner.setStatus(AuditTask.STATUS_PENDING);
        when(taskMapper.selectAuditTaskList(any()))
                .thenReturn(Collections.emptyList(), Collections.singletonList(winner));
        org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException("uk_audit_biz_open"))
                .when(taskMapper).insertAuditTask(any(AuditTask.class));
        when(taskMapper.refreshPendingSnapshot(eq(73L), any(), eq(3L), any())).thenReturn(1);

        Long id = service.submitCommercialReview(AuditTask.BIZ_TOPIC_SALE_REVIEW, 9L, 3L,
                "{\"saleParameters\":{}}");

        assertEquals(73L, id);
        verify(recordMapper, never()).insertAuditRecord(any());
    }

    @Test
    void approvedAndRejectedProjectReviewsDriveOnlyTheirAllowedLifecycleExit() {
        AuditTask approved = task(80L, AuditTask.STATUS_PENDING,
                "{\"requestedTarget\":\"PRICING\"}");
        when(taskMapper.selectAuditTaskById(80L)).thenReturn(approved);
        when(taskMapper.updateAuditTaskReviewCas(eq(80L), eq(AuditTask.STATUS_PENDING), eq(AuditTask.STATUS_APPROVED), eq(100L), eq(0), any()))
                .thenReturn(1);
        when(lifecycleService.transition(9L, TopicLifecycleState.PENDING_REVIEW, TopicLifecycleState.PRICING))
                .thenReturn(true);

        CmsTopic approvedTopic = new CmsTopic();
        approvedTopic.setId(9L); approvedTopic.setMemberId(3L);
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(9L)).thenReturn(approvedTopic);
        service.review(80L, 100L, review(AuditTask.STATUS_APPROVED));

        verify(lifecycleService).transition(9L, TopicLifecycleState.PENDING_REVIEW, TopicLifecycleState.PRICING);
        verify(cmsTopicMapper).updateTopicDraftState(9L, 0);
        verify(commercialReviewService).submitSaleReviewIfReady(9L, 3L);

        AuditTask rejected = task(81L, AuditTask.STATUS_PENDING,
                "{\"requestedTarget\":\"RECRUITING\"}");
        when(taskMapper.selectAuditTaskById(81L)).thenReturn(rejected);
        when(taskMapper.updateAuditTaskReviewCas(eq(81L), eq(AuditTask.STATUS_PENDING), eq(AuditTask.STATUS_REJECTED), eq(100L), eq(0), any()))
                .thenReturn(1);
        when(lifecycleService.transition(9L, TopicLifecycleState.PENDING_REVIEW, TopicLifecycleState.DRAFT))
                .thenReturn(true);

        service.review(81L, 100L, review(AuditTask.STATUS_REJECTED));

        verify(lifecycleService).transition(9L, TopicLifecycleState.PENDING_REVIEW, TopicLifecycleState.DRAFT);
        verify(cmsTopicMapper).updateTopicDraftState(9L, 1);
    }

    @Test
    void rejectedProjectRestoresPrivateDraftAndReopensPastDateEditing() {
        AuditTask task = task(84L, AuditTask.STATUS_PENDING,
                "{\"requestedTarget\":\"RECRUITING\"}");
        when(taskMapper.selectAuditTaskById(84L)).thenReturn(task);
        when(taskMapper.updateAuditTaskReviewCas(eq(84L), eq(AuditTask.STATUS_PENDING),
                eq(AuditTask.STATUS_REJECTED), eq(100L), eq(0), any())).thenReturn(1);
        when(lifecycleService.transition(9L, TopicLifecycleState.PENDING_REVIEW,
                TopicLifecycleState.DRAFT)).thenReturn(true);
        int[] draft = {0};
        doAnswer(invocation -> {
            draft[0] = invocation.getArgument(1);
            return 1;
        }).when(cmsTopicMapper).updateTopicDraftState(eq(9L), any());
        CmsTopic topic = new CmsTopic();
        topic.setStatus(1);
        topic.setLifecycle(TopicLifecycleState.DRAFT.getCode());
        Date now = new Date();
        topic.setStartDate(new Date(now.getTime() - 60000L));
        assertThrows(IllegalStateException.class,
                () -> com.chengyinhub.business.util.TopicEditWindow.resolve(topic, draft[0], now));

        service.review(84L, 100L, review(AuditTask.STATUS_REJECTED));

        assertEquals(1, draft[0]);
        assertEquals(com.chengyinhub.business.util.TopicEditWindow.Scope.FULL,
                com.chengyinhub.business.util.TopicEditWindow.resolve(topic, draft[0], now));
    }

    @Test
    void projectReviewFailsWhenDraftFlagCannotBeSaved() {
        AuditTask task = task(85L, AuditTask.STATUS_PENDING,
                "{\"requestedTarget\":\"RECRUITING\"}");
        when(taskMapper.selectAuditTaskById(85L)).thenReturn(task);
        when(taskMapper.updateAuditTaskReviewCas(eq(85L), eq(AuditTask.STATUS_PENDING),
                eq(AuditTask.STATUS_REJECTED), eq(100L), eq(0), any())).thenReturn(1);
        when(lifecycleService.transition(9L, TopicLifecycleState.PENDING_REVIEW,
                TopicLifecycleState.DRAFT)).thenReturn(true);
        when(cmsTopicMapper.updateTopicDraftState(9L, 1)).thenReturn(0);

        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service.review(85L, 100L, review(AuditTask.STATUS_REJECTED)));
    }

    @Test
    void concurrentDuplicateReviewThatLosesCasWritesNoRecordOrLifecycle() {
        AuditTask task = task(82L, AuditTask.STATUS_PENDING, "{\"requestedTarget\":\"PRICING\"}");
        when(taskMapper.selectAuditTaskById(82L)).thenReturn(task);
        when(taskMapper.updateAuditTaskReviewCas(eq(82L), eq(AuditTask.STATUS_PENDING), eq(AuditTask.STATUS_APPROVED), eq(100L), eq(0), any()))
                .thenReturn(0);

        assertEquals(0, service.review(82L, 100L, review(AuditTask.STATUS_APPROVED)));

        verify(recordMapper, never()).insertAuditRecord(any());
        verify(lifecycleService, never()).transition(any(), any(), any());
    }

    @Test
    void approvedShopDaySaleReviewFinalizesWithoutDepositOrReminder() {
        AuditTask sale = task(83L, AuditTask.STATUS_PENDING,
                "{\"saleParameters\":{\"topicId\":9}}");
        sale.setBizType(AuditTask.BIZ_TOPIC_SALE_REVIEW);
        when(taskMapper.selectAuditTaskById(83L)).thenReturn(sale);
        when(taskMapper.updateAuditTaskReviewCas(eq(83L), eq(AuditTask.STATUS_PENDING), eq(AuditTask.STATUS_APPROVED), eq(100L), eq(0), any()))
                .thenReturn(1);
        CmsTopic shopDay = new CmsTopic();
        shopDay.setId(9L);
        shopDay.setProductType(ProductType.FREE_EXPLORE);
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(9L)).thenReturn(shopDay);
        when(coopInviteMapper.selectAcceptedByTopicForUpdate(9L)).thenReturn(
                Collections.singletonList(unpaidMerchantInvite()));
        when(coopInviteMapper.updateDepositIfCurrent(any(), any(), any(), any(), any())).thenReturn(1);

        service.review(83L, 100L, review(AuditTask.STATUS_APPROVED));

        verify(saleFinalizationService).finalizeForSale(9L);
        verify(coopInviteService).markTopicDepositsDue(9L);
        verify(coopInviteMapper, never()).updateDeposit(any(), any(), any(), any(), any());
        verify(coopInviteMapper, never()).updateDepositIfCurrent(any(), any(), any(), any(), any());
        verify(notifyService, never()).notifyCoopStatus(any(), any(), any(), any(), any());
    }

    @Test
    void approvedClassicSaleReviewFinalizesAndMarksDepositOnce() {
        AuditTask sale = task(84L, AuditTask.STATUS_PENDING,
                "{\"saleParameters\":{\"topicId\":9}}");
        sale.setBizType(AuditTask.BIZ_TOPIC_SALE_REVIEW);
        when(taskMapper.selectAuditTaskById(84L)).thenReturn(sale);
        when(taskMapper.updateAuditTaskReviewCas(eq(84L), eq(AuditTask.STATUS_PENDING), eq(AuditTask.STATUS_APPROVED), eq(100L), eq(0), any()))
                .thenReturn(1);
        CmsTopic classic = new CmsTopic();
        classic.setId(9L);
        classic.setName("经典定向");
        classic.setProductType(ProductType.ORIENTEERING);
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(9L)).thenReturn(classic);
        when(coopInviteMapper.selectAcceptedByTopicForUpdate(9L)).thenReturn(
                Collections.singletonList(unpaidMerchantInvite()));
        when(coopInviteMapper.updateDepositIfCurrent(any(), any(), any(), any(), any())).thenReturn(1);

        service.review(84L, 100L, review(AuditTask.STATUS_APPROVED));

        verify(saleFinalizationService).finalizeForSale(9L);
        verify(coopInviteMapper, times(1)).updateDepositIfCurrent(eq(501L), eq(0), isNull(), eq(5),
                eq(ICoopInviteService.DEPOSIT_AMOUNT));
        verify(notifyService, times(1)).notifyCoopStatus(eq(200L), eq("经典定向"), eq("系统"),
                eq("待缴保证金"), eq("主题已锁价开卖，请缴纳¥50保证金"));
    }

    @Test
    void approvedUnknownProductSaleReviewFailsClosedToClassicDepositPath() {
        AuditTask sale = task(85L, AuditTask.STATUS_PENDING,
                "{\"saleParameters\":{\"topicId\":9}}");
        sale.setBizType(AuditTask.BIZ_TOPIC_SALE_REVIEW);
        when(taskMapper.selectAuditTaskById(85L)).thenReturn(sale);
        when(taskMapper.updateAuditTaskReviewCas(eq(85L), eq(AuditTask.STATUS_PENDING), eq(AuditTask.STATUS_APPROVED), eq(100L), eq(0), any()))
                .thenReturn(1);
        CmsTopic unknown = new CmsTopic();
        unknown.setId(9L);
        unknown.setName("未知产品");
        unknown.setProductType(99);
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(9L)).thenReturn(unknown);
        when(coopInviteMapper.selectAcceptedByTopicForUpdate(9L)).thenReturn(
                Collections.singletonList(unpaidMerchantInvite()));
        when(coopInviteMapper.updateDepositIfCurrent(any(), any(), any(), any(), any())).thenReturn(1);

        service.review(85L, 100L, review(AuditTask.STATUS_APPROVED));

        verify(coopInviteMapper, times(1)).updateDepositIfCurrent(eq(501L), eq(0), isNull(), eq(5),
                eq(ICoopInviteService.DEPOSIT_AMOUNT));
    }

    @Test
    void approvedNullProductSaleReviewFailsClosedToClassicDepositPath() {
        AuditTask sale = task(86L, AuditTask.STATUS_PENDING,
                "{\"saleParameters\":{\"topicId\":9}}");
        sale.setBizType(AuditTask.BIZ_TOPIC_SALE_REVIEW);
        when(taskMapper.selectAuditTaskById(86L)).thenReturn(sale);
        when(taskMapper.updateAuditTaskReviewCas(eq(86L), eq(AuditTask.STATUS_PENDING), eq(AuditTask.STATUS_APPROVED), eq(100L), eq(0), any()))
                .thenReturn(1);
        CmsTopic unknown = new CmsTopic();
        unknown.setId(9L);
        unknown.setName("缺失产品类型");
        unknown.setProductType(null);
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(9L)).thenReturn(unknown);
        when(coopInviteMapper.selectAcceptedByTopicForUpdate(9L)).thenReturn(
                Collections.singletonList(unpaidMerchantInvite()));
        when(coopInviteMapper.updateDepositIfCurrent(any(), any(), any(), any(), any())).thenReturn(1);

        service.review(86L, 100L, review(AuditTask.STATUS_APPROVED));

        verify(coopInviteMapper, times(1)).updateDepositIfCurrent(eq(501L), eq(0), isNull(), eq(5),
                eq(ICoopInviteService.DEPOSIT_AMOUNT));
    }

    @Test
    void platformSubmittedProjectRejectionNotifiesTopicOwnerInsteadOfSameNumberAppMember() {
        AuditTask task = task(90L, AuditTask.STATUS_PENDING, "{}");
        task.setSubmitterId(1L);
        ReflectionTestUtils.invokeMethod(service, "notifyReviewResult", task, review(AuditTask.STATUS_REJECTED));

        verify(imService).sendSystem(eq(990009L), any(), eq("审核未通过"), any());
        verify(imService, never()).sendSystem(eq(1L), any(), any(), any());
    }

    @Test
    void platformSubmittedSaleApprovalAlsoNotifiesTopicOwner() {
        AuditTask task = task(91L, AuditTask.STATUS_PENDING, "{}");
        task.setBizType(AuditTask.BIZ_TOPIC_SALE_REVIEW);
        task.setSubmitterId(1L);
        ReflectionTestUtils.invokeMethod(service, "notifyReviewResult", task, review(AuditTask.STATUS_APPROVED));

        verify(imService).sendSystem(eq(990009L), any(), eq("审核通过"), any());
        verify(imService, never()).sendSystem(eq(1L), any(), any(), any());
    }

    @Test
    void commercialReviewWithoutCurrentOwnerDoesNotFallBackToPlatformSubmitter() {
        AuditTask task = task(92L, AuditTask.STATUS_PENDING, "{}");
        task.setSubmitterId(1L);
        when(cmsTopicMapper.selectCmsTopicById(9L)).thenReturn(null);

        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "notifyReviewResult", task,
                        review(AuditTask.STATUS_REJECTED)));
        verify(imService, never()).sendSystem(any(), any(), any(), any());
    }

    private static CoopInvite unpaidMerchantInvite() {
        CoopInvite invite = new CoopInvite();
        invite.setId(501L);
        invite.setTopicId(9L);
        invite.setStatus(1);
        invite.setInviteType(0);
        invite.setToType("merchant");
        invite.setToId(200L);
        invite.setDepositStatus(0);
        return invite;
    }

    private static AuditTask task(Long id, Integer status, String snapshot) {
        AuditTask task = new AuditTask();
        task.setId(id);
        task.setBizType(AuditTask.BIZ_TOPIC_PROJECT_REVIEW);
        task.setBizId(9L);
        task.setStatus(status);
        task.setVersion(0);
        task.setSnapshotJson(snapshot);
        return task;
    }

    private static AuditReviewDTO review(Integer status) {
        AuditReviewDTO dto = new AuditReviewDTO();
        dto.setStatus(status);
        dto.setReasonText("人工审核");
        return dto;
    }
}
