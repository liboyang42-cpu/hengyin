package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditRecord;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.dto.AuditRevertDTO;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.domain.vo.AuditRevertResultVO;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.service.IClubPostService;
import com.chengyinhub.business.service.IMerchantReviewService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Arrays;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditGovernanceContractTest {
    private static final long TASK_ID = 71L;
    private static final long ACTOR_ID = 900L;
    private static final int CURRENT_VERSION = 4;
    private static final int NEXT_VERSION = CURRENT_VERSION + 1;
    private static final String REVERT_REQUEST_ID = "audit-revert-71-v4";
    private static final String REVERT_REASON = "审批员误点";
    private static final String REJECT_REASON = "内容违规";
    private static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");

    private AuditTaskMapper tasks;
    private AuditRecordMapper records;
    private BusinessAuditEventMapper audits;
    private IClubPostService clubPosts;
    private IMerchantReviewService merchantReviews;
    private ObjectProvider<IMerchantReviewService> merchantReviewProvider;
    private AuditTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        tasks = mock(AuditTaskMapper.class);
        records = mock(AuditRecordMapper.class);
        audits = mock(BusinessAuditEventMapper.class);
        clubPosts = mock(IClubPostService.class);
        merchantReviews = mock(IMerchantReviewService.class);
        merchantReviewProvider = mock(ObjectProvider.class);
        service = new AuditTaskServiceImpl();
        ObjectProvider<com.chengyinhub.business.service.IImService> imProvider = mock(ObjectProvider.class);
        when(imProvider.getObject()).thenReturn(mock(com.chengyinhub.business.service.IImService.class));
        ReflectionTestUtils.setField(service, "imServiceProvider", imProvider);
        ReflectionTestUtils.setField(service, "auditTaskMapper", tasks);
        ReflectionTestUtils.setField(service, "auditRecordMapper", records);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", audits);
        ReflectionTestUtils.setField(service, "clubPostService", clubPosts);
        ReflectionTestUtils.setField(service, "merchantReviewServiceProvider", merchantReviewProvider);
        when(merchantReviewProvider.getObject()).thenReturn(merchantReviews);
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void legacyReasonStatesKeepNullAndEmptyDistinct() {
        AuditRecord missing = new AuditRecord();
        missing.setReasonText(null);
        AuditRecord empty = new AuditRecord();
        empty.setReasonText("");

        assertNull(missing.getReason());
        assertEquals(AuditRecord.REASON_NOT_RECORDED, missing.getReasonState());
        assertEquals(AuditRecord.REASON_RECORDED_EMPTY, empty.getReasonState());
    }

    @Test
    void rejectionCommandAcceptsReasonContractName() throws Exception {
        AuditReviewDTO decision = new ObjectMapper().readValue(
                "{\"reason\":\"" + REJECT_REASON + "\"}", AuditReviewDTO.class);

        assertEquals(REJECT_REASON, decision.getReasonText());
    }

    @Test
    void rejectingWithoutReasonStopsBeforeStateCas() {
        AuditReviewDTO decision = new AuditReviewDTO();
        decision.setStatus(AuditTask.STATUS_REJECTED);
        decision.setReasonText("   ");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.review(TASK_ID, ACTOR_ID, decision));

        assertEquals(AuditTaskServiceImpl.REJECT_REASON_REQUIRED, error.getMessage());
        verify(tasks, never()).updateAuditTaskReviewCas(any(), any(), any(), any(), any(), any());
        verify(records, never()).insertAuditRecord(any());
    }

    @Test
    void reviewCasNamesExpectedStatusAndWritesImmutableAudit() {
        AuditTask task = task(AuditTask.STATUS_PENDING, AuditTask.BIZ_CLUB_POST);
        AuditReviewDTO decision = new AuditReviewDTO();
        decision.setStatus(AuditTask.STATUS_REJECTED);
        decision.setReasonText("  内容违规  ");
        when(tasks.selectAuditTaskById(TASK_ID)).thenReturn(task);
        when(tasks.updateAuditTaskReviewCas(eq(TASK_ID), eq(AuditTask.STATUS_PENDING),
                eq(AuditTask.STATUS_REJECTED), eq(ACTOR_ID), eq(CURRENT_VERSION), any(Date.class))).thenReturn(1);
        when(records.insertAuditRecord(any(AuditRecord.class))).thenReturn(1);
        when(audits.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(1);

        int rows = service.review(TASK_ID, ACTOR_ID, decision);

        assertEquals(1, rows);
        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(records).insertAuditRecord(record.capture());
        assertEquals("内容违规", record.getValue().getReasonText());
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(audits).insertBusinessAuditEvent(audit.capture());
        assertEquals(BusinessAuditEvent.ACTOR_TYPE_SYS_USER, audit.getValue().getActorType());
        assertTrue(audit.getValue().getRequestId() != null && !audit.getValue().getRequestId().isEmpty());
    }

    @Test
    void successfulReviewReplayReturnsOriginalReceiptWithoutSecondCas() {
        AuditTask task = task(AuditTask.STATUS_REJECTED, AuditTask.BIZ_MERCHANT_REVIEW);
        task.setVersion(NEXT_VERSION);
        AuditReviewDTO decision = new AuditReviewDTO();
        decision.setStatus(AuditTask.STATUS_REJECTED);
        decision.setReasonText(REJECT_REASON);
        decision.setRequestId("review:71:4:reject");
        BusinessAuditEvent replay = new BusinessAuditEvent();
        replay.setActorMemberId(ACTOR_ID);
        replay.setReason(REJECT_REASON);
        replay.setBeforeJson("{\"status\":0,\"version\":" + CURRENT_VERSION + "}");
        replay.setAfterJson("{\"status\":2,\"version\":" + NEXT_VERSION + "}");
        when(tasks.selectAuditTaskById(TASK_ID)).thenReturn(task);
        when(audits.selectByDomainActionRequestForUpdate(
                "PLATFORM", TASK_ID, "AUDIT_DECISION_REJECT", decision.getRequestId())).thenReturn(replay);

        int rows = service.review(TASK_ID, ACTOR_ID, decision);

        assertEquals(1, rows);
        verify(tasks, never()).updateAuditTaskReviewCas(any(), any(), any(), any(), any(), any());
        verify(records, never()).insertAuditRecord(any());
    }

    @Test
    void concurrentReviewLoserReadsWinnerReceiptAfterCasMiss() {
        AuditTask task = task(AuditTask.STATUS_PENDING, AuditTask.BIZ_MERCHANT_REVIEW);
        AuditReviewDTO decision = new AuditReviewDTO();
        decision.setStatus(AuditTask.STATUS_REJECTED);
        decision.setReasonText(REJECT_REASON);
        decision.setRequestId("review:71:4:reject");
        BusinessAuditEvent winner = new BusinessAuditEvent();
        winner.setActorMemberId(ACTOR_ID);
        winner.setReason(REJECT_REASON);
        winner.setBeforeJson("{\"status\":0,\"version\":" + CURRENT_VERSION + "}");
        winner.setAfterJson("{\"status\":2,\"version\":" + NEXT_VERSION + "}");
        when(tasks.selectAuditTaskById(TASK_ID)).thenReturn(task);
        when(audits.selectByDomainActionRequestForUpdate(
                "PLATFORM", TASK_ID, "AUDIT_DECISION_REJECT", decision.getRequestId()))
                .thenReturn(null, winner);
        when(tasks.updateAuditTaskReviewCas(any(), any(), any(), any(), any(), any())).thenReturn(0);

        int rows = service.review(TASK_ID, ACTOR_ID, decision);

        assertEquals(1, rows);
        verify(records, never()).insertAuditRecord(any());
    }

    @Test
    void reviewRecordWriteFailureStopsBusinessSideEffectAndAudit() {
        AuditTask task = task(AuditTask.STATUS_PENDING, AuditTask.BIZ_CLUB_POST);
        AuditReviewDTO decision = new AuditReviewDTO();
        decision.setStatus(AuditTask.STATUS_REJECTED);
        decision.setReasonText(REJECT_REASON);
        when(tasks.selectAuditTaskById(TASK_ID)).thenReturn(task);
        when(tasks.updateAuditTaskReviewCas(eq(TASK_ID), eq(AuditTask.STATUS_PENDING),
                eq(AuditTask.STATUS_REJECTED), eq(ACTOR_ID), eq(CURRENT_VERSION), any(Date.class))).thenReturn(1);
        when(records.insertAuditRecord(any(AuditRecord.class))).thenReturn(0);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.review(TASK_ID, ACTOR_ID, decision));

        assertEquals(Integer.valueOf(HttpStatus.ERROR), error.getCode());
        verify(clubPosts, never()).deleteClubPostById(any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void revertUsesStatusAndVersionCasThenAppendsReverseAudit() {
        AuditTask task = task(AuditTask.STATUS_REJECTED, AuditTask.BIZ_MERCHANT_REVIEW);
        task.setSourceType("report");
        AuditRecord decision = decisionRecord(AuditRecord.ACTION_REJECT);
        when(tasks.selectAuditTaskById(TASK_ID)).thenReturn(task);
        when(records.selectAuditRecordList(any(AuditRecord.class))).thenReturn(Collections.singletonList(decision));
        when(tasks.revertAuditTaskDecisionCas(eq(TASK_ID), eq(AuditTask.STATUS_REJECTED),
                eq(CURRENT_VERSION), any(Date.class), any(Date.class))).thenReturn(1);
        when(records.insertAuditRecord(any(AuditRecord.class))).thenReturn(1);
        when(audits.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(1);
        when(merchantReviews.moderateFromAudit(eq(task.getBizId()), any(), any(), any())).thenReturn(1);

        AuditRevertResultVO result = service.revert(TASK_ID, ACTOR_ID, false, revertRequest());

        assertEquals(AuditTask.STATUS_PENDING, result.getStatus());
        assertEquals(Integer.valueOf(NEXT_VERSION), result.getVersion());
        verify(merchantReviews).moderateFromAudit(eq(task.getBizId()),
                eq(com.chengyinhub.business.domain.MerchantReview.STATUS_HIDDEN),
                eq(com.chengyinhub.business.domain.MerchantReview.STATUS_VISIBLE), eq((String) null));
        ArgumentCaptor<AuditRecord> reverse = ArgumentCaptor.forClass(AuditRecord.class);
        verify(records).insertAuditRecord(reverse.capture());
        assertEquals(AuditRecord.ACTION_REVERT, reverse.getValue().getAction());
        assertEquals(decision.getId(), reverse.getValue().getRevertedRecordId());
    }

    @Test
    void rejectedSubmissionRevertRestoresPendingReviewInsteadOfPublishingIt() {
        AuditTask task = task(AuditTask.STATUS_REJECTED, AuditTask.BIZ_MERCHANT_REVIEW);
        task.setSourceType("auto_gate");
        AuditRecord decision = decisionRecord(AuditRecord.ACTION_REJECT);
        when(tasks.selectAuditTaskById(TASK_ID)).thenReturn(task);
        when(records.selectAuditRecordList(any(AuditRecord.class))).thenReturn(Collections.singletonList(decision));
        when(tasks.revertAuditTaskDecisionCas(eq(TASK_ID), eq(AuditTask.STATUS_REJECTED),
                eq(CURRENT_VERSION), any(Date.class), any(Date.class))).thenReturn(1);
        when(records.insertAuditRecord(any(AuditRecord.class))).thenReturn(1);
        when(audits.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(1);
        when(merchantReviews.moderateFromAudit(task.getBizId(),
                com.chengyinhub.business.domain.MerchantReview.STATUS_HIDDEN,
                com.chengyinhub.business.domain.MerchantReview.STATUS_PENDING_REVIEW, null)).thenReturn(1);

        AuditRevertResultVO result = service.revert(TASK_ID, ACTOR_ID, false, revertRequest());

        assertEquals(AuditTask.STATUS_PENDING, result.getStatus());
        verify(merchantReviews).moderateFromAudit(task.getBizId(),
                com.chengyinhub.business.domain.MerchantReview.STATUS_HIDDEN,
                com.chengyinhub.business.domain.MerchantReview.STATUS_PENDING_REVIEW, null);
    }

    @Test
    void staleRevertCasCannotAppendOrReverseBusinessState() {
        AuditTask task = task(AuditTask.STATUS_REJECTED, AuditTask.BIZ_MERCHANT_REVIEW);
        when(tasks.selectAuditTaskById(TASK_ID)).thenReturn(task);
        when(records.selectAuditRecordList(any(AuditRecord.class)))
                .thenReturn(Collections.singletonList(decisionRecord(AuditRecord.ACTION_REJECT)));
        when(tasks.revertAuditTaskDecisionCas(any(), any(), any(), any(), any())).thenReturn(0);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.revert(TASK_ID, ACTOR_ID, false, revertRequest()));

        assertEquals(Integer.valueOf(HttpStatus.CONFLICT), error.getCode());
        verify(records, never()).insertAuditRecord(any());
        verify(audits, never()).insertBusinessAuditEvent(any());
        verify(clubPosts, never()).updateClubPost(any());
    }

    @Test
    void concurrentRevertLoserReadsWinnerReceiptAfterCasMiss() {
        AuditTask task = task(AuditTask.STATUS_REJECTED, AuditTask.BIZ_MERCHANT_REVIEW);
        AuditRecord decision = decisionRecord(AuditRecord.ACTION_REJECT);
        BusinessAuditEvent winner = new BusinessAuditEvent();
        winner.setActorMemberId(ACTOR_ID);
        winner.setReason(REVERT_REASON);
        winner.setRelationId(decision.getId());
        winner.setBeforeJson("{\"status\":2,\"version\":" + CURRENT_VERSION + "}");
        winner.setAfterJson("{\"status\":0,\"version\":" + NEXT_VERSION + "}");
        when(audits.selectByDomainActionRequestForUpdate(
                "PLATFORM", TASK_ID, "AUDIT_DECISION_REVERTED", REVERT_REQUEST_ID))
                .thenReturn(null, winner);
        when(tasks.selectAuditTaskById(TASK_ID)).thenReturn(task);
        when(records.selectAuditRecordList(any(AuditRecord.class))).thenReturn(Collections.singletonList(decision));
        when(tasks.revertAuditTaskDecisionCas(any(), any(), any(), any(), any())).thenReturn(0);

        AuditRevertResultVO result = service.revert(TASK_ID, ACTOR_ID, false, revertRequest());

        assertEquals(Integer.valueOf(NEXT_VERSION), result.getVersion());
        verify(records, never()).insertAuditRecord(any());
        verify(merchantReviews, never()).moderateFromAudit(any(), any(), any(), any());
    }

    @Test
    void targetStateCasFailureStopsReverseTimelineAndAudit() {
        AuditTask task = task(AuditTask.STATUS_REJECTED, AuditTask.BIZ_MERCHANT_REVIEW);
        when(tasks.selectAuditTaskById(TASK_ID)).thenReturn(task);
        when(records.selectAuditRecordList(any(AuditRecord.class)))
                .thenReturn(Collections.singletonList(decisionRecord(AuditRecord.ACTION_REJECT)));
        when(tasks.revertAuditTaskDecisionCas(any(), any(), any(), any(), any())).thenReturn(1);
        when(merchantReviews.moderateFromAudit(eq(task.getBizId()), any(), any(), any()))
                .thenThrow(new ServiceException("评价审核状态 CAS 失败", HttpStatus.CONFLICT));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.revert(TASK_ID, ACTOR_ID, false, revertRequest()));

        assertEquals(Integer.valueOf(HttpStatus.CONFLICT), error.getCode());
        verify(records, never()).insertAuditRecord(any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void anotherReviewerCannotReplayExistingRevertReceipt() {
        BusinessAuditEvent replay = new BusinessAuditEvent();
        replay.setActorMemberId(ACTOR_ID);
        replay.setRequestId(REVERT_REQUEST_ID);
        replay.setAfterJson("{\"status\":0,\"version\":" + NEXT_VERSION + "}");
        when(audits.selectByDomainActionRequestForUpdate(
                "PLATFORM", TASK_ID, "AUDIT_DECISION_REVERTED", REVERT_REQUEST_ID)).thenReturn(replay);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.revert(TASK_ID, ACTOR_ID + 1, false, revertRequest()));

        assertEquals(Integer.valueOf(HttpStatus.FORBIDDEN), error.getCode());
        // Reserved-type denial must happen before any receipt lookup, even for a replay.
        verify(tasks).selectAuditTaskById(TASK_ID);
        verify(records, never()).insertAuditRecord(any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void sameRevertKeyWithDifferentPayloadReturnsStableConflictCode() {
        BusinessAuditEvent replay = new BusinessAuditEvent();
        replay.setActorMemberId(ACTOR_ID);
        replay.setRequestId(REVERT_REQUEST_ID);
        replay.setReason("另一个原因");
        replay.setBeforeJson("{\"status\":2,\"version\":" + CURRENT_VERSION + "}");
        replay.setAfterJson("{\"status\":0,\"version\":" + NEXT_VERSION + "}");
        when(audits.selectByDomainActionRequestForUpdate(
                "PLATFORM", TASK_ID, "AUDIT_DECISION_REVERTED", REVERT_REQUEST_ID)).thenReturn(replay);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.revert(TASK_ID, ACTOR_ID, false, revertRequest()));

        assertEquals(Integer.valueOf(HttpStatus.CONFLICT), error.getCode());
        assertEquals(AuditTaskServiceImpl.ERROR_REVERT_KEY_REUSED, error.getDetailMessage());
    }

    @Test
    void applicantTimelineHidesReportsAndSnapshotsAndUsesForwardOrder() {
        AuditTask task = task(AuditTask.STATUS_REJECTED, AuditTask.BIZ_MERCHANT_REVIEW);
        task.setSnapshotJson("{\"internalRisk\":true}");
        AuditRecord submit = new AuditRecord();
        submit.setId(1L);
        submit.setTaskId(TASK_ID);
        submit.setAction(AuditRecord.ACTION_SUBMIT);
        submit.setOperatorId(88L);
        submit.setSnapshotJson("{\"private\":true}");
        submit.setCreateTime(Date.from(NOW.minusSeconds(120L)));
        AuditRecord report = new AuditRecord();
        report.setId(2L);
        report.setTaskId(TASK_ID);
        report.setAction(AuditRecord.ACTION_REPORT);
        report.setOperatorId(777L);
        report.setSnapshotJson("{\"reporter\":777}");
        report.setCreateTime(Date.from(NOW.minusSeconds(90L)));
        AuditRecord decision = decisionRecord(AuditRecord.ACTION_REJECT);
        when(tasks.selectAuditTaskById(TASK_ID)).thenReturn(task);
        when(records.selectAuditRecordList(any(AuditRecord.class)))
                .thenReturn(Arrays.asList(decision, report, submit));

        com.chengyinhub.business.domain.vo.AuditTaskDetailVO detail =
                service.detail(TASK_ID, task.getSubmitterId(), false);

        assertNull(detail.getTask().getSnapshotJson());
        assertEquals(2, detail.getTimeline().size());
        assertEquals(AuditRecord.ACTION_SUBMIT, detail.getTimeline().get(0).getAction());
        assertEquals(AuditRecord.ACTION_REJECT, detail.getTimeline().get(1).getAction());
        assertNull(detail.getTimeline().get(0).getSnapshotJson());
    }

    @Test
    void commercialApprovalRevertFailsClosedBeforeCas() {
        when(tasks.selectAuditTaskById(TASK_ID))
                .thenReturn(task(AuditTask.STATUS_APPROVED, AuditTask.BIZ_TOPIC_SALE_REVIEW));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.revert(TASK_ID, ACTOR_ID, true, revertRequest()));

        assertEquals(Integer.valueOf(HttpStatus.CONFLICT), error.getCode());
        verify(tasks, never()).revertAuditTaskDecisionCas(any(), any(), any(), any(), any());
    }

    private AuditTask task(Integer status, String bizType) {
        AuditTask task = new AuditTask();
        task.setId(TASK_ID);
        task.setBizType(bizType);
        task.setBizId(501L);
        task.setSubmitterId(88L);
        task.setStatus(status);
        task.setVersion(CURRENT_VERSION);
        task.setCurrentAssignee(ACTOR_ID);
        task.setUpdateTime(Date.from(NOW.minusSeconds(60L)));
        return task;
    }

    private AuditRecord decisionRecord(String action) {
        AuditRecord record = new AuditRecord();
        record.setId(801L);
        record.setTaskId(TASK_ID);
        record.setAction(action);
        record.setOperatorId(ACTOR_ID);
        record.setReasonText("原决定");
        record.setCreateTime(Date.from(NOW.minusSeconds(60L)));
        return record;
    }

    private AuditRevertDTO revertRequest() {
        AuditRevertDTO request = new AuditRevertDTO();
        request.setExpectedVersion(CURRENT_VERSION);
        request.setIdempotencyKey(REVERT_REQUEST_ID);
        request.setReason(REVERT_REASON);
        return request;
    }
}
