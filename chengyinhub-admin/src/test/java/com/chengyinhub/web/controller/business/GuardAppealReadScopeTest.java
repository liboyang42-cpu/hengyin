package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.common.constant.HttpStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Detail rejection must precede record/evidence reads, independently of list visibility. */
class GuardAppealReadScopeTest {
    @Test void unconfiguredScopeCannotReadTaskOrEvidenceByExactId() {
        IAuditTaskService service = mock(IAuditTaskService.class);
        AuditTask task = new AuditTask(); task.setId(71L); task.setBizType("play_arrive_guard");
        when(service.selectAuditTaskById(71L)).thenReturn(task);
        when(service.canReadGuardTask(task)).thenReturn(false);
        AuditWorkbenchController controller = new AuditWorkbenchController();
        ReflectionTestUtils.setField(controller, "auditTaskService", service);
        assertEquals(HttpStatus.FORBIDDEN, controller.getInfo(71L).get("code"));
        assertEquals(HttpStatus.FORBIDDEN, controller.detail(71L, "review-1").get("code"));
        verify(service, never()).listAuditRecords(any());
        verify(service, never()).guardReviewReceipt(any(), any(), any());
        verify(service, never()).detail(any(), any(), anyBoolean());
    }
    @Test void scopeIsResolvedBeforePaginationAndCannotComeFromClientParams() {
        java.util.concurrent.atomic.AtomicBoolean pageStarted = new java.util.concurrent.atomic.AtomicBoolean();
        AuditWorkbenchController controller = new AuditWorkbenchController() {
            @Override protected void startPage() { pageStarted.set(true); }
        };
        IAuditTaskService service = mock(IAuditTaskService.class);
        com.chengyinhub.business.service.support.GuardAppealReviewerScope scope =
                mock(com.chengyinhub.business.service.support.GuardAppealReviewerScope.class);
        ReflectionTestUtils.setField(controller, "auditTaskService", service);
        ReflectionTestUtils.setField(controller, "guardAppealReviewerScope", scope);
        when(scope.readableTopicIds()).thenAnswer(call -> {
            assertFalse(pageStarted.get(), "config SQL must not consume workbench pagination");
            return java.util.List.of(77L);
        });
        AuditTask filter = new AuditTask(); filter.getParams().put("guardTopicIds", java.util.List.of(88L));
        when(service.selectWorkbenchQueueWithGuardScope(filter, java.util.List.of(77L))).thenAnswer(call -> {
            assertTrue(pageStarted.get()); return java.util.List.of();
        });
        controller.list(filter);
        verify(service).selectWorkbenchQueueWithGuardScope(filter, java.util.List.of(77L));
        verify(service, never()).selectAuditTaskList(any());
    }
}
