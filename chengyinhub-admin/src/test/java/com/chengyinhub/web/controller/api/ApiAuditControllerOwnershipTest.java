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

    private static class TestableController extends ApiAuditController {
        private final Long memberId;
        private TestableController(Long memberId) { this.memberId = memberId; }
        @Override public Long getAppUserId() { return memberId; }
    }
}
