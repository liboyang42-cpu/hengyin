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
    void communityEditRefreshesExistingReviewWithoutReplacingItsSource() {
        AuditTask open = communityTask(2);
        open.setSourceType("report");
        open.setSubmitterId(200L);
        open.setSnapshotJson("old");
        when(auditTaskMapper.selectAuditTaskList(any())).thenReturn(Collections.singletonList(open));
        when(auditTaskMapper.selectAuditTaskByIdForUpdate(5L)).thenReturn(open);
        when(auditTaskMapper.refreshOpenAuditSnapshot(org.mockito.ArgumentMatchers.eq(5L),
                org.mockito.ArgumentMatchers.eq(AuditTask.BIZ_COMMUNITY_POST),
                org.mockito.ArgumentMatchers.eq("report"), org.mockito.ArgumentMatchers.eq("new"),
                org.mockito.ArgumentMatchers.eq(200L), any())).thenReturn(1);

        assertEquals(5L, svc.submitSafetyHold(AuditTask.BIZ_COMMUNITY_POST, 1L, 100L, "EDIT_REVIEW", "new"));
        verify(auditTaskMapper).refreshOpenAuditSnapshot(org.mockito.ArgumentMatchers.eq(5L),
                org.mockito.ArgumentMatchers.eq(AuditTask.BIZ_COMMUNITY_POST),
                org.mockito.ArgumentMatchers.eq("report"), org.mockito.ArgumentMatchers.eq("new"),
                org.mockito.ArgumentMatchers.eq(200L), any());
        verify(auditTaskMapper, never()).insertAuditTask(any());
    }

    @Test
    void failedCommunitySnapshotRefreshAbortsEdit() {
        AuditTask open = communityTask(2);
        open.setSnapshotJson("old");
        when(auditTaskMapper.selectAuditTaskList(any())).thenReturn(Collections.singletonList(open));
        when(auditTaskMapper.selectAuditTaskByIdForUpdate(5L)).thenReturn(open);
        org.junit.jupiter.api.Assertions.assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> svc.submitSafetyHold(AuditTask.BIZ_COMMUNITY_POST, 1L, 100L, "EDIT_REVIEW", "new"));
    }

    @Test
    void staleCommunityReviewCannotApproveNewSnapshot() {
        rejectCommunityReview(1);
    }

    @Test
    void unversionedCommunityReviewCannotApproveNewSnapshot() {
        rejectCommunityReview(null);
    }

    private void rejectCommunityReview(Integer expectedVersion) {
        AuditTask task = communityTask(2);
        when(auditTaskMapper.selectAuditTaskById(5L)).thenReturn(task);
        when(auditTaskMapper.selectAuditTaskByIdForUpdate(5L)).thenReturn(task);
        com.chengyinhub.business.mapper.CommunityPostMapper posts =
                mock(com.chengyinhub.business.mapper.CommunityPostMapper.class);
        com.chengyinhub.business.domain.community.CommunityPost post =
                new com.chengyinhub.business.domain.community.CommunityPost();
        post.setId(1L);
        when(posts.selectByIdForUpdate(1L)).thenReturn(post);
        ReflectionTestUtils.setField(svc, "communityPostMapper", posts);
        ReflectionTestUtils.setField(svc, "businessAuditEventMapper",
                mock(com.chengyinhub.business.mapper.BusinessAuditEventMapper.class));
        com.chengyinhub.business.domain.dto.AuditReviewDTO decision =
                new com.chengyinhub.business.domain.dto.AuditReviewDTO();
        decision.setExpectedVersion(expectedVersion);
        decision.setStatus(AuditTask.STATUS_APPROVED);
        assertEquals(409, org.junit.jupiter.api.Assertions.assertThrows(
                com.chengyinhub.common.exception.ServiceException.class,
                () -> svc.review(5L, 8L, decision)).getCode());
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(posts, auditTaskMapper);
        order.verify(posts).selectByIdForUpdate(1L);
        order.verify(auditTaskMapper).selectAuditTaskByIdForUpdate(5L);
        verify(auditTaskMapper, never()).updateAuditTaskReviewCas(any(), any(), any(), any(), any(), any());
    }

    private AuditTask communityTask(int version) {
        AuditTask task = new AuditTask();
        task.setId(5L); task.setBizId(1L); task.setBizType(AuditTask.BIZ_COMMUNITY_POST);
        task.setSourceType("auto_gate"); task.setStatus(AuditTask.STATUS_PENDING); task.setVersion(version);
        return task;
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
