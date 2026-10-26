package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/** 已发布改回 LINEAR 后，既有 BRANCH session 仍是完成读侧的权威口径。 */
class TopicRouteFrozenSessionReadSideTest {

    private static final long MEMBER = 41L;
    private static final long TOPIC = 51L;
    private static final long ACTIVITY = 61L;
    private static final long CLUB = 71L;

    @SuppressWarnings("unchecked")
    @Test
    void myCompletedUsesFrozenBranchSessionEvenAfterPublishedRouteBecomesLinear() {
        ApiPlayProgressController controller = spy(new ApiPlayProgressController());
        doReturn(MEMBER).when(controller).getAppUserId();
        com.chengyinhub.business.service.support.CompletedRouteFactReader completedRoutes =
                mock(com.chengyinhub.business.service.support.CompletedRouteFactReader.class);
        when(completedRoutes.findCurrentCompletedRoutes(MEMBER)).thenReturn(Collections.emptyList());
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "completedRouteFactReader", completedRoutes);

        IPlayerNodeProgressService progressService = mock(IPlayerNodeProgressService.class);
        when(progressService.selectPlayerNodeProgressList(any()))
                .thenReturn(Collections.singletonList(progress(MEMBER, ACTIVITY, TOPIC, 1L)));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progressService);

        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        when(registrationService.selectCmsRegistrationList(any(com.chengyinhub.business.domain.CmsRegistration.class))).thenReturn(Collections.emptyList());
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrationService);

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        activity.setName("冻结分支活动");
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activityService);

        ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Arrays.asList(node(1L), node(2L)));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);

        CmsTopic publishedLinear = new CmsTopic();
        publishedLinear.setId(TOPIC);
        publishedLinear.setPublishedRouteGraphJson(null);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(publishedLinear);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topicService);

        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        when(runtime.sessionStatuses(any())).thenReturn(Collections.singletonMap(
                ITopicRouteRuntimeService.sessionKey(MEMBER, TOPIC, ACTIVITY),
                TopicRouteSession.STATUS_COMPLETED));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicRouteRuntimeService", runtime);

        AjaxResult result = controller.myCompleted();

        assertEquals(200, result.get("code"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("data");
        assertEquals(1, rows.size(), "冻结分支已完赛不能因新版发布 LINEAR 而从通关列表消失");
    }

    @SuppressWarnings("unchecked")
    @Test
    void clubStatsUnionsLegacyLinearAndFrozenBranchCohortsWithoutDoubleCounting() {
        ApiStatsController controller = spy(new ApiStatsController());
        doReturn(MEMBER).when(controller).getAppUserId();

        // 看板授权已从 CAP_CLUB_LEADER 能力位换成「显式 clubId + 该团主理人本人」，
        // 这里只需把授权前置喂通，本用例断言的仍是分支/线性完成口径的合并计数。
        IClubService clubService = mock(IClubService.class);
        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(MEMBER);
        club.setStatus(1L);
        club.setDelFlag(0);
        when(clubService.selectClubById(CLUB)).thenReturn(club);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "clubService", clubService);

        CmsTopic publishedLinear = new CmsTopic();
        publishedLinear.setId(TOPIC);
        publishedLinear.setName("混合代际主题");
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        when(topicService.selectCmsTopicList(any())).thenReturn(Collections.singletonList(publishedLinear));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topicService);

        ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Arrays.asList(node(1L), node(2L)));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);

        IPlayerNodeProgressService progressService = mock(IPlayerNodeProgressService.class);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(
                progress(1L, ACTIVITY, TOPIC, 1L),
                progress(2L, ACTIVITY, TOPIC, 1L), progress(2L, ACTIVITY, TOPIC, 2L),
                progress(3L, ACTIVITY, TOPIC, 1L), progress(3L, ACTIVITY, TOPIC, 2L)));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progressService);

        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        when(runtime.participantMemberIds(TOPIC))
                .thenReturn(new LinkedHashSet<Long>(Arrays.asList(1L, 3L)));
        when(runtime.completedMemberIds(TOPIC))
                .thenReturn(new LinkedHashSet<Long>(Collections.singletonList(1L)));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicRouteRuntimeService", runtime);

        AjaxResult result = controller.club(Collections.<String, Object>singletonMap("clubId", CLUB));

        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> topics = (List<Map<String, Object>>) data.get("topics");
        assertEquals(3, topics.get(0).get("participants"));
        assertEquals(2, topics.get(0).get("completed"),
                "member1 按分支完成、member2 按旧线性完成、member3 的未完成分支不能被全节点数误判");
    }

    private static CmsTopicNode node(Long id) {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(id);
        node.setStatus(1L);
        return node;
    }

    private static PlayerNodeProgress progress(Long memberId, Long activityId, Long topicId, Long nodeId) {
        PlayerNodeProgress progress = new PlayerNodeProgress();
        progress.setMemberId(memberId);
        progress.setActivityId(activityId);
        progress.setTopicId(topicId);
        progress.setNodeId(nodeId);
        progress.setStatus(1); // 正式完成事实(status=0 是 mode2 到店凭证,不算完成)
        return progress;
    }

}
