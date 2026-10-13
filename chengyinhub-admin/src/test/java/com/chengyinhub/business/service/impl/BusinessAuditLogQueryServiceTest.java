package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.dto.BusinessAuditLogQueryDTO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessAuditLogQueryServiceTest {
    private static final int REQUESTED_LIMIT = 25;
    private static final String REDACTED_BEFORE = "{\"status\":1,\"phone\":\"***\"}";
    private static final String REDACTED_AFTER = "{\"nested\":{\"accessToken\":\"***\"},\"status\":2}";
    private BusinessAuditEventMapper mapper;
    private BusinessAuditLogQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(BusinessAuditEventMapper.class);
        service = new BusinessAuditLogQueryServiceImpl();
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", mapper);
    }

    @Test
    void nonAdminCannotReadOtherActorsAuditTrail() {
        BusinessAuditLogQueryDTO query = new BusinessAuditLogQueryDTO();

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.query(query, false));

        assertEquals(Integer.valueOf(HttpStatus.FORBIDDEN), error.getCode());
        verify(mapper, never()).selectAuditLogPage(any());
    }

    @Test
    void adminQueryUsesBoundedCursorPage() {
        BusinessAuditLogQueryDTO query = new BusinessAuditLogQueryDTO();
        query.setLimit(REQUESTED_LIMIT);
        when(mapper.selectAuditLogPage(query)).thenReturn(Collections.<BusinessAuditEvent>emptyList());

        service.query(query, true);

        assertEquals(Integer.valueOf(REQUESTED_LIMIT + 1), query.getFetchLimit());
        verify(mapper).selectAuditLogPage(query);
    }

    @Test
    void adminProjectionRedactsSensitiveBeforeAfterFields() {
        BusinessAuditLogQueryDTO query = new BusinessAuditLogQueryDTO();
        BusinessAuditEvent event = new BusinessAuditEvent();
        event.setId(9L);
        event.setBeforeJson("{\"status\":1,\"phone\":\"13800000000\"}");
        event.setAfterJson("{\"nested\":{\"accessToken\":\"secret\"},\"status\":2}");
        when(mapper.selectAuditLogPage(any(BusinessAuditLogQueryDTO.class))).thenReturn(Arrays.asList(event));

        com.chengyinhub.business.domain.vo.BusinessAuditLogPageVO page = service.query(query, true);

        assertEquals(REDACTED_BEFORE, page.getItems().get(0).getBeforeJson());
        assertEquals(REDACTED_AFTER, page.getItems().get(0).getAfterJson());
    }
}
