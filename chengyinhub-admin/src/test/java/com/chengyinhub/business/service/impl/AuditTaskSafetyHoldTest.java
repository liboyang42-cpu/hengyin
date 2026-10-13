package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * submitSafetyHold 幂等单测:同 bizType+bizId 已有未结审核单则复用,不重复建。
 */
class AuditTaskSafetyHoldTest {

    private AuditTaskMapper auditTaskMapper;
    private AuditTaskServiceImpl svc;

    @BeforeEach
    void setUp() {
        auditTaskMapper = mock(AuditTaskMapper.class);
        svc = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(svc, "auditTaskMapper", auditTaskMapper);
    }

    @Test
    void reusesExistingOpenTask_noInsert() {
        AuditTask open = new AuditTask();
        open.setId(5L);
        open.setStatus(AuditTask.STATUS_PENDING);
        when(auditTaskMapper.selectAuditTaskList(any())).thenReturn(Collections.singletonList(open));

        Long id = svc.submitSafetyHold("SQUARE", 1L, 100L, "NORMAL", "{}");

        assertEquals(Long.valueOf(5L), id, "已有未结单 → 复用其 id");
        verify(auditTaskMapper, never()).insertAuditTask(any());
    }

    @Test
    void resolvedTasksDontBlock_insertsNew() {
        // 只有已通过/已拒绝的历史单 → 不算未结,应新建
        AuditTask approved = new AuditTask();
        approved.setId(7L);
        approved.setStatus(AuditTask.STATUS_APPROVED);
        when(auditTaskMapper.selectAuditTaskList(any())).thenReturn(Arrays.asList(approved));

        svc.submitSafetyHold("SQUARE", 2L, 100L, "NORMAL", "{}");

        verify(auditTaskMapper, times(1)).insertAuditTask(any());
    }

    @Test
    void noExistingTask_insertsNew() {
        when(auditTaskMapper.selectAuditTaskList(any())).thenReturn(Collections.emptyList());

        svc.submitSafetyHold("COMMENT", 3L, 100L, "NORMAL", "{}");

        verify(auditTaskMapper, times(1)).insertAuditTask(any());
    }
}
