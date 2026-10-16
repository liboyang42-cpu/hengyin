package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.domain.dto.CommunityPostRequests;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.service.IContentSafetyGate;
import com.chengyinhub.business.service.impl.CommunityPostServiceImpl;
import com.chengyinhub.business.service.support.CommunityPostLifecyclePolicy;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN: deterministic service replay branches; mocked SQL boundary is not real DB concurrency. */
class E02_25Test {
    private CommunityPostMapper mapper;
    private IContentSafetyGate safety;
    private CommunityPostServiceImpl service;

    @BeforeEach
    void setup() {
        mapper = mock(CommunityPostMapper.class);
        safety = mock(IContentSafetyGate.class);
        service = new CommunityPostServiceImpl(mapper, new CommunityPostLifecyclePolicy(), safety, null, null, null);
        CommunityPost post = new CommunityPost();
        post.setId(7L);
        post.setAuthorId(11L);
        post.setLifecycle("PUBLISHED");
        post.setAudience("PUBLIC");
        post.setCommentPolicy("EVERYONE");
        post.setInteractionEnabled(1);
        post.setSlowModeSeconds(0);
        when(mapper.selectById(7L, 9L)).thenReturn(post);
    }

    @Test
    void duplicateRecoveryUsesCurrentWinnerForTheSameRequest() {
        prepare(stored(17L));
        assertEquals("PUBLISHED", service.comment(9L, 7L, request(17L, " 同意 ")).get("lifecycle"));
        verify(mapper).selectCommentByAuthorRequest(9L, "synthetic-reply-1");
        verify(mapper).selectCommentByAuthorRequestForUpdate(9L, "synthetic-reply-1");
        noNewEffects();
    }

    @Test
    void currentWinnerStillRejectsDifferentPostBodyOrParent() {
        for (int difference = 0; difference < 3; difference++) {
            setup();
            Map<String, Object> winner = stored(17L);
            if (difference == 0) winner.put("post_id", 8L);
            if (difference == 1) winner.put("body", "另一正文");
            if (difference == 2) winner.put("parent_id", 18L);
            prepare(winner);
            ServiceException error = assertThrows(ServiceException.class, () -> service.comment(9L, 7L, request(17L, "同意")));
            assertEquals(Integer.valueOf(409), error.getCode());
            verify(mapper).selectCommentByAuthorRequestForUpdate(9L, "synthetic-reply-1");
            noNewEffects();
        }
    }

    @Test
    void absentCurrentWinnerFailsClosedAfterDuplicateKey() {
        prepare(null);
        ServiceException error = assertThrows(ServiceException.class, () -> service.comment(9L, 7L, request(17L, "同意")));
        assertEquals(Integer.valueOf(409), error.getCode());
        verify(mapper).selectCommentByAuthorRequestForUpdate(9L, "synthetic-reply-1");
        noNewEffects();
    }

    @Test
    void currentReadPreservesNormalizedTopLevelAndTrimmedBodyCompatibility() {
        for (Long storedParent : new Long[]{null, 0L, -2L}) {
            for (Long requestedParent : new Long[]{null, 0L, -1L}) {
                setup();
                prepare(stored(storedParent));
                assertEquals("PUBLISHED", service.comment(9L, 7L, request(requestedParent, " 同意 ")).get("lifecycle"));
                verify(mapper).selectCommentByAuthorRequestForUpdate(9L, "synthetic-reply-1");
                noNewEffects();
            }
        }
    }

    private void prepare(Map<String, Object> winner) {
        when(mapper.selectCommentByAuthorRequest(9L, "synthetic-reply-1")).thenReturn(null);
        when(mapper.selectCommentByAuthorRequestForUpdate(9L, "synthetic-reply-1")).thenReturn(winner);
        Map<String, Object> parent = new HashMap<>();
        parent.put("id", 17L);
        parent.put("author_id", 12L);
        when(mapper.selectCommentForReply(17L, 7L, 9L)).thenReturn(parent);
        when(mapper.insertComment(eq(7L), eq(9L), any(), any(), any(), eq("synthetic-reply-1"),
                eq("同意"), eq("CHECKING"), eq("VISIBLE"), any())).thenThrow(new DuplicateKeyException("synthetic SQL boundary"));
    }

    private CommunityPostRequests.Comment request(Long parent, String body) {
        CommunityPostRequests.Comment request = new CommunityPostRequests.Comment();
        request.setClientRequestId("synthetic-reply-1");
        request.setParentId(parent);
        request.setBody(body);
        return request;
    }
    private Map<String, Object> stored(Long parent) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 30L);
        row.put("post_id", 7L);
        row.put("body", "同意");
        row.put("parent_id", parent);
        row.put("author_approval_state", "VISIBLE");
        row.put("lifecycle", "PUBLISHED");
        return row;
    }
    private void noNewEffects() {
        verifyNoInteractions(safety);
        verify(mapper, never()).updateCommentLifecycle(anyLong(), anyString(), anyString(), anyInt(), any());
        verify(mapper, never()).recomputeCounts(anyLong());
        verify(mapper, never()).insertOutbox(anyString(), anyLong(), anyString(), anyString(), any());
        verify(mapper, never()).insertTypedOutbox(anyString(), anyString(), anyLong(), anyString(), anyString(), any());
    }
}
