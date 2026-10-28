package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.vo.ai.NpcProfileVO;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsPointsSettingService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IPuzzlePlayService;
import com.chengyinhub.business.service.impl.NpcProfileService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * /api/play/nodes 的节点 NPC 出场规则(CR-927):
 * 商家给点位配了节点 NPC(scope_type=4)时优先出场,没配才回落门店分身(scope_type=2)。
 *
 * <p>为什么必须测这一层:门店分身是「店铺替身」,一条店可以挂多个点位;
 * 若 preference 写反/漏掉,配了节点 NPC 也看不见(静默),或存量点位的店分身凭空消失。
 */
class ApiPlayNodeNpcPreferenceTest {

    private static final Long MEMBER = 71L;
    private static final Long TOPIC = 73L;
    private static final Long NODE = 101L;
    private static final Long MERCHANT = 501L;

    private ApiPlayProgressController controller;
    private NpcProfileService npcProfileService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
        npcProfileService = mock(NpcProfileService.class);

        controller = spy(new ApiPlayProgressController());
        doReturn(MEMBER).when(controller).getAppUserId();
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activityService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrationService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topicService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "npcProfileService", npcProfileService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService",
                mock(IPlayerNodeProgressService.class));
        var fixtureHintSettings = mock(ICmsPointsSettingService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsMemberTemplateService",
                mock(ICmsMemberTemplateService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "omsTicketService", mock(IOmsTicketService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicChapterService", mock(ICmsTopicChapterService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "puzzlePlayService", mock(IPuzzlePlayService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicOnsiteMetricsMapper",
                mock(com.chengyinhub.business.mapper.TopicOnsiteMetricsMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicTemplateMetaMapper",
                mock(com.chengyinhub.business.mapper.CmsTopicTemplateMetaMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "merchantChapterOfferMapper",
                mock(com.chengyinhub.business.mapper.MerchantChapterOfferMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "coopPerkMapper",
                mock(com.chengyinhub.business.mapper.CoopPerkMapper.class));

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setProductType(2);   // 自由探索
        topic.setStartDate(new Date(System.currentTimeMillis() - 86400000L));
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);

        CmsRegistration pass = new CmsRegistration();
        pass.setMemberId(MEMBER);
        pass.setExpiresAt(new Date(System.currentTimeMillis() + 30L * 86400000L));
        when(registrationService.selectCmsRegistrationList(any()))
                .thenReturn(Collections.singletonList(pass));

        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(TOPIC);
        node.setChapterId(9L);
        node.setMerchantMemberId(MERCHANT);
        node.setName("幸会咖啡");
        node.setBusinessTime("10:00-20:00");
        node.setStatus(1L);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
        com.chengyinhub.web.controller.api.NodeHintUnlockTestFixtures.pricing(controller, fixtureHintSettings);
    }

    private static NpcProfileVO npc(String name) {
        NpcProfileVO vo = new NpcProfileVO();
        vo.setName(name);
        vo.setGreeting("欢迎光临");
        return vo;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstNode() {
        AjaxResult response = controller.nodes(null, TOPIC);
        assertEquals(200, response.get("code"), String.valueOf(response));
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) data.get("nodes");
        assertEquals(1, nodes.size());
        return nodes.get(0);
    }

    @Test
    void nodeNpcTakesPrecedenceOverStoreNpc() {
        when(npcProfileService.resolveForNodeOrMerchant(NODE, MERCHANT)).thenReturn(npc("阿福"));

        Map<String, Object> node = firstNode();

        @SuppressWarnings("unchecked")
        Map<String, Object> npc = (Map<String, Object>) node.get("npc");
        assertEquals("阿福", npc.get("name"));
        assertEquals("欢迎光临", npc.get("greeting"));
        assertFalse(npc.containsKey("persona"), "persona 是喂模型的提示词,永远不下发");
    }

    @Test
    void fallsBackToStoreNpcWhenNodeNpcAbsent() {
        when(npcProfileService.resolveForNodeOrMerchant(NODE, MERCHANT)).thenReturn(npc("店小二"));

        Map<String, Object> node = firstNode();

        @SuppressWarnings("unchecked")
        Map<String, Object> npc = (Map<String, Object>) node.get("npc");
        assertEquals("店小二", npc.get("name"), "没配节点 NPC = 店分身照旧出场");
    }

    @Test
    void noNpcAtAllLeavesTheFieldAbsent() {
        when(npcProfileService.resolveForNodeOrMerchant(NODE, MERCHANT)).thenReturn(null);

        Map<String, Object> node = firstNode();

        assertFalse(node.containsKey("npc"), "两边都没有时不塞一个空对象,前端据 null 判空态");
    }
}
