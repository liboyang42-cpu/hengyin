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

/**
 * FE-STORY 5.6 / T-B6:城市定向(mode 1)的 /api/play/nodes 在自由探索改动前后逐字节相同。
 *
 * <p>快照 {@code snapshots/city-route-nodes-mode1.json} 是在改动前的 master({@code 65498f6d},
 * F08/F07/FE 剧情之前)上用同一夹具录下的。夹具里两站都已到达,所以 F07 的「到站前隐藏站图」不参与,
 * 任何自由探索字段(visited / storyLocked / visitCheck / storyUnlocked …)出现在城市定向里都会让本测试变红。
 * 重新录制:环境变量 {@code CITY_SNAPSHOT_RECORD=<目标文件>}。
 */
class CityRouteNodesSnapshotTest
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

    private static com.chengyinhub.business.domain.PlayerNodeProgress arrived(long nodeId, long epochMillis) {
        com.chengyinhub.business.domain.PlayerNodeProgress p = new com.chengyinhub.business.domain.PlayerNodeProgress();
        p.setMemberId(MEMBER);
        p.setTopicId(TOPIC);
        p.setActivityId(0L);
        p.setNodeId(nodeId);
        p.setStatus(0);
        p.setCheckinTime(new java.util.Date(epochMillis));
        return p;
    }

    @Test
    void cityRouteNodesResponseIsUnchanged() throws Exception
    {
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(java.util.Arrays.asList(
                arrived(NODE, 1_700_000_000_000L), arrived(NODE + 1, 1_700_000_600_000L)));
        AjaxResult result = controller.nodes(null, TOPIC);
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        String actual = json.writerWithDefaultPrettyPrinter().writeValueAsString(
                json.readTree(json.writeValueAsBytes(result)));
        String record = System.getenv("CITY_SNAPSHOT_RECORD");
        if (record != null && !record.isEmpty()) {
            java.nio.file.Files.write(java.nio.file.Paths.get(record), actual.getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (java.io.InputStream in = getClass().getResourceAsStream("/snapshots/city-route-nodes-mode1.json")) {
            org.junit.jupiter.api.Assertions.assertNotNull(in, "缺少快照文件");
            String expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(expected.trim(), actual.trim(), "城市定向 /nodes 与改动前快照不一致");
        }
    }
}
