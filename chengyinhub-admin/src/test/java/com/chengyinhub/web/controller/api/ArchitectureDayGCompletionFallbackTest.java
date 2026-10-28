package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * G→C:无票的探索主题不得借 classic 节点规则宣称通关。
 * 本基线源码预计第一个测试为红；不 Disabled、不把错误行为改成期望通过。
 * G 不改 C 的 controller；由 C 将本测试连同修复运行转绿后集成。
 */
class ArchitectureDayGCompletionFallbackTest {
    private ApiPlayProgressController controller;
    private IPlayerNodeProgressService progress;
    private ICmsTopicService topics;
    private ICmsTopicNodeService nodes;
    private IPerkLedgerService perks;

    @BeforeEach
    void setup() {
        controller = spy(new ApiPlayProgressController());
        doReturn(7L).when(controller).getAppUserId();
        com.chengyinhub.business.service.support.CompletedRouteFactReader completed =
                mock(com.chengyinhub.business.service.support.CompletedRouteFactReader.class);
        when(completed.findCurrentCompletedRoutes(7L)).thenReturn(List.of());
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "completedRouteFactReader", completed);
        progress = mock(IPlayerNodeProgressService.class);
        topics = mock(ICmsTopicService.class);
        nodes = mock(ICmsTopicNodeService.class);
        perks = mock(IPerkLedgerService.class);
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        ITopicRouteRuntimeService routes = mock(ITopicRouteRuntimeService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progress);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrations);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", mock(ICmsActivityService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topics);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodes);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "perkLedgerService", perks);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicRouteRuntimeService", routes);
        when(registrations.selectCmsRegistrationList(any(com.chengyinhub.business.domain.CmsRegistration.class))).thenReturn(List.of());
        when(routes.sessionStatuses(any())).thenReturn(Map.of());
        CmsTopic topic = new CmsTopic();
        topic.setId(301L);
        topic.setName("需要有效探索票");
        topic.setProductType(ProductType.FREE_EXPLORE);
        when(topics.selectCmsTopicById(301L)).thenReturn(topic);
        CmsTopicNode node = new CmsTopicNode();
        node.setId(401L);
        node.setTopicId(301L);
        node.setStatus(1L);
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(List.of(node));
    }

    @Test
    void completedGameNodeWithoutExploreTicketDoesNotBecomeACompletedExploreProduct() {
        when(progress.selectPlayerNodeProgressList(any())).thenReturn(List.of(progress(1)));

        AjaxResult result = controller.myCompleted();

        assertEquals(200, result.get("code"));
        assertTrue(((List<?>) result.get("data")).isEmpty(),
                "FREE_EXPLORE 无有效票必须 fail-closed，不能 fallback 到 done>=total 的经典规则");
        verifyNoInteractions(perks);
    }

    @Test
    void arrivalOnlyRowDoesNotBecomeNodeOrThemeCompletion() {
        when(progress.selectPlayerNodeProgressList(any())).thenReturn(List.of(progress(0)));

        AjaxResult result = controller.myCompleted();

        assertEquals(200, result.get("code"));
        assertTrue(((List<?>) result.get("data")).isEmpty());
        verifyNoInteractions(perks);
    }

    private PlayerNodeProgress progress(int status) {
        PlayerNodeProgress row = new PlayerNodeProgress();
        row.setId(51L);
        row.setMemberId(7L);
        row.setActivityId(0L);
        row.setTopicId(301L);
        row.setNodeId(401L);
        row.setPlayInstanceId("default");
        row.setStatus(status);
        return row;
    }
}
