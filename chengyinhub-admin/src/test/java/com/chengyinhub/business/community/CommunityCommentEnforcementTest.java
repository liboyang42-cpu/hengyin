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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUDIT-G3-6 评论处置 + AUDIT-G3-5 举报→处置桥接(帖文服务侧)。
 * 原实现处置只认帖文 id、处置表没有 comment_id,被举报的评论无法下架;举报单与处置行也没有关联。
 */
class CommunityCommentEnforcementTest {
    private static final long OPERATOR = 5L;
    private static final long POST = 7L;
    private static final long POST_AUTHOR = 9L;
    private static final long COMMENT = 55L;
    private static final long COMMENT_AUTHOR = 12L;
    private static final long REPORT = 88L;

    private CommunityPostMapper mapper;
    private CommunityPostServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(CommunityPostMapper.class);
        service = new CommunityPostServiceImpl(mapper, new CommunityPostLifecyclePolicy(), mock(IContentSafetyGate.class),
                mock(IAuditTaskService.class), mock(CommunityTrustService.class),
                mock(ICommunityMediaReceiptService.class));
        when(mapper.selectCommandReceipt(anyLong(), anyString())).thenReturn(null);
        when(mapper.insertCommandReceipt(anyLong(), anyString(), anyString(), anyLong(), anyString(), any()))
                .thenReturn(1);
        when(mapper.insertOutbox(anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.insertTypedOutbox(anyString(), anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.countGuidelineVersion(anyLong())).thenReturn(1);
        when(mapper.countGuidelineRule(anyLong(), anyString())).thenReturn(1);
    }

    @Test
    void reportedCommentCanBeTakenDownAndTheEnforcementPointsAtIt() {
        when(mapper.selectCommentForAudit(COMMENT)).thenReturn(comment("PUBLISHED", 2));
        when(mapper.updateCommentLifecycle(eq(COMMENT), eq("PUBLISHED"), eq("REMOVED"), eq(2), any())).thenReturn(1);
        when(mapper.selectEnforcementByRequest(OPERATOR, "comment-remove-1")).thenReturn(stored(41L));
        when(mapper.linkEnforcement(41L, REPORT, COMMENT)).thenReturn(1);

        Long id = service.enforceReportedTarget(OPERATOR, REPORT, "COMMENT", COMMENT, request("REMOVE", "comment-remove-1"));

        assertEquals(Long.valueOf(41L), id);
        // post_id 留空:帖文级生效/恢复查询按 post_id 走,评论处置不会把整帖下架;target 指向评论作者以便其申诉
        verify(mapper).insertEnforcement(isNull(), eq(COMMENT_AUTHOR), eq("REMOVE"), eq("SAFETY"), eq(5L),
                eq("HARM"), eq("{\"snapshotId\":55}"), eq("PUBLISHED"), eq(OPERATOR), any(), isNull(),
                eq("comment-remove-1"), any());
        verify(mapper).linkEnforcement(41L, REPORT, COMMENT);
        verify(mapper).recomputeCounts(POST);
        verify(mapper, never()).reconcilePostEnforcementCas(anyLong(), anyInt(), anyString(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void directCommentEnforcementHasNoReportLink() {
        when(mapper.selectCommentForAudit(COMMENT)).thenReturn(comment("PUBLISHED", 2));
        when(mapper.updateCommentLifecycle(eq(COMMENT), eq("PUBLISHED"), eq("REMOVED"), eq(2), any())).thenReturn(1);
        when(mapper.selectEnforcementByRequest(OPERATOR, "comment-remove-2")).thenReturn(stored(42L));
        when(mapper.linkEnforcement(42L, null, COMMENT)).thenReturn(1);

        assertEquals(Long.valueOf(42L), service.enforceComment(OPERATOR, COMMENT, request("REMOVE", "comment-remove-2")));
        verify(mapper).linkEnforcement(42L, null, COMMENT);
    }

    @Test
    void commentEnforcementOnlySupportsRemoveOfAPublishedComment() {
        assertEquals(Integer.valueOf(400), assertThrows(ServiceException.class,
                () -> service.enforceComment(OPERATOR, COMMENT, request("DOWNRANK", "comment-downrank"))).getCode());
        when(mapper.selectCommentForAudit(COMMENT)).thenReturn(comment("CHECKING", 1));
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class,
                () -> service.enforceComment(OPERATOR, COMMENT, request("REMOVE", "comment-checking"))).getCode());
        verify(mapper, never()).updateCommentLifecycle(anyLong(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void commentAuthorCanAppealAndReversalRestoresTheComment() {
        Map<String, Object> enforcement = stored(41L);
        enforcement.put("post_id", null);
        enforcement.put("comment_id", COMMENT);
        enforcement.put("target_member_id", COMMENT_AUTHOR);
        enforcement.put("version", 0);
        when(mapper.selectEnforcement(41L)).thenReturn(enforcement);
        CommunityPostRequests.Appeal appeal = new CommunityPostRequests.Appeal();
        appeal.setEnforcementId(41L);
        appeal.setReason("评论没有违规");
        appeal.setRequestId("comment-appeal-1");
        service.appeal(COMMENT_AUTHOR, appeal);
        verify(mapper).insertAppeal(eq(41L), eq(COMMENT_AUTHOR), anyString(), anyString(), eq("comment-appeal-1"), any());

        Map<String, Object> row = new HashMap<>();
        row.put("enforcement_id", 41L);
        row.put("appellant_member_id", COMMENT_AUTHOR);
        when(mapper.selectAppeal(71L)).thenReturn(row);
        when(mapper.reviewAppealCas(eq(71L), eq("REVERSED"), anyString(), eq(2L), eq(0), any())).thenReturn(1);
        when(mapper.reverseEnforcement(eq(41L), eq(0), any())).thenReturn(1);
        when(mapper.selectCommentForAudit(COMMENT)).thenReturn(comment("REMOVED", 3));
        when(mapper.updateCommentLifecycle(eq(COMMENT), eq("REMOVED"), eq("PUBLISHED"), eq(3), any())).thenReturn(1);
        CommunityPostRequests.AppealReview review = new CommunityPostRequests.AppealReview();
        review.setDecision("REVERSED");
        review.setReason("复核后不违规");
        review.setExpectedVersion(0);

        service.reviewAppeal(2L, 71L, review);

        verify(mapper).updateCommentLifecycle(eq(COMMENT), eq("REMOVED"), eq("PUBLISHED"), eq(3), any());
        verify(mapper).recomputeCounts(POST);
        verify(mapper, never()).reconcilePostEnforcementCas(anyLong(), anyInt(), anyString(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void reportedPostEnforcementIsLinkedToTheReportCase() {
        CommunityPost post = new CommunityPost();
        post.setId(POST);
        post.setAuthorId(POST_AUTHOR);
        post.setLifecycle("PUBLISHED");
        post.setVersion(3);
        when(mapper.selectByIdForUpdate(POST)).thenReturn(post);
        when(mapper.selectEnforcementByRequest(OPERATOR, "post-remove-1")).thenReturn(stored(21L));
        when(mapper.linkEnforcement(21L, REPORT, null)).thenReturn(1);
        when(mapper.selectActivePostEnforcementTypes(eq(POST), any())).thenReturn(Collections.singletonList("REMOVE"));
        when(mapper.reconcilePostEnforcementCas(eq(POST), eq(3), eq("REMOVED"), eq(0), eq(0), eq(0), any())).thenReturn(1);

        Long id = service.enforceReportedTarget(OPERATOR, REPORT, "CONTENT", POST, request("REMOVE", "post-remove-1"));

        assertEquals(Long.valueOf(21L), id);
        verify(mapper).linkEnforcement(21L, REPORT, null);
        verify(mapper).reconcilePostEnforcementCas(eq(POST), eq(3), eq("REMOVED"), eq(0), eq(0), eq(0), any());
    }

    @Test
    void reportBridgeRejectsTargetsThatAreNotContent() {
        assertEquals(Integer.valueOf(400), assertThrows(ServiceException.class,
                () -> service.enforceReportedTarget(OPERATOR, REPORT, "MEMBER", 3L, request("REMOVE", "x-1"))).getCode());
    }

    private Map<String, Object> comment(String lifecycle, int version) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", COMMENT);
        row.put("post_id", POST);
        row.put("author_id", COMMENT_AUTHOR);
        row.put("lifecycle", lifecycle);
        row.put("version", version);
        return row;
    }

    private Map<String, Object> stored(long id) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        return row;
    }

    private CommunityPostRequests.Enforcement request(String type, String requestId) {
        CommunityPostRequests.Enforcement request = new CommunityPostRequests.Enforcement();
        request.setEnforcementType(type);
        request.setRuleCode("SAFETY");
        request.setGuidelineVersionId(5L);
        request.setReasonCode("HARM");
        request.setEvidenceJson("{\"snapshotId\":55}");
        request.setRequestId(requestId);
        return request;
    }
}
