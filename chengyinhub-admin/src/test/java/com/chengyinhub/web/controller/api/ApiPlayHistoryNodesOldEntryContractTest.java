package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsPointsSettingService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IPuzzlePlayService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 规则16 旧入口业务反例(29E 补测)。
 *
 * <p>基线负控曾以「注入点不存在」报错(setUp 缺 wxContentCallbackInboxMapper 字段),
 * 那不是业务反例。本类只注入基线已存在的依赖、不走新注入点,把「已下架完成站的历史回读」
 * 与「下架站不得新完成」都压到真实方法入口上,使基线以业务断言差异失败,而非构造失败。
 * 复跑(基线快照 / 集成分支,离线,不占 root):
 * <pre>
 *   git archive &lt;基线&gt; | tar -x -C /tmp/base &amp;&amp; cp 本文件 /tmp/base/.../api/
 *   mvn -o -pl chengyinhub-admin -am -Dtest=ApiPlayHistoryNodesOldEntryContractTest \
 *       -Dsurefire.failIfNoSpecifiedTests=false test
 * </pre>
 */
class ApiPlayHistoryNodesOldEntryContractTest
{
    private static final long MEMBER = 71L;
    private static final long ACTIVITY = 72L;
    private static final long TOPIC = 73L;
    private static final long ACTIVE_NODE = 201L;
    private static final long DELISTED_DONE = 202L;
    private static final long DELISTED_ARRIVAL = 203L;

    private ApiPlayProgressController controller;
    private ICmsTopicNodeService nodeService;
    private IPlayerNodeProgressService progressService;
    private List<PlayerNodeProgress> progressRows;

    @BeforeEach
    void setUp()
    {
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        nodeService = mock(ICmsTopicNodeService.class);
        progressService = mock(IPlayerNodeProgressService.class);

        controller = spy(new ApiPlayProgressController());
        doReturn(MEMBER).when(controller).getAppUserId();
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activityService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrationService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topicService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progressService);
        var fixtureHintSettings = mock(ICmsPointsSettingService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsMemberTemplateService", mock(ICmsMemberTemplateService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "omsTicketService",
                mock(com.chengyinhub.business.service.IOmsTicketService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicChapterService", mock(ICmsTopicChapterService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "puzzlePlayService", mock(IPuzzlePlayService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicRouteRuntimeService", mock(ITopicRouteRuntimeService.class));

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);

        CmsRegistration registration = new CmsRegistration();
        registration.setMemberId(MEMBER);
        registration.setOwnerType(2L);
        registration.setOwnerId(ACTIVITY);
        registration.setRegistrationStatus(2);
        when(registrationService.selectCmsRegistrationList(any()))
                .thenReturn(Collections.singletonList(registration));

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setProductType(1);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);

        CmsTopicNode active = node(ACTIVE_NODE, "在线站", 1L, "在线碎片");
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(active));
        when(nodeService.selectCmsTopicNodeById(DELISTED_DONE))
                .thenReturn(node(DELISTED_DONE, "已下架完成站", 0L, "下架前的碎片"));
        when(nodeService.selectCmsTopicNodeById(DELISTED_ARRIVAL))
                .thenReturn(node(DELISTED_ARRIVAL, "仅到店站", 0L, "不该出现"));

        CurrentPlayControllerFixtures.install(controller);
        BasicPlayCommandFixtures.scope(controller, TOPIC);
        progressRows = new ArrayList<>(Arrays.asList(
                done(MEMBER, DELISTED_DONE, 1000L),
                arrival(MEMBER, DELISTED_ARRIVAL, 2000L),
                done(MEMBER, ACTIVE_NODE, 3000L)));
        when(progressService.selectPlayerNodeProgressList(any())).thenAnswer(inv -> progressRows);
        com.chengyinhub.web.controller.api.NodeHintUnlockTestFixtures.pricing(controller, fixtureHintSettings);
    }

    private CmsTopicNode node(long id, String name, Long status, String fragment)
    {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(id);
        n.setTopicId(TOPIC);
        n.setStatus(status);
        n.setSortId(id);
        n.setName(name);
        n.setDescription("描述-" + name);
        n.setFragmentText(fragment);
        return n;
    }

    private PlayerNodeProgress done(long memberId, long nodeId, long checkinAt)
    {
        PlayerNodeProgress p = new PlayerNodeProgress();
        p.setMemberId(memberId);
        p.setTopicId(TOPIC);
        p.setActivityId(ACTIVITY);
        p.setNodeId(nodeId);
        p.setStatus(1);
        p.setCheckinTime(new Date(checkinAt));
        return p;
    }

    private PlayerNodeProgress arrival(long memberId, long nodeId, long checkinAt)
    {
        PlayerNodeProgress p = done(memberId, nodeId, checkinAt);
        p.setStatus(0);
        return p;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nodesData()
    {
        AjaxResult result = controller.nodes(ACTIVITY, null);
        assertEquals(200, result.get("code"), String.valueOf(result.get("msg")));
        return (Map<String, Object>) result.get("data");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Map<String, Object> data, String key)
    {
        return (List<Map<String, Object>>) data.get(key);
    }

    @Test
    void delistedCompletedNodeHistoryReachableThroughNodesEntry()
    {
        Map<String, Object> data = nodesData();

        Object historyRaw = data.get("historyNodes");
        assertNotNull(historyRaw,
                "规则16:已下架完成站必须能被本人只读回读(旧入口当前没有任何历史通道)");
        List<Map<String, Object>> history = list(data, "historyNodes");
        assertEquals(1, history.size(), "仅正式完成的普通下架站进入历史");
        Map<String, Object> h = history.get(0);
        assertEquals(Long.valueOf(DELISTED_DONE), ((Number) h.get("nodeId")).longValue());
        assertEquals(Boolean.TRUE, h.get("done"));
        assertEquals(Boolean.TRUE, h.get("history"));
        assertEquals("下架前的碎片", h.get("fragmentText"));

        List<Map<String, Object>> playable = list(data, "nodes");
        assertEquals(1, playable.size(), "可玩集合仍只含在线节点,历史站不进分母");
        assertEquals(1, data.get("total"));
    }

    @Test
    void delistedNodeCannotBeNewlyCompletedThroughAnswerEntry()
    {
        // 负控收紧(§7-2):已完成者(status=1)的幂等重试现在走原回执豁免,不再是「新完成」;
        // 本用例钉住的正是**未完成**玩家——无本人完成行,下架闸必须原样拦住。
        progressRows.clear();
        AjaxResult answer = BasicPlayCommandFixtures.answer(controller, String.valueOf(ACTIVITY), String.valueOf(DELISTED_DONE), "A", null, null, null);

        assertEquals(500, answer.get("code"));
        assertEquals("该站已下架，暂时无法完成", answer.get("msg"),
                "旧链接/旧码不得对已下架节点产生新完成");
        verify(progressService, never()).insertPlayerNodeProgress(any());
    }

    @Test
    void delistedNodeCannotBeNewlyCheckedInThroughOldCodeEntry()
    {
        // 负控收紧(§7-2):同上,只钉未完成玩家的旧码不得产生新完成
        progressRows.clear();
        CmsTopicNode delisted = node(DELISTED_DONE, "已下架站", 0L, "碎片");
        delisted.setCheckinCode("OLD-CODE");
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(delisted));

        AjaxResult result = BasicPlayCommandFixtures.checkin(controller, String.valueOf(ACTIVITY), "OLD-CODE", null, null, null, null, String.valueOf(DELISTED_DONE));

        assertEquals(500, result.get("code"));
        assertEquals("该站已下架，暂时无法完成", result.get("msg"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
    }
}
