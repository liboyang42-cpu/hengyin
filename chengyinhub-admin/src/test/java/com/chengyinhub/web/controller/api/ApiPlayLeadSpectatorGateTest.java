package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.club.ClubEventLeadResolver;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPuzzlePlayService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * [F-37] 俱乐部领队是运营身份,本来就不报名本场。此前 /api/play/nodes 只回
 * registered=false,前端据此落「你还没有报名这个场次」空态,于是领队连页面都进不去,
 * 发车/广播/放行章节全都够不着。
 *
 * <p>本类钉住两件相反的事:领队要能被认出来(leadSpectator=true),
 * 以及它<b>只</b>解锁渲染 —— registered 必须仍是 false,玩家权益都挂在它身上。
 */
class ApiPlayLeadSpectatorGateTest {
    private static final Long LEADER = 9009L;
    private static final Long STRANGER = 7777L;
    private static final Long ACTIVITY = 18L;
    private static final Long TOPIC = 990028L;
    private static final Long CLUB = 7001L;

    private ApiPlayProgressController controller;
    private ClubEventLeadResolver leadResolver;

    @BeforeEach
    void setUp() {
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
        leadResolver = mock(ClubEventLeadResolver.class);

        controller = spy(new ApiPlayProgressController());
        doReturn(LEADER).when(controller).getAppUserId();
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activityService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrationService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topicService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "clubEventLeadResolver", leadResolver);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService",
                mock(com.chengyinhub.business.service.IPlayerNodeProgressService.class));
        var fixtureHintSettings = mock(com.chengyinhub.business.service.ICmsPointsSettingService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsMemberTemplateService",
                mock(com.chengyinhub.business.service.ICmsMemberTemplateService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "omsTicketService",
                mock(com.chengyinhub.business.service.IOmsTicketService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicChapterService", mock(ICmsTopicChapterService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "puzzlePlayService", mock(IPuzzlePlayService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "npcProfileService",
                mock(com.chengyinhub.business.service.impl.NpcProfileService.class));

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        activity.setClubId(CLUB);
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        // 谁都没报名:领队就是没买票的那个人,这正是本用例的前提。
        when(registrationService.selectCmsRegistrationList(any()))
                .thenReturn(Collections.<com.chengyinhub.business.domain.CmsRegistration>emptyList());

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setProductType(2);
        topic.setRouteMode(TopicRouteState.LINEAR);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);
        // O-08:未报名者看节点要求主题对其公开可见;本夹具的主题是公开在架主题。
        when(topicService.selectCmsTopicDetailById(eq(TOPIC), any())).thenReturn(topic);

        CmsTopicNode node = new CmsTopicNode();
        node.setId(101L);
        node.setTopicId(TOPIC);
        node.setName("咖啡");
        node.setSortId(1L);
        node.setStatus(1L);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
        com.chengyinhub.web.controller.api.NodeHintUnlockTestFixtures.pricing(controller, fixtureHintSettings);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nodesData() {
        AjaxResult response = controller.nodes(ACTIVITY, null);
        assertEquals(200, response.get("code"));
        return (Map<String, Object>) response.get("data");
    }

    @Test
    void unregisteredEventLeadIsMarkedSpectatorButNeverCountedAsRegistered() {
        when(leadResolver.resolveLeaderMemberId(any(CmsActivity.class))).thenReturn(LEADER);

        Map<String, Object> data = nodesData();

        assertTrue((Boolean) data.get("leadSpectator"),
                "本场领队必须被认出来,否则页面停在「你还没有报名这个场次」");
        assertFalse((Boolean) data.get("registered"),
                "运营权不等于玩家领奖权:registered 仍必须是 false");
    }

    @Test
    void unregisteredStrangerIsNotASpectator() {
        when(leadResolver.resolveLeaderMemberId(any(CmsActivity.class))).thenReturn(STRANGER);

        Map<String, Object> data = nodesData();

        assertFalse((Boolean) data.get("leadSpectator"),
                "不是本场领队的未报名者不得被放进来");
        assertFalse((Boolean) data.get("registered"));
    }
}
