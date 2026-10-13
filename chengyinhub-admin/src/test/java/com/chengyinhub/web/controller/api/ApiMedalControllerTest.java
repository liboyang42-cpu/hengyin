package com.chengyinhub.web.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.common.core.domain.AjaxResult;

/** /api/medal/wall 的响应字段契约。 */
class ApiMedalControllerTest {

    private static final long MEMBER_ID = 42L;
    private static final String CITY_CONDITION = "完成绑定此勋章模板的城市节点";

    private IPlayerNodeProgressService playerNodeProgressService;
    private ICmsTopicNodeService cmsTopicNodeService;
    private ICmsMemberTemplateService cmsMemberTemplateService;
    private PlayerBadgeMapper playerBadgeMapper;
    private ApiMedalController controller;

    @BeforeEach
    void setUp() {
        playerNodeProgressService = mock(IPlayerNodeProgressService.class);
        cmsTopicNodeService = mock(ICmsTopicNodeService.class);
        cmsMemberTemplateService = mock(ICmsMemberTemplateService.class);
        playerBadgeMapper = mock(PlayerBadgeMapper.class);
        controller = new ApiMedalController();
        ReflectionTestUtils.setField(controller, "playerNodeProgressService", playerNodeProgressService);
        ReflectionTestUtils.setField(controller, "cmsTopicNodeService", cmsTopicNodeService);
        ReflectionTestUtils.setField(controller, "cmsMemberTemplateService", cmsMemberTemplateService);
        ReflectionTestUtils.setField(controller, "playerBadgeMapper", playerBadgeMapper);
    }

    @Test
    void wall_template_row_has_explicit_condition_and_achievement_has_none() {
        PlayerNodeProgress progress = new PlayerNodeProgress();
        progress.setNodeId(7L);
        progress.setTopicId(9L);
        progress.setStatus(1); // 正式完成事实(status=0 到店行只作到达展示,不算完成)
        progress.setCheckinTime(new Date());
        when(playerNodeProgressService.selectPlayerNodeProgressList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Collections.singletonList(progress));

        CmsTopicNode node = new CmsTopicNode();
        node.setTemplateId(18L);
        when(cmsTopicNodeService.selectCmsTopicNodeById(7L)).thenReturn(node);

        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setMedalImg("/medal/city.png");
        template.setMedalName("城市纪念章");
        when(cmsMemberTemplateService.selectCmsMemberTemplateById(18L)).thenReturn(template);

        PlayerBadge achievement = new PlayerBadge();
        achievement.setBadgeCode("ROAM_TILES_50");
        achievement.setBadgeName("城市点亮 50 格");
        achievement.setObtainTime(new Date());
        when(playerBadgeMapper.selectListByMember(MEMBER_ID)).thenReturn(Collections.singletonList(achievement));

        ApiMedalController loggedIn = spy(controller);
        doReturn(MEMBER_ID).when(loggedIn).getAppUserId();

        AjaxResult response = loggedIn.wall();
        assertEquals(200, response.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> medals = (List<Map<String, Object>>) data.get("medals");
        assertEquals(2, medals.size());

        Map<String, Object> city = medals.get(0);
        assertEquals(CITY_CONDITION, city.get("condition"));

        Map<String, Object> earned = medals.get(1);
        assertEquals("achievement", earned.get("kind"));
        assertFalse(earned.containsKey("condition"), "成就徽章不能继承城市节点条件");
        assertNotNull(earned.get("getTime"));
    }

    @Test
    void wall_arrivalOnly_row_does_not_earn_template_medal() {
        PlayerNodeProgress arrivedOnly = new PlayerNodeProgress();
        arrivedOnly.setNodeId(7L);
        arrivedOnly.setTopicId(9L);
        arrivedOnly.setStatus(0); // mode2 到店/凭证行:不是完成
        arrivedOnly.setCheckinTime(new Date());
        when(playerNodeProgressService.selectPlayerNodeProgressList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Collections.singletonList(arrivedOnly));

        CmsTopicNode node = new CmsTopicNode();
        node.setTemplateId(18L);
        when(cmsTopicNodeService.selectCmsTopicNodeById(7L)).thenReturn(node);

        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setMedalImg("/medal/city.png");
        template.setMedalName("城市纪念章");
        when(cmsMemberTemplateService.selectCmsMemberTemplateById(18L)).thenReturn(template);
        when(playerBadgeMapper.selectListByMember(MEMBER_ID)).thenReturn(Collections.emptyList());

        ApiMedalController loggedIn = spy(controller);
        doReturn(MEMBER_ID).when(loggedIn).getAppUserId();

        AjaxResult response = loggedIn.wall();
        assertEquals(200, response.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> medals = (List<Map<String, Object>>) data.get("medals");
        assertEquals(0, medals.size(), "仅到店(status=0)不得发模板勋章");
        assertEquals(0, data.get("count"));
    }
}
