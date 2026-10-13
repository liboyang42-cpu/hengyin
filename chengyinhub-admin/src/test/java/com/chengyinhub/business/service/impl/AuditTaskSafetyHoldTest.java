package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.service.support.CommunityPostAuditSnapshot;
import com.chengyinhub.common.exception.ServiceException;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
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
    private CommunityPostMapper posts;
    private AuditRecordMapper records;
    private CommunityPost post;
    private AuditTask communityTask;

    @BeforeEach
    void setUp() {
        auditTaskMapper = mock(AuditTaskMapper.class);
        svc = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(svc, "auditTaskMapper", auditTaskMapper);
        posts = mock(CommunityPostMapper.class); records = mock(AuditRecordMapper.class);
        ReflectionTestUtils.setField(svc, "communityPostMapper", posts);
        ReflectionTestUtils.setField(svc, "auditRecordMapper", records);
        post = new CommunityPost(); post.setId(7L); post.setAuthorId(9L); post.setVersion(4);
        post.setLifecycle("CHECKING"); post.setBody("new content");
        when(posts.selectByIdForUpdate(7L)).thenReturn(post);
        when(records.insertAuditRecord(any())).thenReturn(1);
        communityTask = new AuditTask(); communityTask.setId(19L); communityTask.setBizType(AuditTask.BIZ_COMMUNITY_POST);
        communityTask.setBizId(7L); communityTask.setSubmitterId(9L); communityTask.setSourceType("auto_gate");
        communityTask.setStatus(0); communityTask.setVersion(2); communityTask.setSnapshotJson("legacy snapshot");
        when(auditTaskMapper.selectOpenAuditTaskForUpdate(AuditTask.BIZ_COMMUNITY_POST, 7L)).thenReturn(communityTask);
        when(auditTaskMapper.refreshCommunitySafetySnapshotCas(eq(19L), eq(2), eq(9L), anyString(), anyString(), any())).thenReturn(1);
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

    @Test
    void communityEditRefreshesLegacyOpenSnapshotWithVersionCasAndSubmissionRecord() {
        String snapshot = CommunityPostAuditSnapshot.capture(post, 4, posts);
        assertEquals(19L, svc.submitSafetyHold(AuditTask.BIZ_COMMUNITY_POST, 7L, 9L, "EDIT_REVIEW", snapshot));
        org.mockito.InOrder order = inOrder(posts, auditTaskMapper, records);
        order.verify(posts).selectByIdForUpdate(7L);
        order.verify(auditTaskMapper).selectOpenAuditTaskForUpdate(AuditTask.BIZ_COMMUNITY_POST, 7L);
        order.verify(auditTaskMapper).refreshCommunitySafetySnapshotCas(eq(19L), eq(2), eq(9L), eq("EDIT_REVIEW"), eq(snapshot), any());
        order.verify(records).insertAuditRecord(argThat(row -> "SUBMIT".equals(row.getAction()) && snapshot.equals(row.getSnapshotJson())));
        verify(auditTaskMapper, never()).insertAuditTask(any());
    }

    @Test
    void communityOpenOtherSourceIsRejectedInsteadOfReusedOrOverwritten() {
        communityTask.setSourceType("report");
        ServiceException error = assertThrows(ServiceException.class, () -> svc.submitSafetyHold(
                AuditTask.BIZ_COMMUNITY_POST, 7L, 9L, "EDIT_REVIEW", CommunityPostAuditSnapshot.capture(post, 4, posts)));
        assertEquals(409, error.getCode());
        verify(auditTaskMapper, never()).refreshCommunitySafetySnapshotCas(anyLong(), anyInt(), anyLong(), anyString(), anyString(), any());
        verify(records, never()).insertAuditRecord(any());
    }

    @Test
    void communityRefreshCasFailureRejectsTheSubmission() {
        when(auditTaskMapper.refreshCommunitySafetySnapshotCas(anyLong(), anyInt(), anyLong(), anyString(), anyString(), any())).thenReturn(0);
        assertEquals(409, assertThrows(ServiceException.class, () -> svc.submitSafetyHold(AuditTask.BIZ_COMMUNITY_POST,
                7L, 9L, "EDIT_REVIEW", CommunityPostAuditSnapshot.capture(post, 4, posts))).getCode());
        verify(records, never()).insertAuditRecord(any());
    }

    @Test
    void communityHoldRejectsUnboundSnapshotWrongOwnerAndWrongPostVersion() {
        assertThrows(ServiceException.class, () -> svc.submitSafetyHold(AuditTask.BIZ_COMMUNITY_POST, 7L, 9L, "NORMAL", "{}"));
        assertThrows(ServiceException.class, () -> svc.submitSafetyHold(AuditTask.BIZ_COMMUNITY_POST, 7L, 10L, "NORMAL",
                CommunityPostAuditSnapshot.capture(post, 4, posts)));
        assertThrows(ServiceException.class, () -> svc.submitSafetyHold(AuditTask.BIZ_COMMUNITY_POST, 7L, 9L, "NORMAL",
                CommunityPostAuditSnapshot.capture(post, 3, posts)));
        verify(auditTaskMapper, never()).selectOpenAuditTaskForUpdate(anyString(), anyLong());
    }

    @Test
    void initialPublishHoldBindsFollowingVersionAndCreatesSubmissionRecord() {
        post.setLifecycle("DRAFT"); post.setVersion(3);
        when(auditTaskMapper.selectOpenAuditTaskForUpdate(AuditTask.BIZ_COMMUNITY_POST, 7L)).thenReturn(null);
        when(auditTaskMapper.insertAuditTask(any())).thenAnswer(inv -> {
            AuditTask created = inv.getArgument(0); created.setId(20L); return 1;
        });
        String snapshot = CommunityPostAuditSnapshot.capture(post, 4, posts);
        assertEquals(20L, svc.submitSafetyHold(AuditTask.BIZ_COMMUNITY_POST, 7L, 9L, "NORMAL", snapshot));
        verify(auditTaskMapper).insertAuditTask(argThat(row -> row.getVersion() == 0 && row.getStatus() == 0
                && snapshot.equals(row.getSnapshotJson())));
        verify(records).insertAuditRecord(argThat(row -> row.getTaskId() == 20L && "SUBMIT".equals(row.getAction())));
    }

    @Test
    void concurrentOpenInsertUsesSameGuardedRefreshInsteadOfReturningStaleId() {
        when(auditTaskMapper.selectOpenAuditTaskForUpdate(AuditTask.BIZ_COMMUNITY_POST, 7L)).thenReturn(null, communityTask);
        when(auditTaskMapper.insertAuditTask(any())).thenThrow(new org.springframework.dao.DuplicateKeyException("open guard"));
        String snapshot = CommunityPostAuditSnapshot.capture(post, 4, posts);
        assertEquals(19L, svc.submitSafetyHold(AuditTask.BIZ_COMMUNITY_POST, 7L, 9L, "NORMAL", snapshot));
        verify(auditTaskMapper).refreshCommunitySafetySnapshotCas(eq(19L), eq(2), eq(9L), eq("NORMAL"), eq(snapshot), any());
    }

    @Test
    void communitySubmissionRecordFailureRejectsTheWholeWrite() {
        when(records.insertAuditRecord(any())).thenReturn(0);
        assertEquals(409, assertThrows(ServiceException.class, () -> svc.submitSafetyHold(AuditTask.BIZ_COMMUNITY_POST,
                7L, 9L, "NORMAL", CommunityPostAuditSnapshot.capture(post, 4, posts))).getCode());
    }
}
