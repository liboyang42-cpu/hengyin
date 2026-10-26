package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-26:自玩会话没有(或过期)通行证时,{@code /api/play/nodes} 必须回 402「请先购买自玩通行证」,
 * 而不是 registered=false 让前端落成「你还没有报名这个场次」+「去报名」——自玩没有报名这个动作,
 * 那条指引把用户送去票务报名,永远解决不了他的问题。
 *
 * <p>写入口(/api/play/*)早就由 resolveCtx 回 402,只有读取入口 /nodes 是漏网的:
 * 前端已经能识别 402 落 needPass 空态(见 pages/play/index.js),所以缺的就是后端这个语义。
 *
 * <p>负控:把 {@code if (pass == null) return new AjaxResult(402, ...)} 撤掉,第一条测试立刻红。
 */
class ApiPlaySelfPlayPassGateTest {

    private static final Long MEMBER = 71L;
    private static final Long TOPIC = 73L;

    private ApiPlayProgressController controller;
    private ICmsRegistrationService registrationService;
    private ICmsTopicNodeService nodeService;

    @BeforeEach
    void setUp() {
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        registrationService = mock(ICmsRegistrationService.class);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        nodeService = mock(ICmsTopicNodeService.class);
        NpcProfileService npcProfileService = mock(NpcProfileService.class);

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
        topic.setProductType(2);   // 自由探索(自玩)
        topic.setStartDate(new Date(System.currentTimeMillis() - 86400000L));
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);

        CmsTopicNode node = new CmsTopicNode();
        node.setId(101L);
        node.setTopicId(TOPIC);
        node.setName("幸会咖啡");
        node.setStatus(1L);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
        com.chengyinhub.web.controller.api.NodeHintUnlockTestFixtures.pricing(controller, fixtureHintSettings);
    }

    private static CmsRegistration pass(Date expiresAt) {
        CmsRegistration r = new CmsRegistration();
        r.setMemberId(MEMBER);
        r.setOwnerType(1L);
        r.setOwnerId(TOPIC);
        r.setRegistrationStatus(2);
        r.setExpiresAt(expiresAt);
        return r;
    }

    @Test
    void selfPlayWithoutPassGetsPassPromptNotSignupCopy() {
        when(registrationService.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());

        AjaxResult r = controller.nodes(null, TOPIC);

        assertEquals(402, r.get(AjaxResult.CODE_TAG));
        assertEquals("请先购买自玩通行证", r.get(AjaxResult.MSG_TAG));
        // 没通行证就不该去读节点:读取即下发,越早挡越干净
        verify(nodeService, never()).selectCmsTopicNodeList(any());
    }

    @Test
    void selfPlayExpiredPassAlsoGetsPassPrompt() {
        when(registrationService.selectCmsRegistrationList(any()))
                .thenReturn(Collections.singletonList(pass(new Date(System.currentTimeMillis() - 60000L))));

        AjaxResult r = controller.nodes(null, TOPIC);

        assertEquals(402, r.get(AjaxResult.CODE_TAG), "过期通行证不能当有效:否则过期的还能继续玩");
        assertEquals("请先购买自玩通行证", r.get(AjaxResult.MSG_TAG));
    }

    @Test
    void validPassStillReturnsNodesAndKeepsRegistrationTrue() {
        when(registrationService.selectCmsRegistrationList(any()))
                .thenReturn(Collections.singletonList(pass(new Date(System.currentTimeMillis() + 86400000L))));

        AjaxResult r = controller.nodes(null, TOPIC);

        assertEquals(200, r.get(AjaxResult.CODE_TAG));
        verify(nodeService).selectCmsTopicNodeList(any());
    }

    /** 2026-10-14 用户已定：自玩通行证在本局内有效。已开局的本局中途到期，节点列表照常下发；未开局仍 402。 */
    @Test
    void expiredPassStillListsNodesOfARouteAlreadyStarted() {
        CmsRegistration expired = pass(new Date(System.currentTimeMillis() - 60000L));
        expired.setId(79L); expired.setPaymentStatus(0); expired.setPurchaseKind(2);
        when(registrationService.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(expired));
        com.chengyinhub.business.mapper.CmsRegistrationMapper mapper = mock(com.chengyinhub.business.mapper.CmsRegistrationMapper.class);
        when(mapper.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(expired));
        when(mapper.selectCmsRegistrationByIdForUpdate(79L)).thenReturn(expired);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationMapper", mapper);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "currentPlayActivityMapper", mock(com.chengyinhub.business.mapper.CmsActivityMapper.class));
        com.chengyinhub.business.service.ITopicRouteRuntimeService routes = mock(com.chengyinhub.business.service.ITopicRouteRuntimeService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicRouteRuntimeService", routes);

        when(routes.hasRunningSession(MEMBER, TOPIC, 0L)).thenReturn(true);
        assertEquals(200, controller.nodes(null, TOPIC).get(AjaxResult.CODE_TAG), "已开局：中途到期仍下发本局节点");

        when(routes.hasRunningSession(MEMBER, TOPIC, 0L)).thenReturn(false);
        assertEquals(402, controller.nodes(null, TOPIC).get(AjaxResult.CODE_TAG), "未开局：到期后回购票态 402");
    }
}
