package com.chengyinhub.business.service.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.service.IClubPostService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F0528:通过/拒绝记录每个 bizType 对象投影的实际结果;best-effort 失败成为可重放的待处置意图。
 */
class AuditModerationOutcomeTest {

    private AuditTaskMapper tasks;
    private AuditRecordMapper records;
    private BusinessAuditEventMapper events;
    private IClubPostService clubPosts;
    private AuditTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        tasks = mock(AuditTaskMapper.class);
        records = mock(AuditRecordMapper.class);
        events = mock(BusinessAuditEventMapper.class);
        clubPosts = mock(IClubPostService.class);
        service = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(service, "auditTaskMapper", tasks);
        ReflectionTestUtils.setField(service, "auditRecordMapper", records);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", events);
        ReflectionTestUtils.setField(service, "clubPostService", clubPosts);
        when(records.insertAuditRecord(any())).thenReturn(1);
        when(events.insertBusinessAuditEvent(any())).thenReturn(1);
        when(tasks.updateAuditTaskReviewCas(anyLong(), any(), any(), any(), any(), any())).thenReturn(1);
    }

    private static AuditTask task(Integer status, int version) {
        AuditTask t = new AuditTask();
        t.setId(1L);
        t.setBizType(AuditTask.BIZ_CLUB_POST);
        t.setBizId(900L);
        t.setStatus(status);
        t.setVersion(version);
        return t;
    }

    private static AuditReviewDTO reject() {
        AuditReviewDTO d = new AuditReviewDTO();
        d.setStatus(AuditTask.STATUS_REJECTED);
        d.setReasonText("违规广告");
        return d;
    }

    private static BusinessAuditEvent event(long id, String action, String afterJson) {
        BusinessAuditEvent e = new BusinessAuditEvent();
        e.setId(id);
        e.setTargetId(1L);
        e.setAction(action);
        e.setAfterJson(afterJson);
        e.setReason("违规广告");
        e.setCreateTime(new Date());
        return e;
    }

    private String decisionAfterJson() {
        ArgumentCaptor<BusinessAuditEvent> captor = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(events).insertBusinessAuditEvent(captor.capture());
        return captor.getValue().getAfterJson();
    }

    @Test
    void successfulTakedownRecordsAppliedOutcome() {
        when(tasks.selectAuditTaskById(1L)).thenReturn(task(AuditTask.STATUS_PENDING, 0));
        when(clubPosts.deleteClubPostById(900L)).thenReturn(1);

        assertEquals(1, service.review(1L, 66L, reject()));

        String after = decisionAfterJson();
        assertTrue(after.contains("\"status\":2"));
        assertTrue(after.contains("\"outcome\":\"APPLIED\""), after);
    }

    @Test
    void failedTakedownKeepsDecisionButRecordsPendingIntent() {
        when(tasks.selectAuditTaskById(1L)).thenReturn(task(AuditTask.STATUS_PENDING, 0));
        doThrow(new RuntimeException("db timeout")).when(clubPosts).deleteClubPostById(900L);

        assertEquals(1, service.review(1L, 66L, reject()), "审核决定照常生效");

        String after = decisionAfterJson();
        assertTrue(after.contains("\"outcome\":\"PENDING\""), after);
        assertTrue(after.contains("db timeout"), after);
    }

    @Test
    void zeroRowProjectionIsPendingNotApplied() {
        when(tasks.selectAuditTaskById(1L)).thenReturn(task(AuditTask.STATUS_PENDING, 0));
        when(clubPosts.deleteClubPostById(900L)).thenReturn(0);

        service.review(1L, 66L, reject());

        assertTrue(decisionAfterJson().contains("\"outcome\":\"PENDING\""));
    }

    @Test
    void pendingIntentIsReplayedUntilProjectionUpdates() {
        AuditTask decided = task(AuditTask.STATUS_REJECTED, 1);
        when(tasks.selectAuditTaskByIdForUpdate(1L)).thenReturn(decided);
        when(tasks.selectAuditTaskById(1L)).thenReturn(decided);
        List<BusinessAuditEvent> trail = new ArrayList<>(Collections.singletonList(event(10L, "AUDIT_DECISION_REJECT",
                "{\"status\":2,\"version\":1,\"moderation\":{\"outcome\":\"PENDING\",\"bizType\":\"CLUB_POST\",\"bizId\":900}}")));
        when(events.selectByTarget("AUDIT_TASK", 1L)).thenReturn(trail);
        assertEquals("PENDING", service.moderationState(1L).get("outcome"));
        when(clubPosts.deleteClubPostById(900L)).thenReturn(1);

        Map<String, Object> result = service.replayModeration(1L, 66L, "PLATFORM_ADMIN");

        assertEquals("APPLIED", result.get("outcome"));
        assertEquals(1, result.get("attempt"));
        ArgumentCaptor<BusinessAuditEvent> captor = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(events).insertBusinessAuditEvent(captor.capture());
        assertEquals("AUDIT_MODERATION_REPLAY", captor.getValue().getAction());
        assertEquals(Long.valueOf(10L), captor.getValue().getRelationId());

        // 重放成功后读回:不再待处置
        trail.add(0, event(11L, "AUDIT_MODERATION_REPLAY", captor.getValue().getAfterJson()));
        assertEquals("APPLIED", service.moderationState(1L).get("outcome"));
        assertThrows(ServiceException.class, () -> service.replayModeration(1L, 66L, "PLATFORM_ADMIN"));
        verify(clubPosts, times(1)).deleteClubPostById(900L);
    }

    @Test
    void revertedOrChangedDecisionHasNoPendingIntent() {
        AuditTask reopened = task(AuditTask.STATUS_PENDING, 2);
        when(tasks.selectAuditTaskById(1L)).thenReturn(reopened);
        when(events.selectByTarget("AUDIT_TASK", 1L)).thenReturn(Arrays.asList(
                event(12L, "AUDIT_DECISION_REVERTED", "{\"status\":0,\"version\":2}"),
                event(10L, "AUDIT_DECISION_REJECT", "{\"status\":2,\"version\":1,\"moderation\":{\"outcome\":\"PENDING\"}}")));
        assertNull(service.moderationState(1L));

        AuditTask stale = task(AuditTask.STATUS_REJECTED, 5);
        when(tasks.selectAuditTaskByIdForUpdate(1L)).thenReturn(stale);
        when(events.selectByTarget("AUDIT_TASK", 1L)).thenReturn(Collections.singletonList(
                event(10L, "AUDIT_DECISION_REJECT", "{\"status\":2,\"version\":1,\"moderation\":{\"outcome\":\"PENDING\"}}")));
        assertThrows(ServiceException.class, () -> service.replayModeration(1L, 66L, "PLATFORM_ADMIN"));
        verify(clubPosts, never()).deleteClubPostById(anyLong());
    }

    @Test
    void listOutcomesComeFromOneBatchRead() {
        AuditTask decided = task(AuditTask.STATUS_REJECTED, 1);
        AuditTask open = task(AuditTask.STATUS_PENDING, 0);
        open.setId(2L);
        when(events.selectByTargets(eq("AUDIT_TASK"), any())).thenReturn(Collections.singletonList(event(10L,
                "AUDIT_DECISION_REJECT", "{\"status\":2,\"version\":1,\"moderation\":{\"outcome\":\"PENDING\"}}")));

        Map<Long, String> outcomes = service.moderationOutcomes(Arrays.asList(decided, open));

        assertEquals("PENDING", outcomes.get(1L));
        assertNull(outcomes.get(2L), "未裁决任务没有投影结果");
        verify(events, times(1)).selectByTargets(eq("AUDIT_TASK"), any());
    }
}
