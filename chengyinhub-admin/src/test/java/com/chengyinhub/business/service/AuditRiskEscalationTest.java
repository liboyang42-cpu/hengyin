package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.service.impl.AuditTaskServiceImpl;
import com.chengyinhub.business.service.support.AuditRiskLevels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O-05 #2:risk_level 随内容升级 —— 新举报、重提换了快照、门禁再次进件时重算开放单风险,只升不降。
 * 开关 chengyin.audit.risk-escalation.enabled 默认关,关时不读不写。
 */
class AuditRiskEscalationTest {
    private AuditTaskMapper taskMapper;
    private AuditRecordMapper recordMapper;
    private AuditTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(AuditTaskMapper.class);
        recordMapper = mock(AuditRecordMapper.class);
        service = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(service, "auditTaskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "auditRecordMapper", recordMapper);
        when(recordMapper.insertAuditRecord(any())).thenReturn(1);
        when(taskMapper.refreshOpenAuditSnapshot(anyLong(), anyString(), anyString(), anyString(), anyLong(), any()))
                .thenReturn(1);
        when(taskMapper.escalateOpenRiskLevel(anyLong(), any(), anyString())).thenReturn(1);
    }

    private void enable() {
        ReflectionTestUtils.setField(service, "riskEscalationEnabled", true);
    }

    private AuditTask open(Long id, String bizType, String sourceType, String risk, String snapshot) {
        AuditTask task = new AuditTask();
        task.setId(id);
        task.setBizType(bizType);
        task.setBizId(77L);
        task.setSubmitterId(5L);
        task.setSourceType(sourceType);
        task.setRiskLevel(risk);
        task.setStatus(AuditTask.STATUS_PENDING);
        task.setSnapshotJson(snapshot);
        task.setVersion(0);
        return task;
    }

    @Test
    void 重算只升不降_标记值同档保留() {
        assertEquals("HIGH", AuditRiskLevels.recompute("HIGH", "NORMAL", 0, 3));
        assertEquals("HIGH", AuditRiskLevels.recompute("HIGH", "MEDIUM", 9, 3));
        assertEquals("EDIT_REVIEW", AuditRiskLevels.recompute("EDIT_REVIEW", "NORMAL", 2, 3));
        assertEquals("HIGH", AuditRiskLevels.recompute("EDIT_REVIEW", null, 3, 3));
        assertEquals("MEDIUM", AuditRiskLevels.recompute("NORMAL", "MEDIUM", 0, 3));
        assertEquals("NORMAL", AuditRiskLevels.recompute("NORMAL", null, 50, 0));
    }

    @Test
    void 开关关闭_举报不读举报数也不写风险() {
        AuditTask task = open(9L, AuditTask.BIZ_COMMENT, "report", "NORMAL", "{}");
        when(taskMapper.selectAuditTaskList(any())).thenReturn(Collections.singletonList(task));
        when(taskMapper.selectAuditTaskById(9L)).thenReturn(task);

        service.reportContent(AuditTask.BIZ_COMMENT, 77L, 31L, "辱骂");

        verify(taskMapper, never()).countOpenReportersByBiz(anyString(), anyLong());
        verify(taskMapper, never()).escalateOpenRiskLevel(anyLong(), any(), anyString());
    }

    @Test
    void 开关打开_举报人数达阈值升HIGH() {
        enable();
        AuditTask task = open(9L, AuditTask.BIZ_COMMENT, "report", "NORMAL", "{}");
        when(taskMapper.selectAuditTaskList(any())).thenReturn(Collections.singletonList(task));
        when(taskMapper.selectAuditTaskById(9L)).thenReturn(task);
        when(taskMapper.countOpenReportersByBiz(AuditTask.BIZ_COMMENT, 77L)).thenReturn(3);

        service.reportContent(AuditTask.BIZ_COMMENT, 77L, 31L, "辱骂");

        verify(taskMapper).escalateOpenRiskLevel(9L, "NORMAL", "HIGH");
    }

    @Test
    void 开关打开_举报人数未达阈值不升() {
        enable();
        AuditTask task = open(9L, AuditTask.BIZ_COMMENT, "report", "NORMAL", "{}");
        when(taskMapper.selectAuditTaskList(any())).thenReturn(Collections.singletonList(task));
        when(taskMapper.selectAuditTaskById(9L)).thenReturn(task);
        when(taskMapper.countOpenReportersByBiz(AuditTask.BIZ_COMMENT, 77L)).thenReturn(2);

        service.reportContent(AuditTask.BIZ_COMMENT, 77L, 31L, "辱骂");

        verify(taskMapper, never()).escalateOpenRiskLevel(anyLong(), any(), anyString());
    }

    @Test
    void 开关打开_门禁HIGH进件不被已开着的NORMAL单吞掉() {
        enable();
        AuditTask task = open(9L, AuditTask.BIZ_COMMENT, "auto_gate", "NORMAL", "{}");
        when(taskMapper.selectAuditTaskList(any())).thenReturn(Collections.singletonList(task));
        when(taskMapper.selectAuditTaskById(9L)).thenReturn(task);

        service.submitSafetyHold(AuditTask.BIZ_COMMENT, 77L, 5L, "HIGH", "{\"v\":2}");

        verify(taskMapper).escalateOpenRiskLevel(9L, "NORMAL", "HIGH");
    }

    @Test
    void 开关打开_低风险进件不降已是HIGH的单() {
        enable();
        AuditTask task = open(9L, AuditTask.BIZ_COMMENT, "auto_gate", "HIGH", "{}");
        when(taskMapper.selectAuditTaskList(any())).thenReturn(Collections.singletonList(task));
        when(taskMapper.selectAuditTaskById(9L)).thenReturn(task);

        service.submitSafetyHold(AuditTask.BIZ_COMMENT, 77L, 5L, "NORMAL", "{\"v\":2}");

        verify(taskMapper, never()).escalateOpenRiskLevel(anyLong(), any(), anyString());
    }

    @Test
    void 开关打开_重提换了快照按举报重算_快照未变不重算() {
        enable();
        AuditTask task = open(9L, AuditTask.BIZ_TEMPLATE, "auto_gate", "NORMAL", "{\"v\":1}");
        when(taskMapper.selectOpenAuditTaskForUpdate(AuditTask.BIZ_TEMPLATE, 77L)).thenReturn(task);
        when(taskMapper.selectAuditTaskById(9L)).thenReturn(task);
        when(taskMapper.countOpenReportersByBiz(AuditTask.BIZ_TEMPLATE, 77L)).thenReturn(4);

        service.submitOrRefreshTemplate(77L, 5L, "{\"v\":1}");
        verify(taskMapper, never()).escalateOpenRiskLevel(anyLong(), any(), anyString());

        service.submitOrRefreshTemplate(77L, 5L, "{\"v\":2}");
        verify(taskMapper).escalateOpenRiskLevel(9L, "NORMAL", "HIGH");
    }

    @Test
    void 开关打开_已结束的单不重算() {
        enable();
        AuditTask task = open(9L, AuditTask.BIZ_COMMENT, "report", "NORMAL", "{}");
        AuditTask closed = open(9L, AuditTask.BIZ_COMMENT, "report", "NORMAL", "{}");
        closed.setStatus(AuditTask.STATUS_APPROVED);
        when(taskMapper.selectAuditTaskList(any())).thenReturn(Collections.singletonList(task));
        when(taskMapper.selectAuditTaskById(9L)).thenReturn(closed);
        when(taskMapper.countOpenReportersByBiz(anyString(), anyLong())).thenReturn(10);

        service.reportContent(AuditTask.BIZ_COMMENT, 77L, 31L, "辱骂");

        verify(taskMapper, never()).escalateOpenRiskLevel(anyLong(), any(), anyString());
    }
}
