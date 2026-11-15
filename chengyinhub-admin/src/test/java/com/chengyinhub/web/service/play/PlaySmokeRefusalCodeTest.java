package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.dto.PreferenceTagCorrectionRequest;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IPlayerTopicTagService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.service.PreferencePlayService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SMOKE-500-R4：偏好标签 / 偏好题 / 历史回看的「不存在」类拒绝不再被就地 catch 压成 500，文案原样保留。
 */
class PlaySmokeRefusalCodeTest
{
    private static void assertRefusal(AjaxResult r, int code, String msg)
    {
        assertEquals(code, r.get(AjaxResult.CODE_TAG));
        assertEquals(msg, r.get(AjaxResult.MSG_TAG));
    }

    @Test
    void preferenceTagRefusalsKeepTheIllegalArgument400()
    {
        PlayPreferenceApplication app = new PlayPreferenceApplication();
        IPlayerTopicTagService tags = mock(IPlayerTopicTagService.class);
        PreferencePlayService preferences = mock(PreferencePlayService.class);
        ReflectionTestUtils.setField(app, "playerTopicTagService", tags);
        ReflectionTestUtils.setField(app, "preferencePlayService", preferences);
        when(tags.confirm(7L, 99L)).thenThrow(new IllegalArgumentException("标签不存在或已撤回"));
        when(tags.revoke(7L, 99L)).thenThrow(new IllegalArgumentException("标签不存在"));
        when(preferences.correctPending(7L, 99L, "smoke")).thenThrow(new IllegalArgumentException("标签不存在"));
        PreferenceTagCorrectionRequest correction = new PreferenceTagCorrectionRequest();
        correction.setTagValue("smoke");

        assertRefusal(app.confirmPreferenceTag(7L, 99L), HttpStatus.BAD_REQUEST, "标签不存在或已撤回");
        assertRefusal(app.revokePreferenceTag(7L, 99L), HttpStatus.BAD_REQUEST, "标签不存在");
        assertRefusal(app.correctPreferenceTag(7L, 99L, correction), HttpStatus.BAD_REQUEST, "标签不存在");
    }

    @Test
    void preferenceReadOnMissingNodeIs404()
    {
        PlayPreferenceApplication app = new PlayPreferenceApplication();
        ICmsTopicNodeService nodes = mock(ICmsTopicNodeService.class);
        PreferencePlayService preferences = mock(PreferencePlayService.class);
        ReflectionTestUtils.setField(app, "cmsTopicNodeService", nodes);
        ReflectionTestUtils.setField(app, "preferencePlayService", preferences);
        when(nodes.selectCmsTopicNodeById(992021L)).thenReturn(null);

        assertRefusal(app.preference(7L, 992021L, "992034", "992001"), HttpStatus.NOT_FOUND, "节点不存在");
        verifyNoInteractions(preferences);
    }

    @Test
    void historyOfForeignOrMissingRunIs404()
    {
        PlayHistoryQueryApplication app = new PlayHistoryQueryApplication();
        TopicRouteSessionMapper sessions = mock(TopicRouteSessionMapper.class);
        ReflectionTestUtils.setField(app, "historySessionMapper", sessions);
        when(sessions.selectAccessibleById(any(), any())).thenReturn(null);

        assertRefusal(app.history(7L, 992034L, 992001L, 993801L), HttpStatus.NOT_FOUND, "本局不存在或无权查看");
    }
}
