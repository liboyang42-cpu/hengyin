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
class E02_24Test {
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
    void fastReplayToAnotherPositiveParentIsConflictBeforeAnyWrite() {
        when(mapper.selectCommentByAuthorRequest(9L, "synthetic-reply-1")).thenReturn(stored(17L));
        ServiceException error = assertThrows(ServiceException.class, () -> service.comment(9L, 7L, request(18L, "同意")));
        assertEquals(Integer.valueOf(409), error.getCode());
        verify(mapper, never()).selectCommentForReply(anyLong(), anyLong(), anyLong());
        verify(mapper, never()).insertComment(anyLong(), anyLong(), any(), any(), any(), anyString(), anyString(), anyString(), anyString(), any());
        noNewEffects();
    }

    @Test
    void topLevelAndPositiveReplyAreDifferentTargetsInBothDirections() {
        for (Long[] pair : new Long[][]{{null, 17L}, {17L, null}, {0L, 17L}, {17L, -1L}}) {
            setup();
            when(mapper.selectCommentByAuthorRequest(9L, "synthetic-reply-1")).thenReturn(stored(pair[0]));
            ServiceException error = assertThrows(ServiceException.class, () -> service.comment(9L, 7L, request(pair[1], "同意")));
            assertEquals(Integer.valueOf(409), error.getCode());
            verify(mapper, never()).insertComment(anyLong(), anyLong(), any(), any(), any(), anyString(), anyString(), anyString(), anyString(), any());
            noNewEffects();
        }
    }

    @Test
    void sameParentAndTrimmedBodyReplaySucceedsWithoutRecheckingHistoricalParent() {
        when(mapper.selectCommentByAuthorRequest(9L, "synthetic-reply-1")).thenReturn(stored(17L));
        CommunityPostRequests.Comment request = request(17L, "  同意  ");
        request.setRootId(999L);
        request.setRepliedToMemberId(888L);
        Map<String, Object> result = service.comment(9L, 7L, request);
        assertEquals("PUBLISHED", result.get("lifecycle"));
        assertEquals("VISIBLE", result.get("approvalState"));
        verify(mapper, never()).selectCommentForReply(anyLong(), anyLong(), anyLong());
        verify(mapper, never()).insertComment(anyLong(), anyLong(), any(), any(), any(), anyString(), anyString(), anyString(), anyString(), any());
        noNewEffects();
    }

    @Test
    void nullZeroAndNegativeTopLevelRepresentationsKeepTheirExistingReplayCompatibility() {
        for (Long storedParent : new Long[]{null, 0L, -2L}) {
            for (Long requestedParent : new Long[]{null, 0L, -1L}) {
                setup();
                when(mapper.selectCommentByAuthorRequest(9L, "synthetic-reply-1")).thenReturn(stored(storedParent));
                assertEquals("PUBLISHED", service.comment(9L, 7L, request(requestedParent, "同意")).get("lifecycle"));
                verify(mapper, never()).selectCommentForReply(anyLong(), anyLong(), anyLong());
                verify(mapper, never()).insertComment(anyLong(), anyLong(), any(), any(), any(), anyString(), anyString(), anyString(), anyString(), any());
                noNewEffects();
            }
        }
    }

    @Test
    void duplicateKeyRecoveryAcceptsOnlyTheSameParentAndDoesNotRepeatSideEffects() {
        for (Long storedParent : new Long[]{18L, 17L}) {
            setup();
            Map<String, Object> parent = new HashMap<>();
            parent.put("id", 18L);
            parent.put("author_id", 12L);
            when(mapper.selectCommentForReply(18L, 7L, 9L)).thenReturn(parent);
            when(mapper.selectCommentByAuthorRequest(9L, "synthetic-reply-1")).thenReturn(null, stored(storedParent));
            when(mapper.insertComment(eq(7L), eq(9L), eq(18L), eq(18L), eq(12L),
                    eq("synthetic-reply-1"), eq("同意"), eq("CHECKING"), eq("VISIBLE"), any()))
                    .thenThrow(new DuplicateKeyException("synthetic insert race at SQL boundary"));
            if (storedParent.equals(18L)) {
                assertEquals("PUBLISHED", service.comment(9L, 7L, request(18L, " 同意 ")).get("lifecycle"));
            } else {
                ServiceException error = assertThrows(ServiceException.class, () -> service.comment(9L, 7L, request(18L, " 同意 ")));
                assertEquals(Integer.valueOf(409), error.getCode());
            }
            verify(mapper).insertComment(eq(7L), eq(9L), eq(18L), eq(18L), eq(12L),
                    eq("synthetic-reply-1"), eq("同意"), eq("CHECKING"), eq("VISIBLE"), any());
            noNewEffects();
        }
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
