package com.chengyinhub.business.service.support;

import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.impl.ContentSafetyGateImpl;
import com.chengyinhub.business.util.JourneyReviewTexts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUDIT-P1:主题文本走统一门禁 POST_REVIEW。真门禁 + mock 机审:
 * 机审建议人工 → 放行打标;违规 → 拦截(叙事字段带字段名);门禁先发后审不建审核单。
 */
class TopicTextSafetyTest {

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
        when(wxSec.checkText(anyString(), any())).thenReturn(null);
        when(wxSec.checkText(contains("违规"), any())).thenReturn("内容包含违规信息");
        when(wxSec.checkText(contains("待复核"), any())).thenReturn(IWxContentSecurityService.REVIEW_REQUIRED);
    }

    @Test
    void reviewSuggestedMainText_passesFlagged_insteadOfError() {
        TopicTextSafety.Verdict v = TopicTextSafety.check(gate, 1L, "openid", null, "待复核的主题名", null);

        assertFalse(v.isBlocked(), "review 建议不再当硬错误");
        assertTrue(v.isFlagged());
        assertEquals("TEXT_REVIEW", v.getFlagReason());
        verify(auditTaskService, never()).submitSafetyHold(anyString(), any(), any(), anyString(), any());
    }

    @Test
    void riskyMainText_isRefused() {
        TopicTextSafety.Verdict v = TopicTextSafety.check(gate, 1L, "openid", null, "违规主题名", null);

        assertTrue(v.isBlocked());
        assertEquals(TopicTextSafety.BLOCK_MESSAGE, v.getBlockMessage());
    }

    @Test
    void riskyStoryEntry_isRefusedWithFieldName_evenAfterAnEarlierReviewEntry() {
        List<JourneyReviewTexts.Entry> entries = Arrays.asList(
                new JourneyReviewTexts.Entry("journeyStory.call.opener", "待复核开场白"),
                new JourneyReviewTexts.Entry("journeyStory.ending.ally", "违规尾声"));

        TopicTextSafety.Verdict v = TopicTextSafety.check(gate, 1L, "openid", 7L, "正常主题名", entries);

        assertTrue(v.isBlocked(), "前一条只是要人工,不能让后一条违规漏过");
        assertTrue(v.getBlockMessage().contains("journeyStory.ending.ally"), v.getBlockMessage());
    }

    @Test
    void reviewSuggestedStoryEntry_passesFlagged() {
        List<JourneyReviewTexts.Entry> entries = Arrays.asList(
                new JourneyReviewTexts.Entry("journeyStory.call.opener", "待复核开场白"));

        TopicTextSafety.Verdict v = TopicTextSafety.check(gate, 1L, "openid", 7L, "正常主题名", entries);

        assertFalse(v.isBlocked());
        assertTrue(v.isFlagged());
    }

    @Test
    void cleanText_passesWithoutFlag() {
        TopicTextSafety.Verdict v = TopicTextSafety.check(gate, 1L, "openid", null, "正常主题名",
                Arrays.asList(new JourneyReviewTexts.Entry("finishMedalName", "通关")));

        assertFalse(v.isBlocked());
        assertFalse(v.isFlagged());
    }

    @Test
    void missingOpenid_passesFlagged_notRefused() {
        TopicTextSafety.Verdict v = TopicTextSafety.check(gate, 1L, "", null, "正常主题名", null);

        assertFalse(v.isBlocked());
        assertEquals("IDENTITY_REVIEW", v.getFlagReason());
    }

    @Test
    void changedOnly_skipsSavedTexts_keepsNewOnes() {
        Set<String> known = new HashSet<String>();
        TopicTextSafety.addJsonTexts(known, "{\"call\":{\"opener\":\"旧开场白\"},\"n\":3}");
        List<JourneyReviewTexts.Entry> changed = TopicTextSafety.changedOnly(Arrays.asList(
                new JourneyReviewTexts.Entry("journeyStory.call.opener", "旧开场白"),
                new JourneyReviewTexts.Entry("journeyStory.ending.ally", "新尾声")), known);

        assertEquals(1, changed.size());
        assertEquals("新尾声", changed.get(0).getText());
        assertTrue(TopicTextSafety.changed("新名", "旧名"));
        assertFalse(TopicTextSafety.changed(null, "旧名"), "没送 = 不改");
        assertFalse(TopicTextSafety.changed("旧名", "旧名"));
    }
}
