package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.ContentSafetyRecheck;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.domain.dto.PublishSafetyRequest;
import com.chengyinhub.business.domain.dto.SafetyDecision;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.ContentSafetyRecheckMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IContentSafetyRecheckService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Round5C:机审没结论的内容自动复检 —— 通过自动发布、违规/review 自动驳回、耗尽驳回并通知,全程无人。 */
class ContentSafetyRecheckServiceImplTest {

    private static final long NOW = 1_800_000_000_000L;
    private ContentSafetyRecheckServiceImpl service;
    private ContentSafetyRecheckMapper mapper;
    private IWxContentSecurityService wx;
    private IAuditTaskService audits;
    private AuditTaskMapper taskMapper;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new ContentSafetyRecheckServiceImpl();
        mapper = mock(ContentSafetyRecheckMapper.class);
        wx = mock(IWxContentSecurityService.class);
        audits = mock(IAuditTaskService.class);
        taskMapper = mock(AuditTaskMapper.class);
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        UmsMember author = new UmsMember();
        author.setWxOpenId("openid-author");
        when(members.selectUmsMemberById(7L)).thenReturn(author);
        ObjectProvider<IAuditTaskService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(audits);
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "wx", wx);
        ReflectionTestUtils.setField(service, "memberMapper", members);
        ReflectionTestUtils.setField(service, "auditTaskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "auditTaskServiceProvider", provider);
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        when(wx.isEnabled()).thenReturn(true);
        when(wx.canSubmitMedia(anyString())).thenReturn(true);
        when(audits.findOpenContentTaskId(anyString(), anyLong())).thenReturn(900L);
        AuditTask task = new AuditTask();
        task.setId(900L);
        task.setVersion(3);
        when(taskMapper.selectAuditTaskById(900L)).thenReturn(task);
        when(mapper.finish(anyLong(), anyInt(), anyInt(), any(), any())).thenReturn(1);
        when(mapper.reschedule(anyLong(), anyInt(), anyInt(), any(), any(), any())).thenReturn(1);
    }

    private ContentSafetyRecheck row(String bizType, String text, int attempts, int business) {
        ContentSafetyRecheck r = new ContentSafetyRecheck();
        r.setId(55L);
        r.setBizType(bizType);
        r.setBizId(31L);
        r.setMemberId(7L);
        r.setTextBody(text);
        r.setAttemptCount(attempts);
        r.setMaxAttempts(12);
        r.setBusinessReview(business);
        r.setState(0);
        r.setCreateTime(new Date(NOW - 60_000L));
        return r;
    }

    private AuditReviewDTO reviewed() {
        ArgumentCaptor<AuditReviewDTO> dto = ArgumentCaptor.forClass(AuditReviewDTO.class);
        verify(audits).review(eq(900L), eq(0L), dto.capture());
        return dto.getValue();
    }

    @Test
    void passingRecheckPublishesThroughTheExistingReviewPathAsSystem() {
        when(mapper.selectDue(any(), anyInt())).thenReturn(Collections.singletonList(row(AuditTask.BIZ_TOPIC, "ok", 2, 0)));
        when(wx.checkText("ok", "openid-author")).thenReturn(null);

        assertEquals(1, service.runDue());

        AuditReviewDTO dto = reviewed();
        assertEquals(AuditTask.STATUS_APPROVED, dto.getStatus());
        assertEquals(Integer.valueOf(3), dto.getExpectedVersion());
        verify(mapper).finish(55L, 2, ContentSafetyRecheck.STATE_PASSED, "PASS", new Date(NOW));
    }

    @Test
    void riskyTextIsRejectedAutomaticallySoAuthorIsNotified() {
        when(mapper.selectDue(any(), anyInt())).thenReturn(Collections.singletonList(row(AuditTask.BIZ_ACTIVITY, "bad", 0, 0)));
        when(wx.checkText("bad", "openid-author")).thenReturn("内容含有违法违规信息");

        service.runDue();

        AuditReviewDTO dto = reviewed();
        assertEquals(AuditTask.STATUS_REJECTED, dto.getStatus());
        assertEquals(ContentSafetyRecheckServiceImpl.REASON_RISKY, dto.getReasonText());
        verify(mapper).finish(55L, 0, ContentSafetyRecheck.STATE_REJECTED, "RISKY", new Date(NOW));
    }

    @Test
    void reviewSuggestionIsTreatedAsRisky() {
        when(mapper.selectDue(any(), anyInt())).thenReturn(Collections.singletonList(row(AuditTask.BIZ_COUPON, "maybe", 0, 0)));
        when(wx.checkText("maybe", "openid-author")).thenReturn(IWxContentSecurityService.REVIEW_REQUIRED);

        service.runDue();

        assertEquals(AuditTask.STATUS_REJECTED, reviewed().getStatus());
    }

    @Test
    void wechatDownKeepsContentHiddenAndReschedulesWithBackoff() {
        when(mapper.selectDue(any(), anyInt())).thenReturn(Collections.singletonList(row(AuditTask.BIZ_TOPIC, "ok", 1, 0)));
        when(wx.checkText("ok", "openid-author")).thenReturn(IWxContentSecurityService.MSG_BUSY);

        service.runDue();

        verify(audits, never()).review(anyLong(), anyLong(), any());
        verify(mapper).reschedule(eq(55L), eq(1), eq(2), eq(new Date(NOW + 20L * 60_000L)), eq("TEXT_UNAVAILABLE"),
                eq(new Date(NOW)));
    }

    @Test
    void detectionOffAndMissingOpenidAreRetriedNotPublished() {
        when(wx.isEnabled()).thenReturn(false);
        when(mapper.selectDue(any(), anyInt())).thenReturn(Collections.singletonList(row(AuditTask.BIZ_TOPIC, "ok", 0, 0)));
        service.runDue();
        verify(mapper).reschedule(eq(55L), eq(0), eq(1), any(), eq("DETECT_DISABLED"), any());

        ContentSafetyRecheck noOpenid = row(AuditTask.BIZ_TOPIC, "ok", 0, 0);
        noOpenid.setMemberId(8L);
        when(wx.isEnabled()).thenReturn(true);
        when(mapper.selectDue(any(), anyInt())).thenReturn(Collections.singletonList(noOpenid));
        service.runDue();
        verify(mapper).reschedule(eq(55L), eq(0), eq(1), any(), eq("NO_OPENID"), any());
        verify(audits, never()).review(anyLong(), anyLong(), any());
    }

    @Test
    void exhaustedRetriesRejectWithExplanationAndStayHidden() {
        when(mapper.selectDue(any(), anyInt())).thenReturn(Collections.singletonList(row(AuditTask.BIZ_TOPIC, "ok", 11, 0)));
        when(wx.checkText("ok", "openid-author")).thenReturn(IWxContentSecurityService.MSG_UNAVAILABLE);

        service.runDue();

        AuditReviewDTO dto = reviewed();
        assertEquals(AuditTask.STATUS_REJECTED, dto.getStatus());
        assertEquals(ContentSafetyRecheckServiceImpl.REASON_EXHAUSTED, dto.getReasonText());
        verify(mapper).finish(55L, 11, ContentSafetyRecheck.STATE_EXHAUSTED, "TEXT_UNAVAILABLE", new Date(NOW));
    }

    @Test
    void businessApprovalIsLeftToPeopleAfterSafetyPasses() {
        when(mapper.selectDue(any(), anyInt())).thenReturn(Collections.singletonList(row(AuditTask.BIZ_OFFICIAL_EVENT, "ok", 0, 1)));
        when(wx.checkText("ok", "openid-author")).thenReturn(null);

        service.runDue();

        verify(audits, never()).review(anyLong(), anyLong(), any());
        verify(mapper).finish(55L, 0, ContentSafetyRecheck.STATE_PASSED, "BUSINESS_REVIEW", new Date(NOW));
    }

    @Test
    void passWithImagesSubmitsThemForAsyncCheckBeforePublishing() {
        ContentSafetyRecheck r = row(AuditTask.BIZ_ACTIVITY, null, 0, 0);
        r.setMediaJson("[\"https://cdn.example/a.jpg\"]");
        when(mapper.selectDue(any(), anyInt())).thenReturn(Collections.singletonList(r));

        service.runDue();

        verify(wx).submitMediaCheck(IWxContentSecurityService.BIZ_TYPE_ACTIVITY, 31L, "https://cdn.example/a.jpg", "openid-author");
        assertEquals(AuditTask.STATUS_APPROVED, reviewed().getStatus());
    }

    @Test
    void imagesThatCannotBeSubmittedAreRetried() {
        ContentSafetyRecheck r = row(AuditTask.BIZ_ACTIVITY, null, 0, 0);
        r.setMediaJson("[\"https://cdn.example/a.jpg\"]");
        when(wx.canSubmitMedia(anyString())).thenReturn(false);
        when(mapper.selectDue(any(), anyInt())).thenReturn(Collections.singletonList(r));

        service.runDue();

        verify(mapper).reschedule(eq(55L), eq(0), eq(1), any(), eq("MEDIA_UNCHECKABLE"), any());
        verify(audits, never()).review(anyLong(), anyLong(), any());
    }

    @Test
    void oneFailingRowDoesNotStopTheBatch() {
        ContentSafetyRecheck a = row(AuditTask.BIZ_TOPIC, "ok", 0, 0);
        ContentSafetyRecheck b = row(AuditTask.BIZ_TOPIC, "ok", 0, 0);
        b.setId(56L);
        when(mapper.selectDue(any(), anyInt())).thenReturn(java.util.Arrays.asList(a, b));
        when(wx.checkText("ok", "openid-author")).thenReturn(null);
        when(audits.review(anyLong(), anyLong(), any())).thenThrow(new IllegalStateException("conflict")).thenReturn(1);

        assertEquals(1, service.runDue());
        verify(mapper).finish(56L, 0, ContentSafetyRecheck.STATE_PASSED, "PASS", new Date(NOW));
        verify(mapper, never()).finish(eq(55L), anyInt(), anyInt(), any(), any());
    }

    @Test
    void enqueueRefreshesAnExistingPendingRowInsteadOfDuplicating() {
        ContentSafetyRecheck open = row(AuditTask.BIZ_TOPIC, "old", 4, 0);
        when(mapper.selectPendingByBiz(AuditTask.BIZ_TOPIC, 31L)).thenReturn(open);
        when(mapper.refreshPending(eq(55L), eq(7L), eq("new"), isNull(), eq(0), any())).thenReturn(1);

        service.enqueue(AuditTask.BIZ_TOPIC, 31L, 7L, "new", null, false);

        verify(mapper, never()).insertRecheck(any());
    }

    @Test
    void preReviewWithBusinessFieldsKeepsPeopleOnBusinessApproval() {
        PublishSafetyRequest req = new PublishSafetyRequest();
        req.setContentType(AuditTask.BIZ_ACTIVITY);
        req.setMemberId(7L);
        req.setText("t");
        req.setHasSignup(true);
        req.setReviewMode(PublishSafetyRequest.ReviewMode.PRE_REVIEW.name());
        service.enqueue(req, 31L);
        ArgumentCaptor<ContentSafetyRecheck> inserted = ArgumentCaptor.forClass(ContentSafetyRecheck.class);
        verify(mapper).insertRecheck(inserted.capture());
        assertEquals(Integer.valueOf(1), inserted.getValue().getBusinessReview());
        assertEquals(new Date(NOW + 5L * 60_000L), inserted.getValue().getNextAttemptAt());
    }

    @Test
    void onlyUncheckedHoldsNeedRecheck() {
        assertTrue(IContentSafetyRecheckService.needsRecheck(SafetyDecision.hold("DETECT_DISABLED", 1L)));
        assertTrue(IContentSafetyRecheckService.needsRecheck(SafetyDecision.hold("MEDIA_PENDING", 1L)));
        assertFalse(IContentSafetyRecheckService.needsRecheck(SafetyDecision.hold("STRUCTURED_REVIEW", 1L)));
        assertFalse(IContentSafetyRecheckService.needsRecheck(SafetyDecision.pass()));
    }
}
