package com.chengyinhub.business.moderation;

import com.chengyinhub.business.domain.ContentSafetyRecheck;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.PublishSafetyRequest;
import com.chengyinhub.business.domain.dto.SafetyDecision;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.ContentSafetyRecheckMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.impl.ContentSafetyGateImpl;
import com.chengyinhub.business.service.impl.ContentSafetyRecheckServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** SEC-12: moderation routing for authors with and without a WeChat mini-program openid. */
class ModerationRoutingTest {
    IWxContentSecurityService wx;
    IAuditTaskService audits;
    TencentCloudModerationProvider tencent;
    ContentSafetyGateImpl gate;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        wx = mock(IWxContentSecurityService.class);
        when(wx.isEnabled()).thenReturn(true);
        audits = mock(IAuditTaskService.class);
        when(audits.submitSafetyHold(anyString(), any(), any(), anyString(), any())).thenReturn(77L);
        tencent = mock(TencentCloudModerationProvider.class);
        when(tencent.name()).thenReturn(TencentCloudModerationProvider.NAME);
        when(tencent.judgesImagesSynchronously()).thenReturn(true);
        ObjectProvider<IWxContentSecurityService> wxProvider = mock(ObjectProvider.class);
        when(wxProvider.getIfAvailable()).thenReturn(wx);
        ModerationRouter router = new ModerationRouter(new WechatModerationProvider(wxProvider), tencent);
        gate = new ContentSafetyGateImpl();
        ReflectionTestUtils.setField(gate, "wxContentSecurityService", wx);
        ReflectionTestUtils.setField(gate, "auditTaskService", audits);
        ReflectionTestUtils.setField(gate, "moderationRouter", router);
    }

    PublishSafetyRequest post(String openid, String text, String... media) {
        PublishSafetyRequest r = new PublishSafetyRequest();
        r.setReviewMode(PublishSafetyRequest.ReviewMode.POST_REVIEW.name());
        r.setContentType("SQUARE");
        r.setContentId(1L);
        r.setMemberId(100L);
        r.setOpenid(openid);
        r.setText(text);
        if (media.length > 0) r.setMediaUrls(Arrays.asList(media));
        return r;
    }

    @Test void authorWithOpenidKeepsTheWechatCheckEvenWhenTencentIsConfigured() {
        when(tencent.canJudge(any())).thenReturn(true);
        when(wx.checkText("hello", "openid-x")).thenReturn(null);
        when(wx.canSubmitMedia("openid-x")).thenReturn(true);
        assertTrue(gate.decide(post("openid-x", "hello")).isPass());
        verify(wx).checkText("hello", "openid-x");
        verify(tencent, never()).checkText(any(), any());
    }

    @Test void authorWithoutOpenidIsJudgedByTencentForTextAndImages() {
        when(tencent.canJudge(any())).thenReturn(true);
        when(tencent.checkText("hello", "")).thenReturn(ModerationVerdict.PASS);
        when(tencent.checkImage("https://cdn.example/a.jpg", "")).thenReturn(ModerationVerdict.PASS);
        SafetyDecision ok = gate.decide(post("", "hello", "https://cdn.example/a.jpg"));
        assertTrue(ok.isPass(), "a judged post is published, not held");
        verify(wx, never()).checkText(any(), any());

        when(tencent.checkText("bad", "")).thenReturn(ModerationVerdict.RISK);
        assertEquals("TEXT_RISK", gate.decide(post("", "bad")).getReasonCode());

        when(tencent.checkImage("https://cdn.example/b.jpg", "")).thenReturn(ModerationVerdict.RISK);
        SafetyDecision image = gate.decide(post("", "hello", "https://cdn.example/b.jpg"));
        assertTrue(image.isBlock());
        assertEquals("IMAGE_RISK", image.getReasonCode());
        verify(audits, never()).submitSafetyHold(anyString(), any(), any(), anyString(), any());
    }

    @Test void tencentReviewOrOutageHoldsForTheAuthorAndQueuesHumanReview() {
        when(tencent.canJudge(any())).thenReturn(true);
        when(tencent.checkText("maybe", "")).thenReturn(ModerationVerdict.REVIEW);
        SafetyDecision review = gate.decide(post("", "maybe"));
        assertTrue(review.isHold());
        assertFalse(review.isVisible());
        assertEquals(77L, review.getAuditTaskId());
        when(tencent.checkText("hello", "")).thenReturn(ModerationVerdict.PASS);
        when(tencent.checkImage(any(), any())).thenReturn(ModerationVerdict.UNAVAILABLE);
        assertEquals("MEDIA_UNCHECKED", gate.decide(post("", "hello", "https://cdn.example/a.jpg")).getReasonCode());
    }

    @Test void withoutAnyMachineProviderTheAuthorSeesItAndHumansReviewItInsteadOfARefusal() {
        when(tencent.canJudge(any())).thenReturn(false);
        SafetyDecision d = gate.decide(post(null, "hello"));
        assertTrue(d.isHold(), "never refused");
        assertEquals("IDENTITY_REVIEW", d.getReasonCode());
        assertEquals(77L, d.getAuditTaskId());
        verify(wx, never()).checkText(any(), any());
    }

    @Test void keywordPreFilterAlwaysRunsBeforeAnyProvider() {
        com.chengyinhub.business.service.ISensitiveContentLibrary library =
                mock(com.chengyinhub.business.service.ISensitiveContentLibrary.class);
        when(library.blockingCategory(eq("禁词"), any(), any())).thenReturn("POLITICS");
        ReflectionTestUtils.setField(gate, "sensitiveLibrary", library);
        when(tencent.canJudge(any())).thenReturn(true);
        assertTrue(gate.decide(post("", "禁词")).isBlock());
        verify(tencent, never()).checkText(any(), any());
    }

    @Test void routerPicksWechatForOpenidAndTencentOnlyWithoutOne() {
        when(tencent.canJudge(any())).thenReturn(true);
        ModerationRouter router = (ModerationRouter) ReflectionTestUtils.getField(gate, "moderationRouter");
        assertEquals(WechatModerationProvider.NAME, router.route("openid-x").name());
        assertSame(tencent, router.route(" "));
        assertNull(router.fallbackFor("openid-x"));
        when(tencent.canJudge(any())).thenReturn(false);
        assertNull(router.route(null));
    }

    // ---- legacy checkText callers (chat, comments, AI characters) ----

    @Test void legacyTextCheckUsesTencentForBlankOpenidAndKeepsWechatRulesOtherwise() {
        com.chengyinhub.business.service.impl.WxContentSecurityServiceImpl service =
                new com.chengyinhub.business.service.impl.WxContentSecurityServiceImpl();
        com.chengyinhub.common.config.WxPayConfig config = new com.chengyinhub.common.config.WxPayConfig();
        ReflectionTestUtils.setField(config, "secCheckEnabled", false);
        ReflectionTestUtils.setField(service, "wxConfig", config);
        // Without a Tencent provider, the legacy fail-closed refusal is unchanged.
        assertSame(IWxContentSecurityService.MSG_UNAVAILABLE, service.checkText("hello", ""));
        ReflectionTestUtils.setField(service, "openidFreeModeration", tencent);
        when(tencent.configured()).thenReturn(true);
        when(tencent.checkText("hello", "")).thenReturn(ModerationVerdict.PASS);
        when(tencent.checkText("bad", "")).thenReturn(ModerationVerdict.RISK);
        when(tencent.checkText("maybe", "")).thenReturn(ModerationVerdict.REVIEW);
        when(tencent.checkText("later", "")).thenReturn(ModerationVerdict.UNAVAILABLE);
        assertNull(service.checkText("hello", ""));
        assertEquals("内容包含违规信息，请修改后重试", service.checkText("bad", ""));
        assertSame(IWxContentSecurityService.REVIEW_REQUIRED, service.checkText("maybe", ""));
        assertTrue(IWxContentSecurityService.isDetectionUnavailable(service.checkText("later", "")));
        // An author with an openid never reaches Tencent: the WeChat switch still decides.
        assertSame(IWxContentSecurityService.MSG_UNAVAILABLE, service.checkText("hello", "openid-x"));
        verify(tencent, never()).checkText(eq("hello"), eq("openid-x"));
    }

    // ---- recheck queue ----

    @SuppressWarnings("unchecked")
    ContentSafetyRecheckServiceImpl recheck(ContentSafetyRecheckMapper mapper, IAuditTaskService auditService) {
        ContentSafetyRecheckServiceImpl service = new ContentSafetyRecheckServiceImpl();
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        UmsMember appleOnly = new UmsMember();
        appleOnly.setWxOpenId("");
        when(members.selectUmsMemberById(8L)).thenReturn(appleOnly);
        ObjectProvider<IAuditTaskService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(auditService);
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "wx", wx);
        ReflectionTestUtils.setField(service, "memberMapper", members);
        ReflectionTestUtils.setField(service, "auditTaskMapper", mock(AuditTaskMapper.class));
        ReflectionTestUtils.setField(service, "auditTaskServiceProvider", provider);
        ReflectionTestUtils.setField(service, "moderationRouter", ReflectionTestUtils.getField(gate, "moderationRouter"));
        return service;
    }

    ContentSafetyRecheck row() {
        ContentSafetyRecheck row = new ContentSafetyRecheck();
        row.setId(55L);
        row.setBizType("TOPIC");
        row.setBizId(9L);
        row.setMemberId(8L);
        row.setTextBody("hello");
        row.setAttemptCount(0);
        row.setMaxAttempts(12);
        row.setCreateTime(new Date());
        return row;
    }

    @Test void recheckHandsUnjudgeableContentToHumansInsteadOfAutoRejecting() {
        when(tencent.canJudge(any())).thenReturn(false);
        ContentSafetyRecheckMapper mapper = mock(ContentSafetyRecheckMapper.class);
        when(mapper.selectDue(any(), anyInt())).thenReturn(Collections.singletonList(row()));
        IAuditTaskService auditService = mock(IAuditTaskService.class);
        recheck(mapper, auditService).runDue();
        verify(mapper).finish(eq(55L), eq(0), eq(5), eq("HUMAN_REVIEW"), any());
        verify(mapper, never()).reschedule(anyLong(), anyInt(), anyInt(), any(), any(), any());
        verify(auditService, never()).review(anyLong(), anyLong(), any());
    }

    @Test void recheckPublishesOpenidFreeContentOnceTencentPasses() {
        when(tencent.canJudge(any())).thenReturn(true);
        when(tencent.checkText("hello", null)).thenReturn(ModerationVerdict.PASS);
        ContentSafetyRecheckMapper mapper = mock(ContentSafetyRecheckMapper.class);
        when(mapper.selectDue(any(), anyInt())).thenReturn(Collections.singletonList(row()));
        IAuditTaskService auditService = mock(IAuditTaskService.class);
        when(auditService.findOpenContentTaskId("TOPIC", 9L)).thenReturn(300L);
        recheck(mapper, auditService).runDue();
        verify(auditService).review(eq(300L), eq(0L), argThat(r -> r.getStatus() != null));
        verify(mapper).finish(eq(55L), eq(0), eq(ContentSafetyRecheck.STATE_PASSED), eq("PASS"), any());
        verify(wx, never()).checkText(any(), any());
    }
}
