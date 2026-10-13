package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.AuditRecord;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.domain.RoamHangout;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.domain.dto.AuditRevertDTO;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.RoamHangoutMapper;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.ITopicCommercialReviewService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.business.service.ITopicSaleFinalizationService;
import com.chengyinhub.business.service.ITopicSaleCanonicalDraftService;
import com.chengyinhub.business.service.IRoamHangoutService;
import com.chengyinhub.business.domain.dto.TopicCommercialReviewSubmitDTO;
import com.chengyinhub.business.domain.dto.TopicSaleCanonicalDraftDTO;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.service.impl.AuditTaskServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditWorkbenchControllerTest {

    private AuditTaskMapper auditTaskMapper;
    private AuditRecordMapper auditRecordMapper;
    private AuditTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        auditTaskMapper = mock(AuditTaskMapper.class);
        auditRecordMapper = mock(AuditRecordMapper.class);
        service = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(service, "auditTaskMapper", auditTaskMapper);
        ReflectionTestUtils.setField(service, "auditRecordMapper", auditRecordMapper);
        BusinessAuditEventMapper auditEventMapper = mock(BusinessAuditEventMapper.class);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", auditEventMapper);
        when(auditRecordMapper.insertAuditRecord(any(AuditRecord.class))).thenReturn(1);
        when(auditEventMapper.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(1);
    }

    @Test
    void reviewAllowsPendingToApproved() {
        AuditTask task = task(10L, AuditTask.STATUS_PENDING, 3);
        AuditReviewDTO request = review(AuditTask.STATUS_APPROVED, "pass");
        when(auditTaskMapper.selectAuditTaskById(10L)).thenReturn(task);
        when(auditTaskMapper.updateAuditTaskReviewCas(eq(10L), eq(AuditTask.STATUS_PENDING),
                eq(AuditTask.STATUS_APPROVED), eq(100L), eq(3), any())).thenReturn(1);

        int rows = service.review(10L, 100L, request);

        assertEquals(1, rows);
        verify(auditRecordMapper).insertAuditRecord(any(AuditRecord.class));
    }

    @Test
    void reviewAllowsEscalatedToRejected() {
        AuditTask task = task(11L, AuditTask.STATUS_ESCALATED, 1);
        AuditReviewDTO request = review(AuditTask.STATUS_REJECTED, "risk");
        when(auditTaskMapper.selectAuditTaskById(11L)).thenReturn(task);
        when(auditTaskMapper.updateAuditTaskReviewCas(eq(11L), eq(AuditTask.STATUS_ESCALATED),
                eq(AuditTask.STATUS_REJECTED), eq(100L), eq(1), any())).thenReturn(1);

        int rows = service.review(11L, 100L, request);

        assertEquals(1, rows);
        verify(auditRecordMapper).insertAuditRecord(any(AuditRecord.class));
    }

    @Test
    void laterUnfoundedHangoutReportDoesNotRestoreEarlierUpheldSanction() {
        AuditTask current = task(12L, AuditTask.STATUS_PENDING, 1);
        current.setBizType(AuditTask.BIZ_HANGOUT);
        current.setBizId(88L);
        current.setSourceType("report");
        AuditTask earlierUpheld = task(11L, AuditTask.STATUS_REJECTED, 1);
        earlierUpheld.setBizType(AuditTask.BIZ_HANGOUT);
        earlierUpheld.setBizId(88L);
        earlierUpheld.setSourceType("report");
        IRoamHangoutService hangoutService = mock(IRoamHangoutService.class);
        @SuppressWarnings("unchecked") ObjectProvider<IRoamHangoutService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(hangoutService);
        ReflectionTestUtils.setField(service, "roamHangoutServiceProvider", provider);
        when(auditTaskMapper.selectAuditTaskById(12L)).thenReturn(current);
        when(auditTaskMapper.selectAuditTaskList(any(AuditTask.class))).thenReturn(Collections.singletonList(earlierUpheld));
        when(auditTaskMapper.updateAuditTaskReviewCas(eq(12L), eq(AuditTask.STATUS_PENDING),
                eq(AuditTask.STATUS_APPROVED), eq(100L), eq(1), any())).thenReturn(1);

        assertEquals(1, service.review(12L, 100L, review(AuditTask.STATUS_APPROVED, "证据不足")));

        verify(hangoutService, never()).moderate(88L, false);
    }

    @Test
    void reviewRejectsApprovedToRejected() {
        AuditTask task = task(12L, AuditTask.STATUS_APPROVED, 1);
        when(auditTaskMapper.selectAuditTaskById(12L)).thenReturn(task);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.review(12L, 100L, review(AuditTask.STATUS_REJECTED, "rollback")));

        assertEquals("当前审核状态不允许该操作", ex.getMessage());
        verify(auditTaskMapper, never()).updateAuditTaskReviewCas(any(), any(), any(), any(), any(), any());
        verify(auditRecordMapper, never()).insertAuditRecord(any(AuditRecord.class));
    }

    @Test
    void reviewReturnsZeroWhenVersionChanged() {
        AuditTask task = task(13L, AuditTask.STATUS_PENDING, 2);
        when(auditTaskMapper.selectAuditTaskById(13L)).thenReturn(task);
        when(auditTaskMapper.updateAuditTaskReviewCas(eq(13L), eq(AuditTask.STATUS_PENDING),
                eq(AuditTask.STATUS_APPROVED), eq(100L), eq(2), any())).thenReturn(0);

        int rows = service.review(13L, 100L, review(AuditTask.STATUS_APPROVED, "pass"));

        assertEquals(0, rows);
        verify(auditRecordMapper, never()).insertAuditRecord(any(AuditRecord.class));
    }

    @Test
    void controllerReviewReturnsConflictMessageWhenVersionChanged() {
        IAuditTaskService mockService = mock(IAuditTaskService.class);
        AuditWorkbenchController controller = new TestableAuditWorkbenchController(100L);
        ReflectionTestUtils.setField(controller, "auditTaskService", mockService);
        when(mockService.review(eq(10L), eq(100L), any(AuditReviewDTO.class))).thenReturn(0);

        AjaxResult result = controller.review(reviewWithId(10L, AuditTask.STATUS_APPROVED, "pass"));

        assertEquals(com.chengyinhub.common.constant.HttpStatus.CONFLICT, result.get(AjaxResult.CODE_TAG));
        assertEquals("审核状态已变化，请刷新后重试", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void controllerReviewReturnsActionableConflictWhenBundleSnapshotIsStale() {
        IAuditTaskService mockService = mock(IAuditTaskService.class);
        AuditWorkbenchController controller = new TestableAuditWorkbenchController(100L);
        ReflectionTestUtils.setField(controller, "auditTaskService", mockService);
        when(mockService.review(eq(10L), eq(100L), any(AuditReviewDTO.class))).thenThrow(
                new ServiceException("提交内容已变化，请创作者重新提交审核", HttpStatus.CONFLICT));

        AjaxResult result = controller.review(reviewWithId(10L, AuditTask.STATUS_APPROVED, "pass"));

        assertEquals(HttpStatus.CONFLICT, result.get(AjaxResult.CODE_TAG));
        assertEquals("提交内容已变化，请创作者重新提交审核", result.get(AjaxResult.MSG_TAG));
        assertEquals("AUDIT_SNAPSHOT_STALE", result.get("errorCode"));
    }

    @Test
    void upheldHangoutReportNotifiesMergedReportersAndHistoricalMembersInsideService() {
        IImService im = mock(IImService.class);
        ObjectProvider<IImService> imProvider = mock(ObjectProvider.class);
        when(imProvider.getObject()).thenReturn(im);
        ReflectionTestUtils.setField(service, "imServiceProvider", imProvider);
        RoamHangoutMapper hangouts = mock(RoamHangoutMapper.class);
        ReflectionTestUtils.setField(service, "roamHangoutMapper", hangouts);
        com.chengyinhub.business.mapper.ImConversationMemberMapper members =
                mock(com.chengyinhub.business.mapper.ImConversationMemberMapper.class);
        ReflectionTestUtils.setField(service, "imConversationMemberMapper", members);
        IRoamHangoutService moderation = mock(IRoamHangoutService.class);
        ObjectProvider<IRoamHangoutService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(moderation);
        ReflectionTestUtils.setField(service, "roamHangoutServiceProvider", provider);
        AuditTask task = task(40L, AuditTask.STATUS_PENDING, 1);
        task.setBizType(AuditTask.BIZ_HANGOUT);
        task.setBizId(88L);
        task.setSourceType("report");
        task.setSubmitterId(9009L);
        RoamHangout hangout = new RoamHangout();
        hangout.setMemberId(9004L);
        hangout.setConversationId(500L);
        when(hangouts.selectByIdForUpdate(88L)).thenReturn(hangout);
        com.chengyinhub.business.domain.ImConversationMember participant =
                new com.chengyinhub.business.domain.ImConversationMember();
        participant.setMemberId(9005L);
        participant.setDeleted(1); // 关闭后的历史成员仍可被通知。
        when(members.selectByConversationForUpdate(500L)).thenReturn(Collections.singletonList(participant));
        when(auditRecordMapper.selectReportsForUpdate(40L)).thenReturn(java.util.Arrays.asList(
                reportRecord(9009L), reportRecord(9008L), reportRecord(9008L)));
        when(auditTaskMapper.selectAuditTaskById(40L)).thenReturn(task);
        when(auditTaskMapper.updateAuditTaskReviewCas(eq(40L), eq(0), eq(2), eq(100L), eq(1), any())).thenReturn(1);

        assertEquals(1, service.review(40L, 100L, review(2, "确认违规")));

        verify(im).sendSystem(eq(9009L), eq(ImMessage.MSG_CARD), eq("举报已处理"), org.mockito.ArgumentMatchers.contains("确认违规"));
        verify(im).sendSystem(eq(9008L), eq(ImMessage.MSG_CARD), eq("举报已处理"), org.mockito.ArgumentMatchers.contains("40"));
        verify(im).sendSystem(eq(9004L), eq(ImMessage.MSG_CARD), eq("你的组局已关闭"), org.mockito.ArgumentMatchers.contains("result"));
        verify(im).sendSystem(eq(9005L), eq(ImMessage.MSG_CARD), eq("你参与的组局已关闭"), any());
        org.mockito.Mockito.verifyNoMoreInteractions(im);
    }

    @Test
    void deletedHangoutUsesFrozenOwnerAndDoesNotExposePrivateEvidenceInNotification() {
        AuditTask task = task(40L, AuditTask.STATUS_PENDING, 1);
        task.setBizType(AuditTask.BIZ_HANGOUT); task.setBizId(88L); task.setSourceType("report"); task.setSubmitterId(9L);
        task.setSnapshotJson("{\"bizType\":\"HANGOUT\",\"bizId\":88,\"content\":{\"memberId\":4,\"conversationId\":500,\"ownerNickname\":\"PRIVATE_OWNER\",\"coverUrl\":\"PRIVATE_COVER\"}}");
        IImService im = mock(IImService.class);
        ObjectProvider<IImService> imProvider = mock(ObjectProvider.class);
        when(imProvider.getObject()).thenReturn(im);
        ReflectionTestUtils.setField(service, "imServiceProvider", imProvider);
        ReflectionTestUtils.setField(service, "roamHangoutMapper", mock(RoamHangoutMapper.class));
        ReflectionTestUtils.setField(service, "imConversationMemberMapper", mock(com.chengyinhub.business.mapper.ImConversationMemberMapper.class));
        ObjectProvider<IRoamHangoutService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(mock(IRoamHangoutService.class));
        ReflectionTestUtils.setField(service, "roamHangoutServiceProvider", provider);
        when(auditTaskMapper.selectAuditTaskById(40L)).thenReturn(task);
        when(auditTaskMapper.updateAuditTaskReviewCas(eq(40L), eq(0), eq(2), eq(100L), eq(1), any())).thenReturn(1);
        assertEquals(1, service.review(40L, 100L, review(2, "确认违规")));
        verify(im).sendSystem(eq(4L), eq(ImMessage.MSG_CARD), eq("你的组局已关闭"), org.mockito.ArgumentMatchers.argThat(json ->
                json.contains("确认违规") && !json.contains("PRIVATE_") && !json.contains("conversationId")));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "{\"bizType\":\"HANGOUT\",\"bizId\":88,\"reason\":\"legacy\"}",
            "{\"bizType\":\"HANGOUT\",\"bizId\":88,\"content\":{\"memberId\":4}}",
            "{broken",
            "{\"bizType\":\"HANGOUT\",\"bizId\":88,\"content\":{\"memberId\":\"4\",\"conversationId\":500.5}}"
    })
    void deletedHangoutFallsBackToLaterReportForSameTaskAndBusiness(String initialSnapshot) {
        AuditTask task = task(40L, AuditTask.STATUS_PENDING, 1);
        task.setBizType(AuditTask.BIZ_HANGOUT); task.setBizId(88L); task.setSourceType("report"); task.setSubmitterId(9L);
        task.setSnapshotJson(initialSnapshot);
        AuditRecord later = reportRecord(8L);
        later.setTaskId(40L);
        later.setSnapshotJson("{\"bizType\":\"HANGOUT\",\"bizId\":88,\"content\":{\"memberId\":4,\"conversationId\":500}}");
        AuditRecord wrongTask = reportRecord(8L); wrongTask.setTaskId(41L);
        wrongTask.setSnapshotJson("{\"bizType\":\"HANGOUT\",\"bizId\":88,\"content\":{\"memberId\":44,\"conversationId\":600}}");
        AuditRecord wrongBusiness = reportRecord(8L); wrongBusiness.setTaskId(40L);
        wrongBusiness.setSnapshotJson("{\"bizType\":\"ACTIVITY\",\"bizId\":88,\"content\":{\"memberId\":44,\"conversationId\":600}}");
        AuditRecord wrongId = reportRecord(8L); wrongId.setTaskId(40L);
        wrongId.setSnapshotJson("{\"bizType\":\"HANGOUT\",\"bizId\":89,\"content\":{\"memberId\":44,\"conversationId\":600}}");
        AuditRecord decision = reportRecord(8L); decision.setTaskId(40L); decision.setAction("REJECT");
        decision.setSnapshotJson(wrongTask.getSnapshotJson());
        java.util.List<AuditRecord> unrelated = java.util.Arrays.asList(wrongTask, wrongBusiness, wrongId, decision);
        java.util.List<AuditRecord> reports = new java.util.ArrayList<>(unrelated);
        reports.add(later);
        when(auditRecordMapper.selectReportsForUpdate(40L)).thenReturn(reports);
        IImService im = mock(IImService.class);
        ObjectProvider<IImService> imProvider = mock(ObjectProvider.class);
        when(imProvider.getObject()).thenReturn(im);
        ReflectionTestUtils.setField(service, "imServiceProvider", imProvider);
        ReflectionTestUtils.setField(service, "roamHangoutMapper", mock(RoamHangoutMapper.class));
        com.chengyinhub.business.mapper.ImConversationMemberMapper members =
                mock(com.chengyinhub.business.mapper.ImConversationMemberMapper.class);
        com.chengyinhub.business.domain.ImConversationMember historical =
                new com.chengyinhub.business.domain.ImConversationMember();
        historical.setMemberId(5L); historical.setDeleted(1);
        when(members.selectByConversationForUpdate(500L)).thenReturn(Collections.singletonList(historical));
        ReflectionTestUtils.setField(service, "imConversationMemberMapper", members);
        ObjectProvider<IRoamHangoutService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(mock(IRoamHangoutService.class));
        ReflectionTestUtils.setField(service, "roamHangoutServiceProvider", provider);
        when(auditTaskMapper.selectAuditTaskById(40L)).thenReturn(task);
        when(auditTaskMapper.updateAuditTaskReviewCas(eq(40L), eq(0), eq(2), eq(100L), eq(1), any())).thenReturn(1);

        assertEquals(1, service.review(40L, 100L, review(2, "确认违规")));
        verify(im).sendSystem(eq(9L), eq(ImMessage.MSG_CARD), eq("举报已处理"), any());
        verify(im).sendSystem(eq(8L), eq(ImMessage.MSG_CARD), eq("举报已处理"), any());
        verify(im).sendSystem(eq(4L), eq(ImMessage.MSG_CARD), eq("你的组局已关闭"), any());
        verify(im).sendSystem(eq(5L), eq(ImMessage.MSG_CARD), eq("你参与的组局已关闭"), any());
        org.mockito.Mockito.verifyNoMoreInteractions(im);

        // 没有可靠证据时只通知举报人；不能使用其他任务/业务/决定快照猜局主和群。
        org.mockito.Mockito.clearInvocations(im, members);
        task.setSnapshotJson("{\"bizType\":\"HANGOUT\",\"bizId\":88,\"content\":{\"memberId\":0,\"conversationId\":9223372036854775808}}");
        when(auditRecordMapper.selectReportsForUpdate(40L)).thenReturn(unrelated);
        assertEquals(1, service.review(40L, 100L, review(2, "确认违规")));
        verify(im).sendSystem(eq(9L), eq(ImMessage.MSG_CARD), eq("举报已处理"), any());
        verify(im).sendSystem(eq(8L), eq(ImMessage.MSG_CARD), eq("举报已处理"), any());
        org.mockito.Mockito.verifyNoMoreInteractions(im);
        org.mockito.Mockito.verifyNoInteractions(members);
    }

    @Test
    void revertConflictReturnsServerStateAndStableRequestId() {
        IAuditTaskService mockService = mock(IAuditTaskService.class);
        AuditWorkbenchController controller = new TestableAuditWorkbenchController(100L);
        ReflectionTestUtils.setField(controller, "auditTaskService", mockService);
        AuditRevertDTO request = new AuditRevertDTO();
        request.setTaskId(10L);
        request.setExpectedVersion(2);
        request.setIdempotencyKey("revert:10:2");
        request.setReason("误操作");
        AuditTask current = task(request.getTaskId(), AuditTask.STATUS_REJECTED, 3);
        when(mockService.revert(eq(request.getTaskId()), eq(100L), eq(false), eq(request)))
                .thenThrow(new ServiceException("审核状态已变化，请刷新后重试", HttpStatus.CONFLICT));
        when(mockService.selectAuditTaskById(request.getTaskId())).thenReturn(current);

        AjaxResult result = controller.revert(request);

        assertEquals(HttpStatus.CONFLICT, result.get(AjaxResult.CODE_TAG));
        assertEquals(request.getIdempotencyKey(), result.get("requestId"));
        assertEquals(current.getStatus(), result.get("currentStatus"));
        assertEquals(current.getVersion(), result.get("currentVersion"));
    }

    @Test
    void detailReportCountDeduplicatesByReporter() {
        IAuditTaskService mockService = mock(IAuditTaskService.class);
        AuditWorkbenchController controller = new TestableAuditWorkbenchController(100L);
        ReflectionTestUtils.setField(controller, "auditTaskService", mockService);
        AuditTask task = task(30L, AuditTask.STATUS_PENDING, 0);
        when(mockService.selectAuditTaskById(30L)).thenReturn(task);
        // 同一举报人(200L)刷 3 条 + 另一人(201L)1 条 + 一条非 REPORT 处置记录 → reportCount 应=2(去重),不是 4
        when(mockService.listAuditRecords(30L)).thenReturn(java.util.Arrays.asList(
                reportRecord(200L), reportRecord(200L), reportRecord(200L),
                reportRecord(201L), actionRecord("REJECT", 100L)));

        AjaxResult result = controller.detail(30L);

        assertEquals(2, result.get("reportCount"), "举报热度按不同举报人去重,单人刷单不灌水");
        assertEquals(task, result.get("task"));
    }

    @Test
    void detailReturnsErrorWhenTaskMissing() {
        IAuditTaskService mockService = mock(IAuditTaskService.class);
        AuditWorkbenchController controller = new TestableAuditWorkbenchController(100L);
        ReflectionTestUtils.setField(controller, "auditTaskService", mockService);
        when(mockService.selectAuditTaskById(99L)).thenReturn(null);

        AjaxResult result = controller.detail(99L);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(mockService, never()).listAuditRecords(any());
    }

    @Test
    void projectSubmissionRequiresObjectScopeBeforeWriting() {
        ITopicCommercialReviewService reviewService = mock(ITopicCommercialReviewService.class);
        ITopicOperationsScopeAuthorizer scope = mock(ITopicOperationsScopeAuthorizer.class);
        AuditWorkbenchController controller = new TestableAuditWorkbenchController(100L);
        ReflectionTestUtils.setField(controller, "topicCommercialReviewService", reviewService);
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scope);
        when(scope.canViewTopic(9L)).thenReturn(true);
        when(scope.canEditTopic(9L)).thenReturn(false);

        AjaxResult result = controller.submitProjectReview(9L, submit("RECRUITING"));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(reviewService, never()).submitProjectReview(any(), any(), any());
        verify(scope).canEditTopic(9L);
    }

    @Test
    void scopedProjectSubmissionUsesAuthenticatedOperatorAndTypedTarget() {
        ITopicCommercialReviewService reviewService = mock(ITopicCommercialReviewService.class);
        ITopicOperationsScopeAuthorizer scope = mock(ITopicOperationsScopeAuthorizer.class);
        AuditWorkbenchController controller = new TestableAuditWorkbenchController(100L);
        ReflectionTestUtils.setField(controller, "topicCommercialReviewService", reviewService);
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scope);
        when(scope.canEditTopic(9L)).thenReturn(true);
        when(reviewService.submitProjectReview(9L, 100L, TopicLifecycleState.PRICING)).thenReturn(77L);

        AjaxResult result = controller.submitProjectReview(9L, submit("PRICING"));

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(77L, result.get("taskId"));
    }

    @Test
    void pureUgcSaleSubmissionFinalizesAndReturnsSelling() {
        ITopicCommercialReviewService reviewService = mock(ITopicCommercialReviewService.class);
        ITopicSaleFinalizationService finalizationService = mock(ITopicSaleFinalizationService.class);
        ITopicOperationsScopeAuthorizer scope = mock(ITopicOperationsScopeAuthorizer.class);
        AuditWorkbenchController controller = new TestableAuditWorkbenchController(100L);
        ReflectionTestUtils.setField(controller, "topicCommercialReviewService", reviewService);
        ReflectionTestUtils.setField(controller, "topicSaleFinalizationService", finalizationService);
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scope);
        when(scope.canEditTopic(9L)).thenReturn(true);
        when(reviewService.submitSaleReview(9L, 100L)).thenReturn(null);

        AjaxResult result = controller.submitSaleReview(9L);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals("SELLING", result.get("lifecycle"));
        verify(finalizationService).finalizeForSale(9L);
    }

    @Test
    void commercialSaleSubmissionOnlyCreatesTaskAndDoesNotFinalizeEarly() {
        ITopicCommercialReviewService reviewService = mock(ITopicCommercialReviewService.class);
        ITopicSaleFinalizationService finalizationService = mock(ITopicSaleFinalizationService.class);
        ITopicOperationsScopeAuthorizer scope = mock(ITopicOperationsScopeAuthorizer.class);
        AuditWorkbenchController controller = new TestableAuditWorkbenchController(100L);
        ReflectionTestUtils.setField(controller, "topicCommercialReviewService", reviewService);
        ReflectionTestUtils.setField(controller, "topicSaleFinalizationService", finalizationService);
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scope);
        when(scope.canEditTopic(9L)).thenReturn(true);
        when(reviewService.submitSaleReview(9L, 100L)).thenReturn(77L);

        AjaxResult result = controller.submitSaleReview(9L);

        assertEquals(77L, result.get("taskId"));
        verify(finalizationService, never()).finalizeForSale(any());
    }

    @Test
    void readOnlyDelegationCannotSubmitSaleOrReachSelling() {
        ITopicCommercialReviewService reviewService = mock(ITopicCommercialReviewService.class);
        ITopicSaleFinalizationService finalizationService = mock(ITopicSaleFinalizationService.class);
        ITopicOperationsScopeAuthorizer scope = mock(ITopicOperationsScopeAuthorizer.class);
        AuditWorkbenchController controller = new TestableAuditWorkbenchController(100L);
        ReflectionTestUtils.setField(controller, "topicCommercialReviewService", reviewService);
        ReflectionTestUtils.setField(controller, "topicSaleFinalizationService", finalizationService);
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scope);
        when(scope.canViewTopic(9L)).thenReturn(true);
        when(scope.canEditTopic(9L)).thenReturn(false);

        AjaxResult result = controller.submitSaleReview(9L);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(reviewService, never()).submitSaleReview(any(), any());
        verify(finalizationService, never()).finalizeForSale(any());
    }

    @Test
    void readOnlyDelegationCannotSaveSaleCanonicalDraft() {
        ITopicSaleCanonicalDraftService draftService = mock(ITopicSaleCanonicalDraftService.class);
        ITopicOperationsScopeAuthorizer scope = mock(ITopicOperationsScopeAuthorizer.class);
        AuditWorkbenchController controller = new TestableAuditWorkbenchController(100L);
        ReflectionTestUtils.setField(controller, "topicSaleCanonicalDraftService", draftService);
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scope);
        when(scope.canViewTopic(9L)).thenReturn(true);
        when(scope.canEditTopic(9L)).thenReturn(false);

        AjaxResult result = controller.saveSaleCanonicalDraft(9L, new TopicSaleCanonicalDraftDTO());

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(draftService, never()).save(any(), any(), any());
    }

    private static AuditRecord reportRecord(Long operatorId) {
        return actionRecord("REPORT", operatorId);
    }

    private static AuditRecord actionRecord(String action, Long operatorId) {
        AuditRecord r = new AuditRecord();
        r.setAction(action);
        r.setOperatorId(operatorId);
        return r;
    }

    private static AuditTask task(Long id, Integer status, Integer version) {
        AuditTask task = new AuditTask();
        task.setId(id);
        task.setStatus(status);
        task.setVersion(version);
        task.setBizType("topic");
        task.setBizId(20L);
        return task;
    }

    private static AuditReviewDTO review(Integer status, String reason) {
        AuditReviewDTO request = new AuditReviewDTO();
        request.setStatus(status);
        request.setReasonText(reason);
        request.setReasonCode("manual");
        return request;
    }

    private static AuditReviewDTO reviewWithId(Long taskId, Integer status, String reason) {
        AuditReviewDTO request = review(status, reason);
        request.setTaskId(taskId);
        return request;
    }

    private static TopicCommercialReviewSubmitDTO submit(String target) {
        TopicCommercialReviewSubmitDTO dto = new TopicCommercialReviewSubmitDTO();
        dto.setRequestedTarget(target);
        return dto;
    }

    private static class TestableAuditWorkbenchController extends AuditWorkbenchController {
        private final Long userId;

        private TestableAuditWorkbenchController(Long userId) {
            this.userId = userId;
        }

        @Override
        public Long getUserId() {
            return userId;
        }
    }
}
