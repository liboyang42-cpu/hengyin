package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;

/**
 * Beta 转正式 /api/topic/beta/graduate:
 * ①归属校验(IDOR)仅主题作者本人可转;②仅 beta_flag=1 的主题可转(WHERE 竞态安全);③成功归 0。
 */
class TopicBetaGraduateTest {

    private ICmsTopicService cmsTopicService;

    @BeforeEach
    void setUp() {
        cmsTopicService = mock(ICmsTopicService.class);
    }

    private ApiTopicController spyAs(long uid) {
        ApiTopicController c = org.mockito.Mockito.spy(new ApiTopicController());
        org.mockito.Mockito.doReturn(uid).when(c).getAppUserId();
        ReflectionTestUtils.setField(c, "cmsTopicService", cmsTopicService);
        return c;
    }

    private CmsTopic topicOwnedBy(long memberId) {
        CmsTopic t = new CmsTopic();
        t.setId(100L);
        t.setMemberId(memberId);
        t.setBetaFlag(1);
        return t;
    }

    @Test
    void nonAuthor_rejectedByAuthorGate() {
        ApiTopicController stranger = spyAs(8L); // 主题归属 9,调用者 8
        when(cmsTopicService.selectCmsTopicById(100L)).thenReturn(topicOwnedBy(9L));

        AjaxResult r = stranger.betaGraduate("100", null);

        assertEquals(500, ((Integer) r.get("code")).intValue());
        assertEquals("仅主题作者可转正式", r.get("msg"), "错误文案必须指明是作者闸拦下的");
        verify(cmsTopicService, never()).graduateBeta(anyLong());
    }

    @Test
    void author_graduatesBetaToZero() {
        ApiTopicController author = spyAs(9L);
        when(cmsTopicService.selectCmsTopicById(100L)).thenReturn(topicOwnedBy(9L));
        when(cmsTopicService.graduateBeta(100L)).thenReturn(1);

        AjaxResult r = author.betaGraduate("100", null);

        assertEquals(200, ((Integer) r.get("code")).intValue(), "作者本人转正应成功");
        verify(cmsTopicService).graduateBeta(100L);
    }

    @Test
    void notInBeta_rejected() {
        ApiTopicController author = spyAs(9L);
        when(cmsTopicService.selectCmsTopicById(100L)).thenReturn(topicOwnedBy(9L));
        when(cmsTopicService.graduateBeta(100L)).thenReturn(0); // WHERE beta_flag=1 未命中

        AjaxResult r = author.betaGraduate("100", null);

        assertEquals(500, ((Integer) r.get("code")).intValue());
        assertEquals("该主题不在 Beta 试玩期", r.get("msg"));
    }
}
