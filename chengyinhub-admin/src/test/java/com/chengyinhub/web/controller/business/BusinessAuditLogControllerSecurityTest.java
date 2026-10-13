package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.dto.BusinessAuditLogQueryDTO;
import com.chengyinhub.business.service.IBusinessAuditLogQueryService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BusinessAuditLogControllerSecurityTest {
    private static final long NON_ADMIN_USER_ID = 2L;

    @Test
    void endpointRequiresDedicatedPermissionAndAdminRole() throws Exception {
        Method method = BusinessAuditLogController.class.getMethod("list", BusinessAuditLogQueryDTO.class);
        String expression = method.getAnnotation(PreAuthorize.class).value();

        assertTrue(expression.contains("business:audit:log:list"));
        assertTrue(expression.contains("hasRole('admin')"));
    }

    @Test
    void controllerPassesFailClosedAdminFactToService() {
        IBusinessAuditLogQueryService service = mock(IBusinessAuditLogQueryService.class);
        BusinessAuditLogController controller = new TestableController(NON_ADMIN_USER_ID);
        ReflectionTestUtils.setField(controller, "businessAuditLogQueryService", service);
        BusinessAuditLogQueryDTO query = new BusinessAuditLogQueryDTO();

        controller.list(query);

        verify(service).query(any(BusinessAuditLogQueryDTO.class), org.mockito.ArgumentMatchers.eq(false));
    }

    private static class TestableController extends BusinessAuditLogController {
        private final Long userId;
        private TestableController(Long userId) { this.userId = userId; }
        @Override public Long getUserId() { return userId; }
    }
}
