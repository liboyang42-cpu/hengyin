package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** F07:城市定向后续站点照片到站前不下发(/api/play/nodes)。 */
class PlayNodeQueryStationImageTest
{
    private static final long MEMBER = 88L;
    private static final long TOPIC = 301L;
    private static final long NODE = 401L;
    private static final long TEMPLATE = 501L;

    private ApiPlayProgressController controller;
    private PlayAttemptLogMapper attemptMapper;
    private IPlayerNodeProgressService progressService;

    @BeforeEach
    void setUp()
    {
        controller = spy(new ApiPlayProgressController());
        org.mockito.Mockito.doReturn(MEMBER).when(controller).getAppUserId();

        ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(TOPIC);
        node.setTemplateId(TEMPLATE);
        node.setSortId(1L);
        when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(node);
        node.setImgUrl("/station-1.jpg");
        CmsTopicNode second = new CmsTopicNode();
        second.setId(NODE + 1);
        second.setTopicId(TOPIC);
        second.setSortId(2L);
        second.setImgUrl("/station-2.jpg");
        second.setStatus(1L);
        when(nodeService.selectCmsTopicNodeById(NODE + 1)).thenReturn(second);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(java.util.Arrays.asList(second, node));

        ICmsMemberTemplateService templateService = mock(ICmsMemberTemplateService.class);
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(TEMPLATE);
        template.setValidationMethod(1);
        template.setHint1("先看门楣上的年份");
        template.setHint2("答案是门楣上最左边的四位数");
        when(templateService.selectCmsMemberTemplateById(TEMPLATE)).thenReturn(template);

        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        CmsRegistration pass = new CmsRegistration();
        pass.setMemberId(MEMBER);
        pass.setOwnerType(1L);
        pass.setOwnerId(TOPIC);
        pass.setRegistrationStatus(2);
        when(registrationService.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(pass));

        ICmsTopicService topicService = mock(ICmsTopicService.class);
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setProductType(ProductType.ORIENTEERING);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);

        attemptMapper = mock(PlayAttemptLogMapper.class);
        when(attemptMapper.insertPlayAttemptLog(any())).thenReturn(1);

        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsMemberTemplateService", templateService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "nodeMerchantOverrideService",
                new com.chengyinhub.business.service.impl.NodeMerchantOverrideServiceImpl(
                        mock(com.chengyinhub.business.service.ICmsRegistrationMerchantService.class)));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrationService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topicService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "omsTicketService", mock(IOmsTicketService.class));
        progressService = mock(IPlayerNodeProgressService.class);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.emptyList());
        when(progressService.insertPlayerNodeProgress(any())).thenReturn(1);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progressService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playAttemptLogMapper", attemptMapper);
        com.chengyinhub.business.mapper.PlayerNodeProgressMapper progressMapper =
                mock(com.chengyinhub.business.mapper.PlayerNodeProgressMapper.class);
        when(progressMapper.selectPlayerNodeProgressList(any())).thenReturn(Collections.emptyList());
        com.chengyinhub.business.mapper.UmsMemberMapper memberMapper =
                mock(com.chengyinhub.business.mapper.UmsMemberMapper.class);
        when(memberMapper.lockMemberById(MEMBER)).thenReturn(MEMBER);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "puzzlePlayService",
                new com.chengyinhub.business.service.impl.PuzzlePlayServiceImpl(
                        templateService, attemptMapper, progressMapper, memberMapper,
                mock(com.chengyinhub.business.service.ICmsTopicNodeService.class),
                mock(com.chengyinhub.business.service.support.PlayNodeAccessService.class),
                mock(com.chengyinhub.business.service.ICmsPointsSettingService.class),
                mock(com.chengyinhub.business.service.IUmsMemberService.class),
                mock(com.chengyinhub.business.service.IUmsMemberPointsDetailService.class)));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "pointsAwardService",
                mock(com.chengyinhub.business.service.IPointsAwardService.class));
        com.chengyinhub.business.service.IPlayerProgressionService progression =
                mock(com.chengyinhub.business.service.IPlayerProgressionService.class);
        when(progression.onNodeDone(MEMBER)).thenReturn(Collections.emptyList());
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerProgressionService", progression);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "xpBudgetService",
                mock(com.chengyinhub.business.service.IXpBudgetService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "growthService",
                mock(com.chengyinhub.business.service.IGrowthService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "rewardDropService",
                mock(com.chengyinhub.business.service.IRewardDropService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "memberCollectibleMapper",
                mock(com.chengyinhub.business.mapper.MemberCollectibleMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicChapterService",
                mock(com.chengyinhub.business.service.ICmsTopicChapterService.class));
        CurrentPlayControllerFixtures.install(controller);
        BasicPlayCommandFixtures.scope(controller, TOPIC);
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<Long, java.util.Map<String, Object>> nodesById() {
        AjaxResult result = controller.nodes(null, TOPIC);
        java.util.Map<String, Object> data = (java.util.Map<String, Object>) result.get("data");
        java.util.Map<Long, java.util.Map<String, Object>> out = new java.util.HashMap<>();
        for (java.util.Map<String, Object> m : (java.util.List<java.util.Map<String, Object>>) data.get("nodes"))
            out.put(((Number) m.get("nodeId")).longValue(), m);
        return out;
    }

    @Test
    void laterStationPhotoIsHiddenUntilReached()
    {
        java.util.Map<Long, java.util.Map<String, Object>> nodes = nodesById();
        assertEquals("/station-1.jpg", nodes.get(NODE).get("imgUrl"));
        assertEquals(null, nodes.get(NODE).get("imgHidden"));
        assertEquals(null, nodes.get(NODE + 1).get("imgUrl"));
        assertEquals(Boolean.TRUE, nodes.get(NODE + 1).get("imgHidden"));
    }

    @Test
    void reachedStationPhotoIsShown()
    {
        com.chengyinhub.business.domain.PlayerNodeProgress arrived = new com.chengyinhub.business.domain.PlayerNodeProgress();
        arrived.setMemberId(MEMBER);
        arrived.setTopicId(TOPIC);
        arrived.setActivityId(0L);
        arrived.setNodeId(NODE + 1);
        arrived.setStatus(0);
        arrived.setCheckinTime(new java.util.Date());
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(arrived));

        java.util.Map<Long, java.util.Map<String, Object>> nodes = nodesById();
        assertEquals("/station-2.jpg", nodes.get(NODE + 1).get("imgUrl"), "扫码或点到达后本站照片可见");
        assertEquals(null, nodes.get(NODE + 1).get("imgHidden"));
    }
}
