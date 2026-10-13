package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.AuditRecord;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.service.ICommunityPostService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.support.CommunityPostAuditSnapshot;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuditCommunityPostDecisionVersionTest {
    private AuditTaskServiceImpl service;
    private AuditTaskMapper tasks;
    private AuditRecordMapper records;
    private BusinessAuditEventMapper events;
    private CommunityPostMapper posts;
    private ICommunityPostService callback;
    private CommunityPost post;
    private AuditTask task;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        service = new AuditTaskServiceImpl();
        tasks = mock(AuditTaskMapper.class); records = mock(AuditRecordMapper.class);
        events = mock(BusinessAuditEventMapper.class); posts = mock(CommunityPostMapper.class);
        callback = mock(ICommunityPostService.class);
        ReflectionTestUtils.setField(service, "auditTaskMapper", tasks);
        ReflectionTestUtils.setField(service, "auditRecordMapper", records);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", events);
        ReflectionTestUtils.setField(service, "communityPostMapper", posts);
        ObjectProvider<ICommunityPostService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(callback);
        ReflectionTestUtils.setField(service, "communityPostServiceProvider", provider);
        ObjectProvider<IImService> im = mock(ObjectProvider.class);
        when(im.getObject()).thenReturn(mock(IImService.class));
        ReflectionTestUtils.setField(service, "imServiceProvider", im);
        post = new CommunityPost(); post.setId(7L); post.setAuthorId(9L);
        post.setVersion(4); post.setLifecycle("CHECKING"); post.setBody("reviewed body");
        task = new AuditTask(); task.setId(19L); task.setBizType(AuditTask.BIZ_COMMUNITY_POST);
        task.setBizId(7L); task.setSubmitterId(9L); task.setSourceType("auto_gate");
        task.setVersion(2); task.setStatus(AuditTask.STATUS_PENDING);
        task.setSnapshotJson(CommunityPostAuditSnapshot.capture(post, 4, posts));
        when(posts.selectByIdForUpdate(7L)).thenReturn(post);
        when(tasks.selectAuditTaskById(19L)).thenReturn(task);
        when(tasks.selectAuditTaskByIdForUpdate(19L)).thenReturn(task);
        when(tasks.updateAuditTaskReviewCas(eq(19L), eq(0), anyInt(), eq(1L), eq(2), any())).thenReturn(1);
        when(records.insertAuditRecord(any())).thenReturn(1);
        when(events.insertBusinessAuditEvent(any())).thenReturn(1);
    }

    @Test
    void currentApprovalLocksPostThenTaskAndAppliesExactlyReviewedVersion() {
        assertEquals(1, service.review(19L, 1L, decision(2, 1)));
        InOrder order = inOrder(posts, tasks, callback);
        order.verify(tasks).selectAuditTaskById(19L);
        order.verify(posts).selectByIdForUpdate(7L);
        order.verify(tasks).selectAuditTaskByIdForUpdate(19L);
        order.verify(tasks).updateAuditTaskReviewCas(eq(19L), eq(0), eq(1), eq(1L), eq(2), any());
        order.verify(callback).applyAuditDecision(7L, true, 4);
        verify(records).insertAuditRecord(argThat(record -> "APPROVE".equals(record.getAction())
                && task.getSnapshotJson().equals(record.getSnapshotJson())));
        verify(events).insertBusinessAuditEvent(argThat(event -> "AUDIT_DECISION_APPROVE".equals(event.getAction())));
    }

    @Test
    void currentRejectionAppliesExactlyReviewedVersion() {
        assertEquals(1, service.review(19L, 1L, decision(2, 2)));
        verify(callback).applyAuditDecision(7L, false, 4);
        verify(records).insertAuditRecord(argThat(record -> "REJECT".equals(record.getAction())));
    }

    @Test
    void missingOrStaleTaskVersionCannotApproveNewSnapshot() {
        assertConflict(decision(null, 1));
        assertConflict(decision(1, 1));
        verifyNoDecision();
    }

    @Test
    void oldPostSnapshotCannotApproveEvenWithCurrentTaskVersion() {
        task.setSnapshotJson(CommunityPostAuditSnapshot.capture(post, 3, posts));
        assertConflict(decision(2, 1));
        verifyNoDecision();
    }

    @Test
    void legacySnapshotCannotBeFilledFromCurrentContent() {
        task.setSnapshotJson("{\"post\":{\"id\":7,\"version\":4},\"media\":[]}");
        assertConflict(decision(2, 1));
        verifyNoDecision();
    }

    @Test
    void bodyAndEachAuthorRelationDriftAreRejectedAtSameVersion() {
        String submitted = task.getSnapshotJson();
        post.setBody("changed without version"); assertConflict(decision(2, 1)); post.setBody("reviewed body");
        when(posts.selectTopicCodes(7L)).thenReturn(Collections.singletonList("CHANGED"));
        assertConflict(decision(2, 1)); when(posts.selectTopicCodes(7L)).thenReturn(Collections.emptyList());
        when(posts.selectMentionedMemberIds(7L)).thenReturn(Collections.singletonList(21L));
        assertConflict(decision(2, 1)); when(posts.selectMentionedMemberIds(7L)).thenReturn(Collections.emptyList());
        when(posts.selectReferences(7L)).thenReturn(Collections.singletonList(Collections.singletonMap("reference_id", 8L)));
        assertConflict(decision(2, 1)); when(posts.selectReferences(7L)).thenReturn(Collections.emptyList());
        when(posts.selectMediaForSafety(7L)).thenReturn(Collections.singletonList(Collections.singletonMap("id", 22L)));
        assertConflict(decision(2, 1));
        assertEquals(submitted, task.getSnapshotJson());
        verifyNoDecision();
    }

    @Test
    void readModelsAndMediaReviewProgressDoNotInvalidateContent() {
        Map<String,Object> media = new HashMap<>(); media.put("id", 22L); media.put("review_state", "PENDING");
        when(posts.selectMediaForSafety(7L)).thenReturn(Collections.singletonList(media));
        task.setSnapshotJson(CommunityPostAuditSnapshot.capture(post, 4, posts));
        media.put("review_state", "HOLD"); post.setLikeCount(99); post.setAuthorNickname("renamed");
        post.setUpdateTime(new java.util.Date());
        assertEquals(1, service.review(19L, 1L, decision(2, 1)));
        verify(callback).applyAuditDecision(7L, true, 4);
    }

    @Test
    void changedTaskBindingSourceOwnerOrLifecycleFailsClosed() {
        AuditTask moved = new AuditTask(); moved.setBizType(AuditTask.BIZ_COMMUNITY_POST); moved.setBizId(8L);
        when(tasks.selectAuditTaskByIdForUpdate(19L)).thenReturn(moved);
        assertConflict(decision(2, 1)); when(tasks.selectAuditTaskByIdForUpdate(19L)).thenReturn(task);
        task.setSourceType("report"); assertConflict(decision(2, 1)); task.setSourceType("auto_gate");
        task.setSubmitterId(10L); assertConflict(decision(2, 1)); task.setSubmitterId(9L);
        for (String state : new String[] {"REMOVED", "DELETED", "PUBLISHED", "DRAFT"}) {
            post.setLifecycle(state); assertConflict(decision(2, 1));
        }
        verifyNoDecision();
    }

    @Test
    void failedTaskCasCannotWriteApprovalRecordOrInvokeCallback() {
        when(tasks.updateAuditTaskReviewCas(eq(19L), eq(0), anyInt(), eq(1L), eq(2), any())).thenReturn(0);
        assertConflict(decision(2, 1));
        verify(records, never()).insertAuditRecord(any()); verifyNoInteractions(callback);
    }

    @Test
    void exactReviewReplayDoesNotApplyDecisionToLaterEditedContent() {
        AtomicReference<BusinessAuditEvent> saved = new AtomicReference<>();
        when(events.insertBusinessAuditEvent(any())).thenAnswer(inv -> { saved.set(inv.getArgument(0)); return 1; });
        AuditReviewDTO request = decision(2, 1);
        assertEquals(1, service.review(19L, 1L, request));
        when(events.selectByDomainActionRequestForUpdate(eq("PLATFORM"), eq(19L), eq("AUDIT_DECISION_APPROVE"), anyString()))
                .thenAnswer(inv -> saved.get());
        post.setVersion(6); post.setBody("new edit awaiting review"); task.setVersion(4);
        assertEquals(1, service.review(19L, 1L, request));
        verify(callback, times(1)).applyAuditDecision(7L, true, 4);
        verify(events, times(1)).insertBusinessAuditEvent(any());
    }

    private AuditReviewDTO decision(Integer version, int status) {
        AuditReviewDTO request = new AuditReviewDTO(); request.setExpectedVersion(version); request.setStatus(status);
        if (status == 2) request.setReasonText("需要修改内容");
        return request;
    }

    private void assertConflict(AuditReviewDTO request) {
        assertEquals(409, assertThrows(ServiceException.class, () -> service.review(19L, 1L, request)).getCode());
    }

    private void verifyNoDecision() {
        verify(tasks, never()).updateAuditTaskReviewCas(anyLong(), anyInt(), anyInt(), anyLong(), anyInt(), any());
        verify(records, never()).insertAuditRecord(any()); verifyNoInteractions(callback);
    }
}
