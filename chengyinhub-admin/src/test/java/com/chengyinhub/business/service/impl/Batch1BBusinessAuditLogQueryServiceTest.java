package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.dto.BusinessAuditLogQueryDTO;
import com.chengyinhub.business.domain.vo.BusinessAuditLogPageVO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Batch1BBusinessAuditLogQueryServiceTest {
    @Test
    void redactsSensitiveValuesFromUnstructuredReason() {
        BusinessAuditEventMapper mapper = mock(BusinessAuditEventMapper.class);
        BusinessAuditEvent event = new BusinessAuditEvent();
        event.setId(1L);
        event.setReason("联系13800138000，证件11010519491231002X，token=plain-secret");
        when(mapper.selectAuditLogPage(any(BusinessAuditLogQueryDTO.class)))
                .thenReturn(Collections.singletonList(event));

        BusinessAuditLogQueryServiceImpl service = new BusinessAuditLogQueryServiceImpl();
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", mapper);
        BusinessAuditLogPageVO page = service.query(new BusinessAuditLogQueryDTO(), true);

        String reason = page.getItems().get(0).getReason();
        assertEquals("联系***，证件***，token=***", reason);
        assertFalse(reason.contains("13800138000"));
    }
}
