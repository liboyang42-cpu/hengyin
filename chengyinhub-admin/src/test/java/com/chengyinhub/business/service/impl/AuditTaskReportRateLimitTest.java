package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 举报 submitterId 限频单测:同一提交人时间窗内新建举报单超阈值被拒;
 * 幂等命中/safetyHold/非举报 submit 均不受限频影响。
 */
class AuditTaskReportRateLimitTest {

    private AuditTaskMapper auditTaskMapper;
    private AuditRecordMapper auditRecordMapper;
    private AuditTaskServiceImpl svc;

    @BeforeEach
    void setUp() {
        auditTaskMapper = mock(AuditTaskMapper.class);
        auditRecordMapper = mock(AuditRecordMapper.class);
        svc = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(svc, "auditTaskMapper", auditTaskMapper);
        ReflectionTestUtils.setField(svc, "auditRecordMapper", auditRecordMapper);
    }

    /** ① 超阈值被拒:窗口内已达上限 → 拒绝、不建单、不 append 举报记录。 */
    @Test
    void overThreshold_rejected_noInsert_noRecord() {
        when(auditTaskMapper.selectAuditTaskList(any())).thenReturn(Collections.emptyList());
        when(auditTaskMapper.countRecentReportsBySubmitter(eq(100L), any(Date.class))).thenReturn(20);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.reportContent("COMMENT", 1L, 100L, "spam"));
        assertEquals("举报过于频繁，请稍后再试", ex.getMessage());
        verify(auditTaskMapper, never()).insertAuditTask(any());
        verify(auditRecordMapper, never()).insertAuditRecord(any());
    }

    /** ② 未超阈值放行:窗口内计数低于阈值 → 正常建单 + append 举报记录。 */
    @Test
    void underThreshold_allowed_createsTask_appendsRecord() {
        when(auditTaskMapper.selectAuditTaskList(any())).thenReturn(Collections.emptyList());
        when(auditTaskMapper.countRecentReportsBySubmitter(eq(100L), any(Date.class))).thenReturn(19);
        when(auditTaskMapper.insertAuditTask(any())).thenAnswer(inv -> {
            ((AuditTask) inv.getArgument(0)).setId(9L);
            return 1;
        });

        Long id = svc.reportContent("SQUARE", 2L, 100L, "违规");

        assertEquals(Long.valueOf(9L), id);
        verify(auditTaskMapper, times(1)).insertAuditTask(any());
        verify(auditRecordMapper, times(1)).insertAuditRecord(any());
    }

    /** ③ 幂等命中不受限频影响:已有未结单 → 复用并 append 记录,限频计数根本不查询。 */
    @Test
    void idempotentHit_notRateLimited_noCountQuery() {
        AuditTask open = new AuditTask();
        open.setId(5L);
        open.setStatus(AuditTask.STATUS_PENDING);
        when(auditTaskMapper.selectAuditTaskList(any())).thenReturn(Collections.singletonList(open));

        Long id = svc.reportContent("COMMENT", 1L, 100L, "spam");

        assertEquals(Long.valueOf(5L), id, "幂等命中 → 复用,不受限频拒");
        verify(auditTaskMapper, never()).countRecentReportsBySubmitter(any(), any());
        verify(auditTaskMapper, never()).insertAuditTask(any());
        verify(auditRecordMapper, times(1)).insertAuditRecord(any());
    }

    /** ④a safetyHold(source=auto_gate)不受限频:即便同 submitter,也不查限频计数、正常建单。 */
    @Test
    void safetyHold_notRateLimited() {
        when(auditTaskMapper.selectAuditTaskList(any())).thenReturn(Collections.emptyList());

        svc.submitSafetyHold("SQUARE", 3L, 100L, "HIGH", "{}");

        verify(auditTaskMapper, never()).countRecentReportsBySubmitter(any(), any());
        verify(auditTaskMapper, times(1)).insertAuditTask(any());
    }

    /** ④b 非举报 submit(user_publish 等)不受限频:plain submit 直插,不查限频计数。 */
    @Test
    void plainSubmit_notRateLimited() {
        svc.submit("TOPIC", 4L, 100L, "user_publish", "{}");

        verify(auditTaskMapper, never()).countRecentReportsBySubmitter(any(), any());
        verify(auditTaskMapper, times(1)).insertAuditTask(any());
    }
}
