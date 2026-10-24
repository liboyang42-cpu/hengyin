package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsPointsSettingService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IPuzzlePlayService;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 故事变量 {名字} 必须挂到 /api/play/nodes 的进度回包上(契约 §3.1)。
 *
 * <p>会话视图(/api/play/advanced)那份不够:B 的替换点在章节全文与节点 story,它们来自这条
 * 回包 —— 只挂会话视图,故事变量整条链是哑的。真源是 StoryVars 一个构造函数,两处共用。
 */
class PlayProgressVarsTest {

    private static final Long PLAYER = 41L;
    private static final Long ACTIVITY = 43L;
    private static final Long TOPIC = 44L;
    private static final Long NODE = 46L;

    private ApiPlayProgressController controller;
    private GameRuntimeSessionMapper sessionMapper;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiPlayProgressController());
        doReturn(PLAYER).when(controller).getAppUserId();

        ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        IPlayerNodeProgressService progressService = mock(IPlayerNodeProgressService.class);
        ICmsPointsSettingService pointsSettingService = mock(ICmsPointsSettingService.class);
        ICmsTopicChapterService chapterService = mock(ICmsTopicChapterService.class);
        IPuzzlePlayService puzzlePlayService = mock(IPuzzlePlayService.class);
        sessionMapper = mock(GameRuntimeSessionMapper.class);

        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activityService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrationService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topicService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progressService);
        var fixtureHintSettings = pointsSettingService;
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicChapterService", chapterService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "puzzlePlayService", puzzlePlayService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "gameRuntimeSessionMapper", sessionMapper);

        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(TOPIC);
        node.setSortId(1L);
        node.setStatus(1L);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        activity.setStartDate(daysFromNow(-1));
        activity.setEndDate(daysFromNow(30));
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);

        CmsRegistration registration = new CmsRegistration();
        registration.setMemberId(PLAYER);
        registration.setRegistrationStatus(2);
        when(registrationService.selectCmsRegistrationList(any()))
                .thenReturn(Collections.singletonList(registration));

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setProductType(ProductType.ORIENTEERING);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);

        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.<PlayerNodeProgress>emptyList());
        when(pointsSettingService.selectCmsPointsSettingList(any())).thenReturn(Collections.emptyList());
        when(chapterService.selectCmsTopicChapterList(any())).thenReturn(Collections.emptyList());
        when(puzzlePlayService.selectHintLevels(any(), any(), any())).thenReturn(Collections.emptyMap());
        com.chengyinhub.web.controller.api.NodeHintUnlockTestFixtures.pricing(controller, fixtureHintSettings);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 进度回包带上建档答案与作品名() {
        when(sessionMapper.selectStateJsonsByTopic(PLAYER, TOPIC, ACTIVITY)).thenReturn(Arrays.asList(
                "{\"profile\":{\"done\":true,\"answers\":{\"name\":\"阿猫\",\"job\":\"clerk\"}}}",
                "{\"diyName\":{\"name\":\"窗边的猫\"}}"));

        AjaxResult result = controller.nodes(ACTIVITY, TOPIC);

        assertEquals(200, result.get("code"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        Map<?, ?> vars = (Map<?, ?>) data.get("vars");
        assertEquals("阿猫", vars.get("name"), "建档答案必须在进度回包里可替换:" + data);
        assertEquals("clerk", vars.get("job"));
        assertEquals("窗边的猫", vars.get("diyName"), "已完成 kit 的结果也要在:" + data);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 负控_一个玩法都没做过就不下发空vars() {
        when(sessionMapper.selectStateJsonsByTopic(PLAYER, TOPIC, ACTIVITY))
                .thenReturn(Collections.<String>emptyList());

        AjaxResult result = controller.nodes(ACTIVITY, TOPIC);

        assertEquals(200, result.get("code"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertFalse(data.containsKey("vars"), "空 map 不下发 —— 前端才看得见「没值」而不是「值是空」");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 负控_多局同键先到先得() {
        // 建档是一次性的:后一局又出现同名键,不该覆盖前面已经成立的身份
        when(sessionMapper.selectStateJsonsByTopic(PLAYER, TOPIC, ACTIVITY)).thenReturn(Arrays.asList(
                "{\"profile\":{\"answers\":{\"name\":\"先填的\"}}}",
                "{\"profile\":{\"answers\":{\"name\":\"后填的\"}}}"));

        AjaxResult result = controller.nodes(ACTIVITY, TOPIC);

        Map<String, Object> data = (Map<String, Object>) result.get("data");
        Map<?, ?> vars = (Map<?, ?>) data.get("vars");
        assertEquals("先填的", vars.get("name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 变量读取失败不拖垮节点列表() {
        when(sessionMapper.selectStateJsonsByTopic(PLAYER, TOPIC, ACTIVITY))
                .thenThrow(new RuntimeException("库抖动"));

        AjaxResult result = controller.nodes(ACTIVITY, TOPIC);

        assertEquals(200, result.get("code"), "变量取不到不该把 /nodes 拖成 500:" + result);
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertFalse(data.containsKey("vars"));
        assertFalse(((java.util.List<?>) data.get("nodes")).isEmpty(), "节点列表本身要照常下发");
    }

    private static Date daysFromNow(int days) {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_MONTH, days);
        return calendar.getTime();
    }
}
