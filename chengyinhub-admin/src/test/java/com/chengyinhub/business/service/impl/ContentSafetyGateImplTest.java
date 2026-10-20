package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.PublishSafetyRequest;
import com.chengyinhub.business.domain.dto.SafetyDecision;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UGC 安全门禁裁决单测:PASS / HOLD / BLOCK + fail-safe HOLD。
 */
class ContentSafetyGateImplTest {

    private IWxContentSecurityService wxSec;
    private IAuditTaskService auditTaskService;
    private ContentSafetyGateImpl gate;

    @BeforeEach
    void setUp() {
        wxSec = mock(IWxContentSecurityService.class);
        auditTaskService = mock(IAuditTaskService.class);
        gate = new ContentSafetyGateImpl();
        ReflectionTestUtils.setField(gate, "wxContentSecurityService", wxSec);
        ReflectionTestUtils.setField(gate, "auditTaskService", auditTaskService);
        when(wxSec.isEnabled()).thenReturn(true);
        when(auditTaskService.submitSafetyHold(anyString(), any(), any(), anyString(), any()))
                .thenReturn(999L);
    }

    /** 既有用例描述的是今天的先审后发语义(社区推文/回复的口径),显式传 PRE_REVIEW;断言一字未改。 */
    private PublishSafetyRequest req(String text) {
        PublishSafetyRequest r = postReq(text);
        r.setReviewMode(PublishSafetyRequest.ReviewMode.PRE_REVIEW.name());
        return r;
    }

    /** 不设模式 = 入参默认值(POST_REVIEW) */
    private PublishSafetyRequest postReq(String text) {
        PublishSafetyRequest r = new PublishSafetyRequest();
        r.setContentType("SQUARE");
        r.setContentId(1L);
        r.setMemberId(100L);
        r.setOpenid("openid-x");
        r.setText(text);
        return r;
    }

    @Test
    void cleanText_pass_noAuditTask() {
        when(wxSec.checkText(eq("今天天气不错"), eq("openid-x"))).thenReturn(null);

        SafetyDecision d = gate.decide(req("今天天气不错"));

        assertTrue(d.isPass());
        assertTrue(d.isVisible());
        verify(auditTaskService, never()).submitSafetyHold(anyString(), any(), any(), anyString(), any());
    }

    @Test
    void riskyText_block_noAuditTask() {
        when(wxSec.checkText(anyString(), anyString())).thenReturn("内容含违规词");

        SafetyDecision d = gate.decide(req("违规内容"));

        assertTrue(d.isBlock());
        assertFalse(d.isVisible());
        assertEquals("TEXT_RISK", d.getReasonCode());
        verify(auditTaskService, never()).submitSafetyHold(anyString(), any(), any(), anyString(), any());
    }

    @Test
    void providerReview_hold_createsAuditTaskInsteadOfPublishing() {
        when(wxSec.checkText(anyString(), anyString()))
                .thenReturn(IWxContentSecurityService.REVIEW_REQUIRED);

        SafetyDecision d = gate.decide(req("疑似风险内容"));

        assertTrue(d.isHold());
        assertEquals("TEXT_REVIEW", d.getReasonCode());
        verify(auditTaskService).submitSafetyHold(eq("SQUARE"), eq(1L), eq(100L), eq("NORMAL"), any());
    }

    @Test
    void structuredFields_hold_createsAuditTask() {
        when(wxSec.checkText(anyString(), anyString())).thenReturn(null);
        PublishSafetyRequest r = req("路线活动");
        r.setHasPrice(true);
        r.setHasSignup(true);

        SafetyDecision d = gate.decide(r);

        assertTrue(d.isHold());
        assertFalse(d.isVisible());
        assertEquals("STRUCTURED_REVIEW", d.getReasonCode());
        assertEquals(Long.valueOf(999L), d.getAuditTaskId());
        verify(auditTaskService).submitSafetyHold(eq("SQUARE"), eq(1L), eq(100L), anyString(), any());
    }

    @Test
    void withMedia_hold_createsAuditTask() {
        when(wxSec.checkText(anyString(), anyString())).thenReturn(null);
        PublishSafetyRequest r = req("看图");
        r.setMediaUrls(Collections.singletonList("https://oss/x.jpg"));

        SafetyDecision d = gate.decide(r);

        assertTrue(d.isHold());
        assertEquals("MEDIA_PENDING", d.getReasonCode());
        verify(auditTaskService).submitSafetyHold(anyString(), any(), any(), anyString(), any());
    }

    @Test
    void detectionDisabled_failSafeHold() {
        when(wxSec.isEnabled()).thenReturn(false);

        SafetyDecision d = gate.decide(req("检测关了也不能直发"));

        assertTrue(d.isHold(), "检测被关闭时 fail-safe HOLD,不 fail-open 直发");
        assertEquals("DETECT_DISABLED", d.getReasonCode());
    }

    @Test
    void detectionThrows_failSafeHold() {
        when(wxSec.checkText(anyString(), anyString())).thenThrow(new RuntimeException("接口挂了"));

        SafetyDecision d = gate.decide(req("检测异常也不能直发"));

        assertTrue(d.isHold(), "检测异常时 fail-safe HOLD");
        assertEquals("DETECT_ERROR", d.getReasonCode());
    }

    @Test
    void appMemberWithoutWechatOpenidGoesToReviewInsteadOfFalseBlock() {
        PublishSafetyRequest request = req("App 发布内容");
        request.setOpenid(null);

        SafetyDecision decision = gate.decide(request);

        assertTrue(decision.isHold());
        assertEquals("IDENTITY_REVIEW", decision.getReasonCode());
        verify(wxSec, never()).checkText(anyString(), anyString());
        verify(auditTaskService).submitSafetyHold(eq("SQUARE"), eq(1L), eq(100L), eq("NORMAL"), any());
    }

    // ==================== 2026-10-20 分级审核:POST_REVIEW / PRE_REVIEW / EXEMPT ====================

    @Test
    void defaultReviewModeIsPostReview() {
        assertEquals("POST_REVIEW", new PublishSafetyRequest().getReviewMode());
    }

    @Test
    void postReview_reviewRequired_passFlagged_noAuditTask() {
        when(wxSec.checkText(anyString(), anyString())).thenReturn(IWxContentSecurityService.REVIEW_REQUIRED);

        SafetyDecision d = gate.decide(postReq("疑似风险内容"));

        assertTrue(d.isPass());
        assertTrue(d.isVisible());
        assertTrue(d.isFlagged());
        assertEquals("TEXT_REVIEW", d.getFlagReason());
        verify(auditTaskService, never()).submitSafetyHold(anyString(), any(), any(), anyString(), any());
    }

    @Test
    void postReview_detectionBusy_passFlagged() {
        when(wxSec.checkText(anyString(), anyString())).thenReturn(IWxContentSecurityService.MSG_BUSY);

        SafetyDecision d = gate.decide(postReq("机审繁忙"));

        assertTrue(d.isPass());
        assertTrue(d.isFlagged());
        assertEquals("TEXT_REVIEW", d.getFlagReason());
        verify(auditTaskService, never()).submitSafetyHold(anyString(), any(), any(), anyString(), any());
    }

    @Test
    void postReview_detectionThrows_passFlagged() {
        when(wxSec.checkText(anyString(), anyString())).thenThrow(new RuntimeException("接口挂了"));

        SafetyDecision d = gate.decide(postReq("检测异常"));

        assertTrue(d.isPass());
        assertTrue(d.isFlagged());
        assertEquals("DETECT_ERROR", d.getFlagReason());
        verify(auditTaskService, never()).submitSafetyHold(anyString(), any(), any(), anyString(), any());
    }

    @Test
    void postReview_missingOpenid_passFlagged() {
        PublishSafetyRequest r = postReq("App 发布");
        r.setOpenid(" ");

        SafetyDecision d = gate.decide(r);

        assertTrue(d.isPass());
        assertTrue(d.isFlagged());
        assertEquals("IDENTITY_REVIEW", d.getFlagReason());
        verify(wxSec, never()).checkText(anyString(), anyString());
    }

    @Test
    void postReview_detectionDisabled_passFlagged() {
        when(wxSec.isEnabled()).thenReturn(false);

        SafetyDecision d = gate.decide(postReq("检测关了"));

        assertTrue(d.isPass());
        assertTrue(d.isFlagged());
        assertEquals("DETECT_DISABLED", d.getFlagReason());
    }

    @Test
    void preReview_reviewRequired_holdWithTask() {
        when(wxSec.checkText(anyString(), anyString())).thenReturn(IWxContentSecurityService.REVIEW_REQUIRED);

        SafetyDecision d = gate.decide(req("疑似风险内容"));

        assertTrue(d.isHold());
        assertFalse(d.isFlagged());
        assertEquals(Long.valueOf(999L), d.getAuditTaskId());
        verify(auditTaskService).submitSafetyHold(eq("SQUARE"), eq(1L), eq(100L), eq("NORMAL"), any());
    }

    @Test
    void riskyText_block_inBothModes() {
        when(wxSec.checkText(anyString(), anyString())).thenReturn("内容含违规词");
        for (PublishSafetyRequest r : java.util.Arrays.asList(req("违规内容"), postReq("违规内容"))) {
            SafetyDecision d = gate.decide(r);
            assertTrue(d.isBlock(), r.getReviewMode());
            assertEquals("TEXT_RISK", d.getReasonCode());
        }
        verify(auditTaskService, never()).submitSafetyHold(anyString(), any(), any(), anyString(), any());
    }

    @Test
    void postReview_imagesPriceSignupGeo_pass_noAuditTask() {
        when(wxSec.checkText(anyString(), anyString())).thenReturn(null);
        PublishSafetyRequest r = postReq("收费路线活动");
        r.setHasPrice(true);
        r.setHasSignup(true);
        r.setHasGeo(true);
        r.setMediaUrls(Collections.singletonList("https://oss/x.jpg"));

        SafetyDecision d = gate.decide(r);

        assertTrue(d.isPass());
        assertFalse(d.isFlagged());
        verify(auditTaskService, never()).submitSafetyHold(anyString(), any(), any(), anyString(), any());
    }

    @Test
    void exempt_pass_withoutDetection() {
        PublishSafetyRequest r = postReq("豁免");
        r.setReviewMode("EXEMPT");

        SafetyDecision d = gate.decide(r);

        assertTrue(d.isPass());
        verify(wxSec, never()).checkText(anyString(), anyString());
        verify(auditTaskService, never()).submitSafetyHold(anyString(), any(), any(), anyString(), any());
    }

    /** 脏值/空值不得 fail-open:一律按最严的 PRE_REVIEW(今天全部调用方的语义)。 */
    @Test
    void unknownOrNullMode_fallsBackToPreReview() {
        when(wxSec.checkText(anyString(), anyString())).thenReturn(IWxContentSecurityService.REVIEW_REQUIRED);
        for (String mode : java.util.Arrays.asList("garbage", "", null, "exempt ")) {
            PublishSafetyRequest r = postReq("疑似风险内容");
            r.setReviewMode(mode);
            SafetyDecision d = gate.decide(r);
            assertTrue(d.isHold(), String.valueOf(mode));
            assertEquals("TEXT_REVIEW", d.getReasonCode());
        }
    }

    /** 先审后发但内容还没落库(活动发布前置裁决):不建 bizId 为空的孤儿审核单,由调用方落库后建单。 */
    @Test
    void preReview_holdWithoutContentId_doesNotCreateOrphanTask() {
        when(wxSec.checkText(anyString(), anyString())).thenReturn(null);
        PublishSafetyRequest r = req("活动");
        r.setContentId(null);
        r.setHasSignup(true);

        SafetyDecision d = gate.decide(r);

        assertTrue(d.isHold());
        assertEquals("STRUCTURED_REVIEW", d.getReasonCode());
        assertEquals(null, d.getAuditTaskId());
        verify(auditTaskService, never()).submitSafetyHold(anyString(), any(), any(), anyString(), any());
    }
}
