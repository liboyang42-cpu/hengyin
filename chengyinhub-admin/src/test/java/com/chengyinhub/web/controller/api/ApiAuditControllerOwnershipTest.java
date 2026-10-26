package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiAuditControllerOwnershipTest {
    private static final long VIEWER_ID = 41L;
    private static final long OTHER_SUBMITTER_ID = 42L;
    private static final long TASK_ID = 81L;

    @Test
    void applicantCannotReadAnotherSubmittersTimeline() {
        IAuditTaskService service = mock(IAuditTaskService.class);
        ApiAuditController controller = new TestableController(VIEWER_ID);
        ReflectionTestUtils.setField(controller, "auditTaskService", service);
        AuditTask task = new AuditTask();
        task.setId(TASK_ID);
        task.setSubmitterId(OTHER_SUBMITTER_ID);
        when(service.selectAuditTaskById(TASK_ID)).thenReturn(task);

        AjaxResult result = controller.detail(TASK_ID);

        assertEquals(HttpStatus.FORBIDDEN, result.get(AjaxResult.CODE_TAG));
        verify(service, never()).detail(TASK_ID, VIEWER_ID, false);
    }

    @Test
    void newRequestIdIsPassedToRealOverloadAndReceiptUsesLoggedInActor() {
        IAuditTaskService service = mock(IAuditTaskService.class);
        ApiAuditController controller = new TestableController(VIEWER_ID);
        ReflectionTestUtils.setField(controller, "auditTaskService", service);
        com.chengyinhub.business.domain.dto.AuditAppealDTO request = new com.chengyinhub.business.domain.dto.AuditAppealDTO();
        request.setBizType("ACTIVITY"); request.setBizId(90L); request.setReason("理由"); request.setRequestId("original");
        com.chengyinhub.business.domain.AuditAppeal appeal = new com.chengyinhub.business.domain.AuditAppeal();
        appeal.setId(Long.MAX_VALUE); appeal.setTaskId(Long.MAX_VALUE - 1); appeal.setState("SUBMITTED");
        when(service.submitAppeal("ACTIVITY", 90L, VIEWER_ID, "理由", null, null, "original")).thenReturn(appeal);
        assertEquals("9223372036854775807", controller.appeal(request).get("appealId"));
        verify(service, never()).submitAppeal("ACTIVITY", 90L, VIEWER_ID, "理由", null, null);
        controller.appealReceipt("ACTIVITY", 90L, "original");
        verify(service).appealReceipt("ACTIVITY", 90L, VIEWER_ID, "original");
    }

    private static class TestableController extends ApiAuditController {
        private final Long memberId;
        private TestableController(Long memberId) { this.memberId = memberId; }
        @Override public Long getAppUserId() { return memberId; }
    }
}
