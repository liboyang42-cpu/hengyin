package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.service.IClubPostService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F0529:升级只推进处理层级与案件版本,不裁决、不动内容;旧层级的迟到提交被拒。
 */
class AuditEscalationLevelTest {

    private AuditTaskMapper tasks;
    private BusinessAuditEventMapper events;
    private IClubPostService clubPosts;
    private AuditTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        tasks = mock(AuditTaskMapper.class);
        AuditRecordMapper records = mock(AuditRecordMapper.class);
        events = mock(BusinessAuditEventMapper.class);
        clubPosts = mock(IClubPostService.class);
        service = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(service, "auditTaskMapper", tasks);
        ReflectionTestUtils.setField(service, "auditRecordMapper", records);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", events);
        ReflectionTestUtils.setField(service, "clubPostService", clubPosts);
        when(records.insertAuditRecord(any())).thenReturn(1);
        when(events.insertBusinessAuditEvent(any())).thenReturn(1);
        when(tasks.updateAuditTaskReviewCas(anyLong(), any(), any(), any(), any(), any())).thenReturn(1);
        when(clubPosts.deleteClubPostById(900L)).thenReturn(1);
    }

    private static AuditTask task(Integer status, int version) {
        AuditTask t = new AuditTask();
        t.setId(1L);
        t.setBizType(AuditTask.BIZ_CLUB_POST);
        t.setBizId(900L);
        t.setStatus(status);
        t.setVersion(version);
        t.setSnapshotJson("{\"content\":\"原案快照\"}");
        return t;
    }

    private static AuditReviewDTO request(Integer status, Integer expectedVersion) {
        AuditReviewDTO d = new AuditReviewDTO();
        d.setStatus(status);
        d.setExpectedVersion(expectedVersion);
        d.setReasonText("违规广告");
        return d;
    }

    @Test
    void escalationAdvancesLevelWithoutAdjudicatingOrTouchingContent() {
        when(tasks.selectAuditTaskById(1L)).thenReturn(task(AuditTask.STATUS_PENDING, 0));

        assertEquals(1, service.review(1L, 66L, request(AuditTask.STATUS_ESCALATED, 0)));

        verify(tasks).updateAuditTaskReviewCas(eq(1L), eq(AuditTask.STATUS_PENDING), eq(AuditTask.STATUS_ESCALATED),
                eq(66L), eq(0), any());
        verify(clubPosts, never()).deleteClubPostById(anyLong());
        ArgumentCaptor<BusinessAuditEvent> captor = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(events).insertBusinessAuditEvent(captor.capture());
        assertEquals("AUDIT_DECISION_ESCALATE", captor.getValue().getAction());
        assertEquals("{\"status\":3,\"version\":1}", captor.getValue().getAfterJson());
        assertFalse(captor.getValue().getAfterJson().contains("moderation"), "升级不是裁决,没有对象投影");
    }

    @Test
    void lateOldLevelSubmissionAfterEscalationIsRejected() {
        when(tasks.selectAuditTaskById(1L)).thenReturn(task(AuditTask.STATUS_ESCALATED, 1));

        ServiceException e = assertThrows(ServiceException.class,
                () -> service.review(1L, 66L, request(AuditTask.STATUS_REJECTED, 0)));

        assertEquals(Integer.valueOf(HttpStatus.CONFLICT), e.getCode());
        assertEquals(AuditTaskServiceImpl.ERROR_REVIEW_STALE_LEVEL, e.getDetailMessage());
        assertTrue(e.getMessage().contains("已升级"));
        verify(tasks, never()).updateAuditTaskReviewCas(anyLong(), any(), any(), any(), any(), any());
        verify(clubPosts, never()).deleteClubPostById(anyLong());
    }

    @Test
    void escalatedCaseRequiresCurrentVersion() {
        when(tasks.selectAuditTaskById(1L)).thenReturn(task(AuditTask.STATUS_ESCALATED, 1));

        ServiceException e = assertThrows(ServiceException.class,
                () -> service.review(1L, 66L, request(AuditTask.STATUS_APPROVED, null)));

        assertEquals(AuditTaskServiceImpl.ERROR_REVIEW_STALE_LEVEL, e.getDetailMessage());
        verify(tasks, never()).updateAuditTaskReviewCas(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void newLevelDecisionOnCurrentVersionStillTracksProjection() {
        when(tasks.selectAuditTaskById(1L)).thenReturn(task(AuditTask.STATUS_ESCALATED, 1));

        assertEquals(1, service.review(1L, 77L, request(AuditTask.STATUS_REJECTED, 1)));

        verify(tasks).updateAuditTaskReviewCas(eq(1L), eq(AuditTask.STATUS_ESCALATED), eq(AuditTask.STATUS_REJECTED),
                eq(77L), eq(1), any());
        verify(clubPosts).deleteClubPostById(900L);
        ArgumentCaptor<BusinessAuditEvent> captor = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(events).insertBusinessAuditEvent(captor.capture());
        assertTrue(captor.getValue().getAfterJson().contains("\"outcome\":\"APPLIED\""));
    }

    @Test
    void staleVersionOnPendingCaseIsAlsoRejected() {
        when(tasks.selectAuditTaskById(1L)).thenReturn(task(AuditTask.STATUS_PENDING, 2));

        assertThrows(ServiceException.class,
                () -> service.review(1L, 66L, request(AuditTask.STATUS_REJECTED, 1)));
        verify(tasks, never()).updateAuditTaskReviewCas(anyLong(), any(), any(), any(), any(), any());
    }
}
