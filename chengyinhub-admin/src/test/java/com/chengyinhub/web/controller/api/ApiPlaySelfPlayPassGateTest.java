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
        ReflectionTestUtils.setField(controller, "cmsActivityService", activityService);
        ReflectionTestUtils.setField(controller, "cmsRegistrationService", registrationService);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "cmsTopicNodeService", nodeService);
        ReflectionTestUtils.setField(controller, "npcProfileService", npcProfileService);
        ReflectionTestUtils.setField(controller, "playerNodeProgressService",
                mock(IPlayerNodeProgressService.class));
        ReflectionTestUtils.setField(controller, "cmsPointsSettingService",
                mock(ICmsPointsSettingService.class));
        ReflectionTestUtils.setField(controller, "cmsMemberTemplateService",
                mock(ICmsMemberTemplateService.class));
        ReflectionTestUtils.setField(controller, "omsTicketService", mock(IOmsTicketService.class));
        ReflectionTestUtils.setField(controller, "cmsTopicChapterService", mock(ICmsTopicChapterService.class));
        ReflectionTestUtils.setField(controller, "puzzlePlayService", mock(IPuzzlePlayService.class));
        ReflectionTestUtils.setField(controller, "topicOnsiteMetricsMapper",
                mock(com.chengyinhub.business.mapper.TopicOnsiteMetricsMapper.class));
        ReflectionTestUtils.setField(controller, "topicTemplateMetaMapper",
                mock(com.chengyinhub.business.mapper.CmsTopicTemplateMetaMapper.class));
        ReflectionTestUtils.setField(controller, "merchantChapterOfferMapper",
                mock(com.chengyinhub.business.mapper.MerchantChapterOfferMapper.class));
        ReflectionTestUtils.setField(controller, "coopPerkMapper",
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
}
