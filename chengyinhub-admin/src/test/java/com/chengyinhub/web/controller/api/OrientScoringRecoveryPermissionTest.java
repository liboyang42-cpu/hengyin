package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IOrientScoringService;
import com.chengyinhub.web.controller.business.OrientScoringAdminController;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrientScoringRecoveryPermissionTest {
    public static class Permission {
        boolean allowed;
        public boolean hasPermi(String permission) { return allowed && permission.startsWith("business:orientScoring:"); }
    }
    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean(name = "ss") Permission permission() { return new Permission(); }
        @Bean IOrientScoringService service() { return mock(IOrientScoringService.class); }
        @Bean OrientScoringAdminController controller() {
            return new OrientScoringAdminController() {
                @Override public String getUsername() { return "reviewer"; }
            };
        }
    }
    @Test
    void existingMethodPermissionsRejectRecoveryReadsAndWritesBeforeService() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("test-reviewer", "unused", java.util.List.of()));
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var controller = context.getBean(OrientScoringAdminController.class);
            var service = context.getBean(IOrientScoringService.class);
            assertThrows(AccessDeniedException.class, () -> controller.recoveries(0, 50));
            assertThrows(AccessDeniedException.class, () -> controller.reviewRecovery(1L, null, "核对"));
            verifyNoInteractions(service);
            context.getBean(Permission.class).allowed = true;
            controller.reviewRecovery(1L, null, "核对");
            verify(service).reviewRecovery(1L, null, "核对", "reviewer");
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }
}
