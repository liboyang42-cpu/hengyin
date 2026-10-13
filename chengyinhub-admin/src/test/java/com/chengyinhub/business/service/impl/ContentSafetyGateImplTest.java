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

    private PublishSafetyRequest req(String text) {
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
}
