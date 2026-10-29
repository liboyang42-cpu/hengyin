package com.chengyinhub.business.task;

import java.util.Map;
import com.chengyinhub.business.service.ai.AiQuotaRecoveryService;
import com.chengyinhub.business.service.impl.AiThemeDraftRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiQuotaRecoveryTaskTest {
    private final AiQuotaRecoveryService quota = mock(AiQuotaRecoveryService.class);
    private final AiThemeDraftRequestService drafts = mock(AiThemeDraftRequestService.class);

    private AnnotationConfigApplicationContext context(Boolean maintenance) {
        var context = new AnnotationConfigApplicationContext();
        if (maintenance != null) context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "maintenance-test", Map.of("ai.themeDraftIntent.maintenanceEnabled", maintenance.toString(),
                        "ai.themeDraftIntent.enabled", "false")));
        context.getBeanFactory().registerSingleton("quota", quota);
        context.getBeanFactory().registerSingleton("drafts", drafts);
        context.register(AiQuotaRecoveryTask.class);
        context.refresh();
        return context;
    }

    @Test void missingMaintenanceFlagPreservesExistingQuotaOnlyJob() {
        try (var context = context(null)) { context.getBean(AiQuotaRecoveryTask.class).run(); }
        verify(quota).runDue();
        verifyNoInteractions(drafts);
    }

    @Test void enabledMaintenanceUsesExistingJobAndFixedBoundWithAdmissionClosed() {
        try (var context = context(true)) { context.getBean(AiQuotaRecoveryTask.class).run(); }
        var order = inOrder(quota, drafts);
        order.verify(quota).runDue();
        order.verify(drafts).purgeExpiredBodies(100);
        order.verifyNoMoreInteractions();
    }

    @Test void quotaFailureKeepsItsOriginalFailureAndDoesNotStartOtherWork() {
        var failure = new IllegalStateException("controlled quota storage failure");
        doThrow(failure).when(quota).runDue();
        try (var context = context(true)) {
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> context.getBean(AiQuotaRecoveryTask.class).run()));
        }
        verifyNoInteractions(drafts);
    }

    @Test void purgeFailureRemainsVisibleAfterOriginalQuotaWorkCompleted() {
        var failure = new IllegalStateException("controlled exact-key purge failure");
        doThrow(failure).when(drafts).purgeExpiredBodies(100);
        try (var context = context(true)) {
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> context.getBean(AiQuotaRecoveryTask.class).run()));
        }
        verify(quota).runDue();
    }
}
