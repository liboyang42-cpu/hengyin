package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.MerchantReview;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.service.IMerchantReviewService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditTaskMerchantReviewModerationTest {
    private AuditTaskMapper taskMapper;
    private AuditRecordMapper recordMapper;
    private IMerchantReviewService reviewService;
    private AuditTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(AuditTaskMapper.class);
        recordMapper = mock(AuditRecordMapper.class);
        reviewService = mock(IMerchantReviewService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<IMerchantReviewService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(reviewService);
        service = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(service, "auditTaskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "auditRecordMapper", recordMapper);
        com.chengyinhub.business.mapper.BusinessAuditEventMapper auditMapper =
                mock(com.chengyinhub.business.mapper.BusinessAuditEventMapper.class);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", auditMapper);
        ReflectionTestUtils.setField(service, "merchantReviewServiceProvider", provider);
        when(recordMapper.insertAuditRecord(any())).thenReturn(1);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);
    }

    @Test
    void rejectConfirmsViolationAndHidesReviewWhileApproveRestoresIt() {
        AuditTask task = task(71L, 505L, 2);
        when(taskMapper.selectAuditTaskById(71L)).thenReturn(task);
        when(taskMapper.updateAuditTaskReviewCas(any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(reviewService.moderateFromAudit(505L, MerchantReview.STATUS_VISIBLE,
                MerchantReview.STATUS_HIDDEN, "确认违规"))
                .thenReturn(1);

        assertEquals(1, service.review(71L, 900L, decision(AuditTask.STATUS_REJECTED, "确认违规")));
        verify(reviewService).moderateFromAudit(505L, MerchantReview.STATUS_VISIBLE,
                MerchantReview.STATUS_HIDDEN, "确认违规");

        AuditTask restore = task(72L, 505L, 0);
        when(taskMapper.selectAuditTaskById(72L)).thenReturn(restore);
        when(reviewService.moderateFromAudit(505L, MerchantReview.STATUS_VISIBLE,
                MerchantReview.STATUS_VISIBLE, "举报无效"))
                .thenReturn(1);
        assertEquals(1, service.review(72L, 900L, decision(AuditTask.STATUS_APPROVED, "举报无效")));
        verify(reviewService).moderateFromAudit(505L, MerchantReview.STATUS_VISIBLE,
                MerchantReview.STATUS_VISIBLE, "举报无效");
    }

    @Test
    void moderationFailurePropagatesSoAuditDecisionCannotSilentlyDiverge() {
        AuditTask task = task(71L, 505L, 2);
        when(taskMapper.selectAuditTaskById(71L)).thenReturn(task);
        when(taskMapper.updateAuditTaskReviewCas(any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(reviewService.moderateFromAudit(505L, MerchantReview.STATUS_VISIBLE,
                MerchantReview.STATUS_HIDDEN, "确认违规"))
                .thenThrow(new ServiceException("CAS failed"));

        assertThrows(ServiceException.class,
                () -> service.review(71L, 900L, decision(AuditTask.STATUS_REJECTED, "确认违规")));
    }

    @Test
    void originalSubmissionReviewUsesPendingStateAcrossBothDecisions() {
        AuditTask approve = task(73L, 506L, 0);
        approve.setSourceType("auto_gate");
        when(taskMapper.selectAuditTaskById(73L)).thenReturn(approve);
        when(taskMapper.updateAuditTaskReviewCas(any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(reviewService.moderateFromAudit(506L, MerchantReview.STATUS_PENDING_REVIEW,
                MerchantReview.STATUS_VISIBLE, "通过复核")).thenReturn(1);

        assertEquals(1, service.review(73L, 900L, decision(AuditTask.STATUS_APPROVED, "通过复核")));
        verify(reviewService).moderateFromAudit(506L, MerchantReview.STATUS_PENDING_REVIEW,
                MerchantReview.STATUS_VISIBLE, "通过复核");

        AuditTask reject = task(74L, 507L, 0);
        reject.setSourceType("auto_gate");
        when(taskMapper.selectAuditTaskById(74L)).thenReturn(reject);
        when(reviewService.moderateFromAudit(507L, MerchantReview.STATUS_PENDING_REVIEW,
                MerchantReview.STATUS_HIDDEN, "拒绝复核")).thenReturn(1);

        assertEquals(1, service.review(74L, 900L, decision(AuditTask.STATUS_REJECTED, "拒绝复核")));
        verify(reviewService).moderateFromAudit(507L, MerchantReview.STATUS_PENDING_REVIEW,
                MerchantReview.STATUS_HIDDEN, "拒绝复核");
    }

    private AuditTask task(Long taskId, Long reviewId, int version) {
        AuditTask task = new AuditTask();
        task.setId(taskId);
        task.setBizType(AuditTask.BIZ_MERCHANT_REVIEW);
        task.setBizId(reviewId);
        task.setSourceType("report");
        task.setStatus(AuditTask.STATUS_PENDING);
        task.setVersion(version);
        task.setSnapshotJson("{\"bizType\":\"MERCHANT_REVIEW\"}");
        return task;
    }

    private AuditReviewDTO decision(Integer status, String reason) {
        AuditReviewDTO request = new AuditReviewDTO();
        request.setStatus(status);
        request.setReasonText(reason);
        return request;
    }
}
