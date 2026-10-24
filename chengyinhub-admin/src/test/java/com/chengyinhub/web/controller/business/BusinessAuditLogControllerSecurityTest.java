package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.dto.BusinessAuditLogQueryDTO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.service.IBusinessAuditLogQueryService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
        BusinessAuditEventMapper mapper = mock(BusinessAuditEventMapper.class);
        BusinessAuditLogController controller = new TestableController(NON_ADMIN_USER_ID);
        ReflectionTestUtils.setField(controller, "businessAuditLogQueryService", service);
        ReflectionTestUtils.setField(controller, "businessAuditEventMapper", mapper);
        BusinessAuditLogQueryDTO query = new BusinessAuditLogQueryDTO();

        controller.list(query);

        verify(service).query(any(BusinessAuditLogQueryDTO.class), org.mockito.ArgumentMatchers.eq(false));
        verifyNoInteractions(mapper);
    }

    @Test
    void orderFilterUsesTheOriginalEventAndADifferentOrderIsNotListed() {
        IBusinessAuditLogQueryService service = mock(IBusinessAuditLogQueryService.class);
        BusinessAuditEventMapper mapper = mock(BusinessAuditEventMapper.class);
        when(service.query(any(BusinessAuditLogQueryDTO.class), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(new com.chengyinhub.business.domain.vo.BusinessAuditLogPageVO());
        BusinessAuditEvent other = new BusinessAuditEvent();
        other.setRequestId("req-other");
        other.setTargetType("ORDER");
        other.setTargetId(99L);
        other.setAction("PAY");
        other.setAfterJson("UNKNOWN");
        other.setCreateTime(new Date(1L));
        when(mapper.selectByTarget("ORDER", 7L)).thenReturn(List.of(other));
        BusinessAuditLogController controller = new TestableController(1L);
        ReflectionTestUtils.setField(controller, "businessAuditLogQueryService", service);
        ReflectionTestUtils.setField(controller, "businessAuditEventMapper", mapper);
        BusinessAuditLogQueryDTO query = new BusinessAuditLogQueryDTO();
        query.setTargetType("ORDER");
        query.setTargetId(7L);

        AjaxResult result = controller.list(query);

        assertEquals(HttpStatus.ERROR, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("orderId=7"));
    }

    private static class TestableController extends BusinessAuditLogController {
        private final Long userId;
        private TestableController(Long userId) { this.userId = userId; }
        @Override public Long getUserId() { return userId; }
    }
}
