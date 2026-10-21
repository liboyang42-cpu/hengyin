package com.chengyinhub.business.community;

import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.domain.dto.CommunityPostRequests;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.service.CommunityTrustService;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICommunityMediaReceiptService;
import com.chengyinhub.business.service.IContentSafetyGate;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.impl.CommunityPostServiceImpl;
import com.chengyinhub.business.service.support.CommunityPostLifecyclePolicy;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.web.controller.business.CommunityPostModerationController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FOLLOWUP-C-2(gap 12·00 §7.1 #9 / 12·01 P2):community_post_appeal 缺 due_at、缺「已受理」通知、没有任何写入 REVIEWING 的路径。
 * 修复后:提交时按举报首响 SLA 写 due_at(标准 24h,紧急 15min)并给申诉人发「申诉已受理」系统消息;
 * 审核员认领(打开)申诉时 SUBMITTED → REVIEWING;UPHELD / REVERSED 结论不变。
 */
class CommunityPostAppealFollowupTest {
    private static final long AUTHOR = 9L;
    private static final long POST = 7L;
    private static final long REVIEWER = 2L;

    private CommunityPostMapper mapper;
    private CommunityPostServiceImpl service;
    private IImService im;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mapper = mock(CommunityPostMapper.class);
        service = new CommunityPostServiceImpl(mapper, new CommunityPostLifecyclePolicy(), mock(IContentSafetyGate.class),
                mock(IAuditTaskService.class), mock(CommunityTrustService.class),
                mock(ICommunityMediaReceiptService.class));
        im = mock(IImService.class);
        ObjectProvider<IImService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(im);
        service.setImServiceProvider(provider);
        when(mapper.insertOutbox(anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.insertTypedOutbox(anyString(), anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        CommunityPost removed = new CommunityPost();
        removed.setId(POST);
        removed.setAuthorId(AUTHOR);
        removed.setLifecycle("REMOVED");
        when(mapper.selectByIdForUpdate(POST)).thenReturn(removed);
    }

    private Map<String, Object> enforcement(String reasonCode, Long reportCaseId) {
        Map<String, Object> enforcement = new HashMap<>();
        enforcement.put("id", 31L);
        enforcement.put("post_id", POST);
        enforcement.put("target_member_id", null);
        enforcement.put("reason_code", reasonCode);
        enforcement.put("rule_code", "AUDIT_REVIEW_REJECTED");
        enforcement.put("report_case_id", reportCaseId);
        when(mapper.selectEnforcement(31L)).thenReturn(enforcement);
        return enforcement;
    }

    private long submitAndCaptureDueMinutes() {
        CommunityPostRequests.Appeal appeal = new CommunityPostRequests.Appeal();
        appeal.setEnforcementId(31L);
        appeal.setReason("内容并不违规，请复核");
        appeal.setRequestId("appeal-followup-1");
        long before = System.currentTimeMillis();
        service.appeal(AUTHOR, appeal);
        ArgumentCaptor<Date> due = ArgumentCaptor.forClass(Date.class);
        ArgumentCaptor<Date> now = ArgumentCaptor.forClass(Date.class);
        verify(mapper).insertAppeal(eq(31L), eq(AUTHOR), anyString(), anyString(), eq("appeal-followup-1"),
                due.capture(), now.capture());
        assertTrue(now.getValue().getTime() >= before);
        return (due.getValue().getTime() - now.getValue().getTime()) / 60_000L;
    }

    @Test
    void standardAppealIsDueIn24Hours() {
        enforcement("REVIEW_REJECTED", null);
        assertEquals(24 * 60L, submitAndCaptureDueMinutes());
    }

    @Test
    void appealOnUrgentReasonIsDueIn15Minutes() {
        enforcement("MINOR_SAFETY", null);
        assertEquals(15L, submitAndCaptureDueMinutes());
    }

    @Test
    void appealOnEnforcementFromUrgentReportUsesTheCaseSlaSnapshot() {
        enforcement("SPAM", 77L);
        when(mapper.selectReportCaseFirstResponseMinutes(77L)).thenReturn(15);
        assertEquals(15L, submitAndCaptureDueMinutes());
    }

    @Test
    void appellantIsToldTheAppealWasReceivedWithTheDeadline() {
        enforcement("REVIEW_REJECTED", null);
        submitAndCaptureDueMinutes();

        ArgumentCaptor<String> extra = ArgumentCaptor.forClass(String.class);
        verify(im).sendSystem(eq(AUTHOR), eq(ImMessage.MSG_CARD), eq("申诉已受理"), extra.capture());
        assertTrue(extra.getValue().contains("你的申诉已受理"), extra.getValue());
        assertTrue(extra.getValue().contains("\"bizId\":31"), extra.getValue());
        assertTrue(extra.getValue().contains("dueAt"), extra.getValue());
    }

    @Test
    void replayedRequestIdIsRefusedWithoutASecondNotice() {
        enforcement("REVIEW_REJECTED", null);
        when(mapper.insertAppeal(anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new DuplicateKeyException("uk_community_post_appeal_request"));
        CommunityPostRequests.Appeal appeal = new CommunityPostRequests.Appeal();
        appeal.setEnforcementId(31L);
        appeal.setReason("再提交一次");
        appeal.setRequestId("appeal-followup-1");

        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class, () -> service.appeal(AUTHOR, appeal)).getCode());
        verify(im, never()).sendSystem(anyLong(), anyInt(), anyString(), anyString());
    }

    private Map<String, Object> appealRow(String status, Long reviewer, int version) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 61L);
        row.put("enforcement_id", 31L);
        row.put("appellant_member_id", AUTHOR);
        row.put("status", status);
        row.put("reviewer_user_id", reviewer);
        row.put("version", version);
        return row;
    }

    @Test
    void reviewerClaimMovesSubmittedAppealToReviewing() {
        when(mapper.selectAppeal(61L)).thenReturn(appealRow("SUBMITTED", null, 0), appealRow("REVIEWING", REVIEWER, 1));
        when(mapper.claimAppealCas(eq(61L), eq(REVIEWER), eq(0), any())).thenReturn(1);

        Map<String, Object> claimed = service.claimAppeal(REVIEWER, 61L, 0);

        verify(mapper).claimAppealCas(eq(61L), eq(REVIEWER), eq(0), any());
        assertEquals("REVIEWING", claimed.get("status"));
    }

    @Test
    void reopeningByTheSameReviewerIsIdempotentButOthersAreRefused() {
        when(mapper.selectAppeal(61L)).thenReturn(appealRow("REVIEWING", REVIEWER, 1));

        assertEquals("REVIEWING", service.claimAppeal(REVIEWER, 61L, 1).get("status"));
        assertEquals(Integer.valueOf(409),
                assertThrows(ServiceException.class, () -> service.claimAppeal(5L, 61L, 1)).getCode());
        verify(mapper, never()).claimAppealCas(anyLong(), anyLong(), anyInt(), any());
    }

    @Test
    void decidedAppealCannotBeClaimedAndLostRaceIs409() {
        when(mapper.selectAppeal(61L)).thenReturn(appealRow("UPHELD", REVIEWER, 2));
        assertEquals(Integer.valueOf(409),
                assertThrows(ServiceException.class, () -> service.claimAppeal(REVIEWER, 61L, 2)).getCode());

        when(mapper.selectAppeal(62L)).thenReturn(appealRow("SUBMITTED", null, 0));
        when(mapper.claimAppealCas(eq(62L), eq(REVIEWER), eq(0), any())).thenReturn(0);
        assertEquals(Integer.valueOf(409),
                assertThrows(ServiceException.class, () -> service.claimAppeal(REVIEWER, 62L, 0)).getCode());
    }

    @Test
    void reviewingAppealStillEndsUpheldOrReversed() {
        enforcement("REVIEW_REJECTED", null);
        when(mapper.selectAppeal(61L)).thenReturn(appealRow("REVIEWING", REVIEWER, 1));
        when(mapper.reviewAppealCas(eq(61L), eq("UPHELD"), anyString(), eq(REVIEWER), eq(1), any())).thenReturn(1);
        CommunityPostRequests.AppealReview review = new CommunityPostRequests.AppealReview();
        review.setDecision("UPHELD");
        review.setReason("维持原处理");
        review.setExpectedVersion(1);

        service.reviewAppeal(REVIEWER, 61L, review);

        verify(mapper).reviewAppealCas(eq(61L), eq("UPHELD"), eq("维持原处理"), eq(REVIEWER), eq(1), any());
    }

    @Test
    void claimEndpointRequiresTheReviewPermission() throws Exception {
        Method claim = CommunityPostModerationController.class.getMethod("claimAppeal", Long.class,
                CommunityPostRequests.AppealReview.class);
        assertEquals("/appeals/{id}/claim", claim.getAnnotation(PostMapping.class).value()[0]);
        assertEquals("@ss.hasPermi('business:audit:review')", claim.getAnnotation(PreAuthorize.class).value());
    }
}
