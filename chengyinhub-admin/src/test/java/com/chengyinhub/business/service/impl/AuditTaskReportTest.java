package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditRecord;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.RoamHangout;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.RoamHangoutMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * reportContent 幂等单测:同 bizType+bizId 未结单复用(不刷单),每次举报 append 一条 AuditRecord(action=REPORT)留痕。
 */
class AuditTaskReportTest {

    private AuditTaskMapper auditTaskMapper;
    private AuditRecordMapper auditRecordMapper;
    private RoamHangoutMapper roamHangoutMapper;
    private AuditTaskServiceImpl svc;

    @BeforeEach
    void setUp() {
        auditTaskMapper = mock(AuditTaskMapper.class);
        auditRecordMapper = mock(AuditRecordMapper.class);
        roamHangoutMapper = mock(RoamHangoutMapper.class);
        svc = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(svc, "auditTaskMapper", auditTaskMapper);
        ReflectionTestUtils.setField(svc, "auditRecordMapper", auditRecordMapper);
        ReflectionTestUtils.setField(svc, "roamHangoutMapper", roamHangoutMapper);
        AuditTask pending = new AuditTask(); pending.setStatus(AuditTask.STATUS_PENDING);
        when(auditTaskMapper.selectAuditTaskByIdForUpdate(any())).thenReturn(pending);
        when(auditRecordMapper.insertAuditRecord(any())).thenReturn(1);
    }

    @Test
    void reusesOpenTask_noInsert_appendsReportRecord() {
        AuditTask open = new AuditTask();
        open.setId(5L);
        open.setStatus(AuditTask.STATUS_PENDING);
        when(auditTaskMapper.selectAuditTaskList(any())).thenReturn(Collections.singletonList(open));

        Long id = svc.reportContent("COMMENT", 1L, 100L, "垃圾广告");

        assertEquals(Long.valueOf(5L), id, "已有未结单 → 复用其 id,不刷单");
        verify(auditTaskMapper, never()).insertAuditTask(any());
        ArgumentCaptor<AuditRecord> cap = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditRecordMapper, times(1)).insertAuditRecord(cap.capture());
        assertEquals("REPORT", cap.getValue().getAction(), "每次举报 append 一条 REPORT 记录");
        assertEquals(Long.valueOf(5L), cap.getValue().getTaskId());
        assertEquals(Long.valueOf(100L), cap.getValue().getOperatorId());
    }

    @Test
    void noOpenTask_createsTask_appendsReportRecord() {
        when(auditTaskMapper.selectAuditTaskList(any())).thenReturn(Collections.emptyList());
        when(auditTaskMapper.insertAuditTask(any())).thenAnswer(inv -> {
            ((AuditTask) inv.getArgument(0)).setId(9L);
            return 1;
        });

        Long id = svc.reportContent("SQUARE", 2L, 100L, "违规");

        assertEquals(Long.valueOf(9L), id);
        verify(auditTaskMapper, times(1)).insertAuditTask(any());
        ArgumentCaptor<AuditRecord> cap = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditRecordMapper, times(1)).insertAuditRecord(cap.capture());
        assertEquals("REPORT", cap.getValue().getAction());
        assertEquals(Long.valueOf(9L), cap.getValue().getTaskId());
    }

    @Test
    void hangoutReportSnapshotPreservesPrivateAuditContextWithoutLocationPrecision() {
        when(auditTaskMapper.selectAuditTaskList(any())).thenReturn(Collections.emptyList());
        when(auditTaskMapper.insertAuditTask(any())).thenAnswer(inv -> {
            ((AuditTask) inv.getArgument(0)).setId(12L);
            return 1;
        });
        RoamHangout hangout = new RoamHangout();
        hangout.setTitle("外滩散步");
        hangout.setDescription("周末慢走");
        hangout.setAddressName("外白渡桥附近");
        hangout.setStartAt(new java.util.Date(0));
        hangout.setStatus(RoamHangout.STATUS_OPEN);
        hangout.setMemberId(99L);
        hangout.setOwnerRole("club");
        hangout.setOwnerNickname("当时局主");
        hangout.setCoverUrl("https://image.example/cover.jpg");
        hangout.setLatitude(new java.math.BigDecimal("31.2400"));
        hangout.setLongitude(new java.math.BigDecimal("121.4900"));
        when(roamHangoutMapper.selectById(3L, null)).thenReturn(hangout);

        svc.reportContent(AuditTask.BIZ_HANGOUT, 3L, 100L, "扰民");

        ArgumentCaptor<AuditTask> cap = ArgumentCaptor.forClass(AuditTask.class);
        verify(auditTaskMapper).insertAuditTask(cap.capture());
        String snapshot = cap.getValue().getSnapshotJson();
        assertEquals(true, snapshot.contains("外滩散步"));
        assertEquals(true, snapshot.contains("周末慢走"));
        assertEquals(true, snapshot.contains("外白渡桥附近"));
        assertEquals(true, snapshot.contains("startAt"));
        assertEquals(true, snapshot.contains("memberId"));
        assertEquals(true, snapshot.contains("当时局主"));
        assertEquals(true, snapshot.contains("cover.jpg"));
        assertEquals(true, snapshot.contains("club"));
        assertEquals(false, snapshot.contains("latitude"));
        assertEquals(false, snapshot.contains("longitude"));
    }

    @Test
    void missingHangoutRecordsExplicitEvidenceGapAndInvestigationPath() {
        when(auditTaskMapper.insertAuditTask(any())).thenAnswer(inv -> {
            ((AuditTask) inv.getArgument(0)).setId(12L); return 1;
        });
        svc.reportContent(AuditTask.BIZ_HANGOUT, 3L, 100L, "内容已删除但请求核查");
        ArgumentCaptor<AuditTask> cap = ArgumentCaptor.forClass(AuditTask.class);
        verify(auditTaskMapper).insertAuditTask(cap.capture());
        String snapshot = cap.getValue().getSnapshotJson();
        assertEquals(true, snapshot.contains("MISSING"));
        assertEquals(true, snapshot.contains("同组局审核历史"));
        assertEquals(true, snapshot.contains("内容已删除但请求核查"));
    }

    @Test
    void reportThatLosesRaceToDecisionMustRetryInsteadOfAppendingToTerminalTask() {
        AuditTask pending = new AuditTask(); pending.setId(12L); pending.setStatus(AuditTask.STATUS_PENDING);
        AuditTask decided = new AuditTask(); decided.setId(12L); decided.setStatus(AuditTask.STATUS_REJECTED);
        when(auditTaskMapper.selectAuditTaskList(any())).thenReturn(Collections.singletonList(pending));
        when(auditTaskMapper.selectAuditTaskByIdForUpdate(12L)).thenReturn(decided);
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> svc.reportContent(AuditTask.BIZ_HANGOUT, 3L, 100L, "审核期间的新举报"));
        verify(auditRecordMapper, never()).insertAuditRecord(any());
    }

    @Test
    void resolvedTaskDoesntBlock_createsNew() {
        AuditTask approved = new AuditTask();
        approved.setId(7L);
        approved.setStatus(AuditTask.STATUS_APPROVED);
        when(auditTaskMapper.selectAuditTaskList(any())).thenReturn(Collections.singletonList(approved));
        when(auditTaskMapper.insertAuditTask(any())).thenAnswer(inv -> {
            ((AuditTask) inv.getArgument(0)).setId(11L);
            return 1;
        });

        Long id = svc.reportContent("COMMENT", 3L, 100L, "spam");

        assertEquals(Long.valueOf(11L), id, "只有已结单 → 不复用,新建");
        verify(auditTaskMapper, times(1)).insertAuditTask(any());
        verify(auditRecordMapper, times(1)).insertAuditRecord(any());
    }

    @Test
    void concurrentInsert_duplicateKey_reusesWinnerTask() {
        // 并发举报同一内容:首查无未结单 → 走 insert;insert 撞 uk_audit_biz_version → catch 内二查命中先插入者
        AuditTask winner = new AuditTask();
        winner.setId(5L);
        winner.setStatus(AuditTask.STATUS_PENDING);
        when(auditTaskMapper.selectAuditTaskList(any()))
                .thenReturn(Collections.emptyList())
                .thenReturn(Collections.singletonList(winner));
        when(auditTaskMapper.insertAuditTask(any()))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_audit_biz_version"));

        Long id = svc.reportContent("COMMENT", 1L, 100L, "spam");

        assertEquals(Long.valueOf(5L), id, "并发撞键 → 复用先插入者 id,不 rethrow");
        ArgumentCaptor<AuditRecord> cap = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditRecordMapper, times(1)).insertAuditRecord(cap.capture());
        assertEquals("REPORT", cap.getValue().getAction(), "复用后仍 append 自己那条 REPORT 留痕");
        assertEquals(Long.valueOf(5L), cap.getValue().getTaskId());
    }

    @Test
    void duplicateKey_butRelookupMiss_rethrows() {
        // 撞键但重查仍无未结单(非并发同 biz,而是真异常)→ 应 rethrow,不吞、不 append 记录
        when(auditTaskMapper.selectAuditTaskList(any())).thenReturn(Collections.emptyList());
        when(auditTaskMapper.insertAuditTask(any()))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk"));

        assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> svc.reportContent("COMMENT", 1L, 100L, "spam"));
        verify(auditRecordMapper, never()).insertAuditRecord(any());
    }

    @Test
    void listAuditRecords_queriesByTaskId() {
        AuditRecord r = new AuditRecord();
        r.setAction("REPORT");
        when(auditRecordMapper.selectAuditRecordList(any())).thenReturn(Collections.singletonList(r));

        java.util.List<AuditRecord> out = svc.listAuditRecords(42L);

        assertEquals(1, out.size());
        ArgumentCaptor<AuditRecord> cap = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditRecordMapper).selectAuditRecordList(cap.capture());
        assertEquals(Long.valueOf(42L), cap.getValue().getTaskId(), "按 taskId 过滤");
    }
}
