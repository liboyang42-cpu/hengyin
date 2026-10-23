package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.business.service.support.TopicAiPrecheckService;
import com.chengyinhub.business.service.support.TopicBundleReviewService;
import com.chengyinhub.business.service.support.TopicPublishCheckService;
import com.chengyinhub.business.service.support.TopicPublishCheckService.PublishCheckIssue;
import com.chengyinhub.business.service.support.TopicStateKeyRefService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DOC2 O5c-(c):试检查只在 AI 预检开关打开时追加其结果;关时与旧版逐项一致。 */
class TopicPublishCheckAiPrecheckWiringTest {

    private TopicPublishCheckService service;
    private TopicAiPrecheckService ai;

    @BeforeEach
    void setUp() {
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        CmsMemberTemplateMapper templateMapper = mock(CmsMemberTemplateMapper.class);
        service = new TopicPublishCheckService(nodeMapper, templateMapper,
                new ThemeStateKeyService(mock(ThemeStateKeyMapper.class)),
                new TopicStateKeyRefService(nodeMapper, templateMapper, mock(CmsTopicChapterMapper.class)),
                mock(TopicBundleReviewService.class));
        ai = mock(TopicAiPrecheckService.class);
    }

    @Test
    void notInjectedOrSwitchedOffAddsNothing() {
        assertTrue(service.check(simpleTopic()).isEmpty());
        service.setTopicAiPrecheckService(ai);
        when(ai.isEnabled()).thenReturn(false);
        assertTrue(service.check(simpleTopic()).isEmpty());
        verify(ai, never()).check(any(), anyList());
    }

    @Test
    void switchedOnAppendsPrecheckIssues() {
        service.setTopicAiPrecheckService(ai);
        when(ai.isEnabled()).thenReturn(true);
        when(ai.check(any(), anyList())).thenReturn(Collections.singletonList(
                PublishCheckIssue.warn(TopicAiPrecheckService.CODE_AI_PRECHECK_SKIPPED, null, "skip")));
        List<PublishCheckIssue> issues = service.check(simpleTopic());
        assertEquals(1, issues.size());
        assertEquals(TopicAiPrecheckService.CODE_AI_PRECHECK_SKIPPED, issues.get(0).getCode());
    }

    private static CmsTopic simpleTopic() {
        CmsTopic t = new CmsTopic();
        t.setId(21L);
        t.setMemberId(7L);
        t.setPublishMode("simple");
        t.setRouteMode("LINEAR");
        return t;
    }
}
