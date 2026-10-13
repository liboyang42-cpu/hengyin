package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditRecord;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.service.IClubPostService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * §8 阶段B:审核申诉(复核)流程测试。
 * 现行申诉模型 = 状态机的升级复核路径:PENDING → ESCALATED(申诉/升级,内容不动)
 * → APPROVED(申诉成立/举报无效 → 恢复内容)或 REJECTED(维持下架)。
 * 终态(APPROVED/REJECTED)不可再转;乐观锁失败时不留处置记录、不动内容。
 */
class AuditTaskAppealFlowTest {

    private AuditTaskMapper auditTaskMapper;
    private AuditRecordMapper auditRecordMapper;
    private IClubPostService clubPostService;
    private AuditTaskServiceImpl svc;

    @BeforeEach
    void setUp() {
        auditTaskMapper = mock(AuditTaskMapper.class);
        auditRecordMapper = mock(AuditRecordMapper.class);
        clubPostService = mock(IClubPostService.class);
        svc = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(svc, "auditTaskMapper", auditTaskMapper);
        ReflectionTestUtils.setField(svc, "auditRecordMapper", auditRecordMapper);
        com.chengyinhub.business.mapper.BusinessAuditEventMapper auditMapper =
                mock(com.chengyinhub.business.mapper.BusinessAuditEventMapper.class);
        ReflectionTestUtils.setField(svc, "businessAuditEventMapper", auditMapper);
        ReflectionTestUtils.setField(svc, "clubPostService", clubPostService);
        when(auditRecordMapper.insertAuditRecord(any())).thenReturn(1);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);
    }

    private AuditTask task(long id, Integer status) {
        AuditTask t = new AuditTask();
        t.setId(id);
        t.setBizType(AuditTask.BIZ_CLUB_POST);
        t.setBizId(900L);
        t.setStatus(status);
        t.setVersion(0);
        return t;
    }

    private AuditReviewDTO decision(Integer status) {
        AuditReviewDTO d = new AuditReviewDTO();
        d.setStatus(status);
        d.setReasonText("复核处理");
        return d;
    }

    private void stubUpdateRows(int rows) {
        when(auditTaskMapper.updateAuditTaskReviewCas(anyLong(), any(), any(), any(), any(), any())).thenReturn(rows);
    }

    // ---------- 申诉受理:升级复核 ----------

    @Test
    void pending_escalate_contentUntouched_recordsESCALATE() {
        when(auditTaskMapper.selectAuditTaskById(1L)).thenReturn(task(1L, AuditTask.STATUS_PENDING));
        stubUpdateRows(1);

        int rows = svc.review(1L, 66L, decision(AuditTask.STATUS_ESCALATED));

        assertEquals(1, rows);
        ArgumentCaptor<AuditRecord> rec = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditRecordMapper).insertAuditRecord(rec.capture());
        assertEquals("ESCALATE", rec.getValue().getAction(), "升级复核应留 ESCALATE 处置记录");
        // 申诉/升级只是转交复核,不得动内容(既不下架也不恢复)
        verify(clubPostService, never()).deleteClubPostById(anyLong());
        verify(clubPostService, never()).updateClubPost(any());
    }

    // ---------- 复核终裁:申诉成立 → 恢复内容 ----------

    @Test
    void escalated_approve_restoresContent_recordsAPPROVE() {
        when(auditTaskMapper.selectAuditTaskById(2L)).thenReturn(task(2L, AuditTask.STATUS_ESCALATED));
        stubUpdateRows(1);

        int rows = svc.review(2L, 66L, decision(AuditTask.STATUS_APPROVED));

        assertEquals(1, rows);
        ArgumentCaptor<AuditRecord> rec = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditRecordMapper).insertAuditRecord(rec.capture());
        assertEquals("APPROVE", rec.getValue().getAction());
        ArgumentCaptor<ClubPost> post = ArgumentCaptor.forClass(ClubPost.class);
        verify(clubPostService).updateClubPost(post.capture());
        assertEquals(Long.valueOf(900L), post.getValue().getId());
        assertEquals(Integer.valueOf(0), post.getValue().getDelFlag(), "申诉成立应恢复内容(delFlag=0)");
    }

    // ---------- 复核终裁:维持违规 → 下架 ----------

    @Test
    void escalated_reject_takesDownContent_recordsREJECT() {
        when(auditTaskMapper.selectAuditTaskById(3L)).thenReturn(task(3L, AuditTask.STATUS_ESCALATED));
        stubUpdateRows(1);

        int rows = svc.review(3L, 66L, decision(AuditTask.STATUS_REJECTED));

        assertEquals(1, rows);
        ArgumentCaptor<AuditRecord> rec = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditRecordMapper).insertAuditRecord(rec.capture());
        assertEquals("REJECT", rec.getValue().getAction());
        verify(clubPostService).deleteClubPostById(900L); // 软下架 del_flag=2
    }

    // ---------- 终态不可再转 ----------

    @Test
    void rejected_isTerminal_cannotReopen() {
        when(auditTaskMapper.selectAuditTaskById(4L)).thenReturn(task(4L, AuditTask.STATUS_REJECTED));

        assertThrows(IllegalArgumentException.class,
                () -> svc.review(4L, 66L, decision(AuditTask.STATUS_APPROVED)),
                "REJECTED 为终态,申诉翻案须走新审核单而非改写旧单");
        verify(auditTaskMapper, never()).updateAuditTaskReviewCas(anyLong(), any(), any(), any(), any(), any());
        verify(auditRecordMapper, never()).insertAuditRecord(any());
    }

    @Test
    void approved_isTerminal_cannotReverse() {
        when(auditTaskMapper.selectAuditTaskById(5L)).thenReturn(task(5L, AuditTask.STATUS_APPROVED));

        assertThrows(IllegalArgumentException.class,
                () -> svc.review(5L, 66L, decision(AuditTask.STATUS_REJECTED)));
        verify(auditRecordMapper, never()).insertAuditRecord(any());
    }

    @Test
    void escalated_cannotEscalateAgain() {
        when(auditTaskMapper.selectAuditTaskById(6L)).thenReturn(task(6L, AuditTask.STATUS_ESCALATED));

        assertThrows(IllegalArgumentException.class,
                () -> svc.review(6L, 66L, decision(AuditTask.STATUS_ESCALATED)),
                "已在复核中不可重复升级");
    }

    // ---------- 乐观锁:并发复核只生效一次 ----------

    @Test
    void staleVersion_noRecord_noModeration() {
        when(auditTaskMapper.selectAuditTaskById(7L)).thenReturn(task(7L, AuditTask.STATUS_ESCALATED));
        stubUpdateRows(0); // version 不匹配,别的复核已先落锤

        int rows = svc.review(7L, 66L, decision(AuditTask.STATUS_APPROVED));

        assertEquals(0, rows, "乐观锁失败应返回 0");
        verify(auditRecordMapper, never()).insertAuditRecord(any());
        verify(clubPostService, never()).updateClubPost(any());
        verify(clubPostService, never()).deleteClubPostById(anyLong());
    }

    // ---------- 防御:任务不存在 / 参数不完整 ----------

    @Test
    void missingTaskOrStatus_rejected() {
        when(auditTaskMapper.selectAuditTaskById(8L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class,
                () -> svc.review(8L, 66L, decision(AuditTask.STATUS_APPROVED)), "任务不存在");
        assertThrows(IllegalArgumentException.class,
                () -> svc.review(1L, 66L, decision(null)), "status 缺失");
    }
}
