package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.service.GuardArrivalEvidenceReader;
import com.chengyinhub.business.service.support.GuardAppealReviewerScope;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Service behavior only. This is not evidence of runtime locking or MySQL rollback. */
class GuardAppealReviewRecoveryTest {
    private AuditTaskServiceImpl service;
    private AuditTaskMapper tasks;
    private BusinessAuditEventMapper events;
    private GuardAppealReviewerScope scope;
    private GuardArrivalEvidenceReader runtime;

    @BeforeEach void prepare() {
        service = new AuditTaskServiceImpl(); tasks = mock(AuditTaskMapper.class);
        events = mock(BusinessAuditEventMapper.class); scope = mock(GuardAppealReviewerScope.class);
        runtime = mock(GuardArrivalEvidenceReader.class);
        ReflectionTestUtils.setField(service, "auditTaskMapper", tasks);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", events);
        ReflectionTestUtils.setField(service, "guardAppealReviewerScope", scope);
        ReflectionTestUtils.setField(service, "guardArrivalEvidenceReader", runtime);
        AuditTask task = new AuditTask(); task.setId(71L); task.setBizId(91L); task.setSubmitterId(10L);
        task.setBizType("play_arrive_guard"); task.setSourceType("appeal"); task.setVersion(1);
        task.setStatus(AuditTask.STATUS_APPROVED);
        task.setSnapshotJson("{\"guardOrigin\":{\"memberId\":\"10\",\"guardLogId\":\"91\",\"topicId\":\"77\",\"arrivalRequestId\":\"arrive-1\"}}");
        when(tasks.selectAuditTaskById(71L)).thenReturn(task);
        com.chengyinhub.business.mapper.AuditAppealMapper appeals = mock(com.chengyinhub.business.mapper.AuditAppealMapper.class);
        com.chengyinhub.business.mapper.PlayArriveGuardLogMapper logs = mock(com.chengyinhub.business.mapper.PlayArriveGuardLogMapper.class);
        ReflectionTestUtils.setField(service, "auditAppealMapper", appeals);
        ReflectionTestUtils.setField(service, "guardAppealLogMapper", logs);
        com.chengyinhub.business.domain.AuditAppeal appeal = new com.chengyinhub.business.domain.AuditAppeal(); appeal.setTaskId(71L);
        com.chengyinhub.business.domain.PlayArriveGuardLog log = new com.chengyinhub.business.domain.PlayArriveGuardLog(); log.setTopicId(77L);
        when(appeals.selectByBizMember("play_arrive_guard", 91L, 10L)).thenReturn(appeal);
        when(logs.selectByIdAndMemberId(91L, 10L)).thenReturn(log);
        when(scope.canReview(7L, 77L)).thenReturn(true);
        BusinessAuditEvent event = new BusinessAuditEvent(); event.setDomainType("PLATFORM"); event.setDomainId(71L);
        event.setTargetType("AUDIT_TASK"); event.setTargetId(71L); event.setActorType("SYS_USER"); event.setActorMemberId(7L);
        event.setAction("AUDIT_DECISION_APPROVE"); event.setRequestId("review-1"); event.setReason("现场重核");
        event.setBeforeJson("{\"version\":0}"); event.setAfterJson("{\"status\":1,\"itemProblemsApplied\":false}");
        when(events.selectByTarget("AUDIT_TASK", 71L)).thenReturn(List.of(event));
    }
    private AuditReviewDTO command() {
        AuditReviewDTO dto = new AuditReviewDTO(); dto.setStatus(AuditTask.STATUS_APPROVED);
        dto.setExpectedVersion(0); dto.setRequestId("review-1"); dto.setReasonText("现场重核"); return dto;
    }
    @Test void committedReplayAndReceiptDoNotRecheckOrMutateEndedRuntime() {
        assertEquals(1, service.review(71L, 7L, command()));
        assertEquals("FOUND", service.guardReviewReceipt(71L, 7L, "review-1").get("state"));
        verifyNoInteractions(runtime);
        verify(tasks, never()).updateAuditTaskReviewCas(any(), any(), any(), any(), any(), any());
        verify(events, never()).insertBusinessAuditEvent(any());
    }
    @Test void changedIntentAndDifferentReviewerCannotReplay() {
        AuditReviewDTO changed = command(); changed.setReasonText("直接放行");
        assertThrows(ServiceException.class, () -> service.review(71L, 7L, changed));
        when(scope.canReview(8L, 77L)).thenReturn(true);
        assertThrows(ServiceException.class, () -> service.review(71L, 8L, command()));
        assertEquals("NOT_FOUND", service.guardReviewReceipt(71L, 8L, "review-1").get("state"));
        verifyNoInteractions(runtime);
    }
    @Test void absentScopeRejectsBeforeReceiptOrRuntimeAccess() {
        when(scope.canReview(7L, 77L)).thenReturn(false);
        assertThrows(ServiceException.class, () -> service.review(71L, 7L, command()));
        assertThrows(ServiceException.class, () -> service.guardReviewReceipt(71L, 7L, "review-1"));
        verifyNoInteractions(events, runtime);
    }
}
