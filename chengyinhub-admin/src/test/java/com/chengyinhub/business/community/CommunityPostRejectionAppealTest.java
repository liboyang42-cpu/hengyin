package com.chengyinhub.business.community;

import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.domain.dto.CommunityPostRequests;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.service.CommunityTrustService;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICommunityMediaReceiptService;
import com.chengyinhub.business.service.IContentSafetyGate;
import com.chengyinhub.business.service.impl.CommunityPostServiceImpl;
import com.chengyinhub.business.service.support.CommunityPostLifecyclePolicy;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUDIT-G3-1:人审驳回把帖子置 REMOVED 后,作者既删不掉也申诉不了。
 * 修复后:作者可删除被下架的帖子(数据权利);驳回落一条 REMOVE 处置,作者按处置 id 走既有申诉,撤销后帖子恢复公开。
 */
class CommunityPostRejectionAppealTest {
    private static final long AUTHOR = 9L;
    private static final long POST = 7L;
    private static final long GUIDELINE = 5L;

    private CommunityPostMapper mapper;
    private CommunityPostServiceImpl service;
    private final CommunityPostLifecyclePolicy policy = new CommunityPostLifecyclePolicy();

    @BeforeEach
    void setUp() {
        mapper = mock(CommunityPostMapper.class);
        service = new CommunityPostServiceImpl(mapper, policy, mock(IContentSafetyGate.class),
                mock(IAuditTaskService.class), mock(CommunityTrustService.class),
                mock(ICommunityMediaReceiptService.class));
        when(mapper.selectCommandReceipt(anyLong(), anyString())).thenReturn(null);
        when(mapper.insertCommandReceipt(anyLong(), anyString(), anyString(), anyLong(), anyString(), any()))
                .thenReturn(1);
        when(mapper.insertOutbox(anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.insertTypedOutbox(anyString(), anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
    }

    @Test
    void authorMayDeleteARemovedPostButOthersMayNot() {
        CommunityPost removed = post("REMOVED", 4);

        assertEquals("DELETED", policy.decide(removed, CommunityPostLifecyclePolicy.ACTION_DELETE, 4, true).getLifecycle());
        assertEquals(Integer.valueOf(403), assertThrows(ServiceException.class,
                () -> policy.decide(removed, CommunityPostLifecyclePolicy.ACTION_DELETE, 4, false)).getCode());
    }

    @Test
    void deletingOwnRemovedPostRunsTheNormalErasure() {
        when(mapper.selectByIdForUpdate(POST)).thenReturn(post("REMOVED", 4));
        when(mapper.transitionCas(eq(POST), eq("REMOVED"), eq("DELETED"), eq(4), isNull(), any(), eq(0), eq(0), eq(0), any()))
                .thenReturn(1);
        when(mapper.insertErasureJob(isNull(), eq(POST), eq("POST_DELETE"), anyString(), anyString(), any())).thenReturn(1);
        CommunityPostRequests.State state = new CommunityPostRequests.State();
        state.setRequestId("delete-removed-post-1");
        state.setExpectedVersion(4);

        service.delete(AUTHOR, POST, state);

        verify(mapper).transitionCas(eq(POST), eq("REMOVED"), eq("DELETED"), eq(4), isNull(), any(), eq(0), eq(0), eq(0), any());
        verify(mapper).upsertSearchDocument(eq(POST), eq("DELETED"), any());
    }

    @Test
    void rejectingACheckingPostRecordsAnAppealableRemoveEnforcement() {
        when(mapper.selectByIdForUpdate(POST)).thenReturn(post("CHECKING", 3));
        when(mapper.transitionCas(eq(POST), eq("CHECKING"), eq("REMOVED"), eq(3), isNull(), isNull(), eq(0), eq(0), eq(0), any()))
                .thenReturn(1);

        service.applyAuditDecision(POST, false);

        ArgumentCaptor<String> evidence = ArgumentCaptor.forClass(String.class);
        verify(mapper).insertEnforcement(eq(POST), isNull(), eq("REMOVE"), eq("AUDIT_REVIEW_REJECTED"), eq(GUIDELINE),
                eq("REVIEW_REJECTED"), evidence.capture(), eq("PUBLISHED"), eq(0L), any(), isNull(),
                eq("AUDIT_REJECT:7:3"), any());
        assertTrue(evidence.getValue().contains("AUDIT_REVIEW_REJECTED"));
        assertTrue(evidence.getValue().contains("CHECKING"));
    }

    @Test
    void rejectingALimitedPostRestoresLimitedOnReversal() {
        when(mapper.selectByIdForUpdate(POST)).thenReturn(post("LIMITED", 3));
        when(mapper.transitionCas(eq(POST), eq("LIMITED"), eq("REMOVED"), eq(3), isNull(), isNull(), eq(0), eq(0), eq(0), any()))
                .thenReturn(1);

        service.applyAuditDecision(POST, false);

        verify(mapper).insertEnforcement(eq(POST), isNull(), eq("REMOVE"), anyString(), eq(GUIDELINE), anyString(),
                anyString(), eq("LIMITED"), eq(0L), any(), isNull(), anyString(), any());
    }

    @Test
    void authorCanAppealTheRejectionEnforcement() {
        Map<String, Object> enforcement = new HashMap<>();
        enforcement.put("id", 31L);
        enforcement.put("post_id", POST);
        enforcement.put("target_member_id", null);
        when(mapper.selectEnforcement(31L)).thenReturn(enforcement);
        when(mapper.selectByIdForUpdate(POST)).thenReturn(post("REMOVED", 4));
        CommunityPostRequests.Appeal appeal = new CommunityPostRequests.Appeal();
        appeal.setEnforcementId(31L);
        appeal.setReason("内容并不违规，请复核");
        appeal.setRequestId("appeal-rejection-1");

        service.appeal(AUTHOR, appeal);

        verify(mapper).insertAppeal(eq(31L), eq(AUTHOR), eq("内容并不违规，请复核"), anyString(),
                eq("appeal-rejection-1"), any(), any());
    }

    @Test
    void reversedRejectionAppealRepublishesThePost() {
        Map<String, Object> appeal = new HashMap<>();
        appeal.put("enforcement_id", 31L);
        appeal.put("appellant_member_id", AUTHOR);
        when(mapper.selectAppeal(61L)).thenReturn(appeal);
        Map<String, Object> enforcement = new HashMap<>();
        enforcement.put("id", 31L);
        enforcement.put("post_id", POST);
        enforcement.put("version", 0);
        when(mapper.selectEnforcement(31L)).thenReturn(enforcement);
        when(mapper.reviewAppealCas(eq(61L), eq("REVERSED"), anyString(), eq(2L), eq(0), any())).thenReturn(1);
        when(mapper.reverseEnforcement(eq(31L), eq(0), any())).thenReturn(1);
        when(mapper.selectByIdForUpdate(POST)).thenReturn(post("REMOVED", 4));
        when(mapper.selectActivePostEnforcementTypes(eq(POST), any())).thenReturn(Collections.emptyList());
        when(mapper.selectEnforcementRestoreLifecycle(POST)).thenReturn("PUBLISHED");
        when(mapper.reconcilePostEnforcementCas(eq(POST), eq(4), eq("PUBLISHED"), eq(1), eq(1), eq(1), any())).thenReturn(1);
        CommunityPostRequests.AppealReview review = new CommunityPostRequests.AppealReview();
        review.setDecision("REVERSED");
        review.setReason("复核后不违规");
        review.setExpectedVersion(0);

        service.reviewAppeal(2L, 61L, review);

        verify(mapper).reconcilePostEnforcementCas(eq(POST), eq(4), eq("PUBLISHED"), eq(1), eq(1), eq(1), any());
        verify(mapper).upsertSearchDocument(eq(POST), eq("VISIBLE"), any());
    }

    private CommunityPost post(String lifecycle, int version) {
        CommunityPost post = new CommunityPost();
        post.setId(POST);
        post.setAuthorId(AUTHOR);
        post.setLifecycle(lifecycle);
        post.setVersion(version);
        post.setGuidelineVersionId(GUIDELINE);
        return post;
    }
}
