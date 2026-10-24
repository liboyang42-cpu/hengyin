package com.chengyinhub.business.service.support.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.dto.BusinessAuditLogQueryDTO;
import com.chengyinhub.business.domain.vo.BusinessAuditLogPageVO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.service.IBusinessAuditLogQueryService;

class SupportOriginalReadFactoryTest {

    @Test
    void listUsesTheExistingAuditQueryAndOrderLookupUsesTheOriginalMapper() {
        IBusinessAuditLogQueryService auditLog = mock(IBusinessAuditLogQueryService.class);
        BusinessAuditEventMapper mapper = mock(BusinessAuditEventMapper.class);
        BusinessAuditLogQueryDTO query = new BusinessAuditLogQueryDTO();
        BusinessAuditLogPageVO page = new BusinessAuditLogPageVO();
        when(auditLog.query(query, true)).thenReturn(page);
        BusinessAuditEvent order = event("req-order-1", "ORDER", 88L, "PAY", "UNKNOWN");
        when(mapper.selectByTarget("ORDER", 88L)).thenReturn(List.of(order));
        SupportOriginalReadFactory factory = new SupportOriginalReadFactory(auditLog, mapper);

        assertSame(page, factory.list(query, true));
        verify(auditLog).query(query, true);
        verifyNoInteractions(mapper);

        SupportEventLocator.LocatedEvent located = factory.locateOrder(88L,
                new SupportEventLocator.CurrentProjection("req-order-1",
                        SupportEventLocator.PROJECTION_APPLIED_SUCCESS));
        assertEquals("req-order-1", located.getRequestId());
        assertEquals("UNKNOWN", located.getOriginalOutcome());
        assertEquals(88L, located.getOrderId());
        assertTrue(located.originalIsNotProjectionSuccess());
    }

    @Test
    void aDifferentFirstOrderIsNotThisLookup() {
        IBusinessAuditLogQueryService auditLog = mock(IBusinessAuditLogQueryService.class);
        BusinessAuditEventMapper mapper = mock(BusinessAuditEventMapper.class);
        when(mapper.selectByTarget("ORDER", 7L)).thenReturn(List.of(
                event("req-other", "ORDER", 99L, "PAY", "UNKNOWN")));
        SupportOriginalReadFactory factory = new SupportOriginalReadFactory(auditLog, mapper);

        IllegalStateException mismatch = assertThrows(IllegalStateException.class,
                () -> factory.locateOrder(7L, null));
        assertTrue(mismatch.getMessage().contains("orderId=7"));
        verifyNoInteractions(auditLog);
    }

    private static BusinessAuditEvent event(String requestId, String targetType, Long targetId,
                                            String action, String afterJson) {
        BusinessAuditEvent event = new BusinessAuditEvent();
        event.setRequestId(requestId);
        event.setTargetType(targetType);
        event.setTargetId(targetId);
        event.setAction(action);
        event.setAfterJson(afterJson);
        event.setCreateTime(new Date(1L));
        return event;
    }
}
