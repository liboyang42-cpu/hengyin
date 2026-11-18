package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FE-STORY 7.4 / T-B8:自由探索票过期后,过期前到访过的章节仍可读(不看通行证时效),
 * 未到访的章节一个字都不给;城市定向不走这条路径。
 */
class FreeExploreExpiredHistoryTest {

    private static final long MEMBER = 11L;
    private static final long ACTIVITY = 12L;
    private static final long TOPIC = 13L;

    private PlayHistoryQueryApplication app;
    private PlayerNodeProgressMapper progress;
    private PlayCompletionFacts facts;
    private CmsTopic topic;

    private static CmsTopicNode place(long id, long chapter, String text) {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(id);
        n.setTopicId(TOPIC);
        n.setChapterId(chapter);
        n.setName("示例站点" + id);
        n.setDescription(text);
        n.setFragmentText(text + "碎片");
        return n;
    }

    private static CmsTopicChapter chapter(long id) {
        CmsTopicChapter c = new CmsTopicChapter();
        c.setId(id);
        c.setTopicId(TOPIC);
        c.setName("示例章" + id);
        c.setDescription("示例章" + id + "正文");
        c.setSortId(id);
        return c;
    }

    @BeforeEach
    void setUp() {
        app = new PlayHistoryQueryApplication();
        ICmsTopicService topics = mock(ICmsTopicService.class);
        ICmsActivityService activities = mock(ICmsActivityService.class);
        ICmsTopicNodeService nodes = mock(ICmsTopicNodeService.class);
        ICmsTopicChapterService chapters = mock(ICmsTopicChapterService.class);
        progress = mock(PlayerNodeProgressMapper.class);
        facts = mock(PlayCompletionFacts.class);
        topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setProductType(2);
        when(topics.selectCmsTopicById(TOPIC)).thenReturn(topic);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        when(activities.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(Arrays.asList(
                place(1L, 101L, "已到访的正文"), place(2L, 102L, "队友到访的正文"), place(3L, 103L, "没到访的正文")));
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(Arrays.asList(chapter(101L), chapter(102L), chapter(103L)));
        PlayerNodeProgress own = new PlayerNodeProgress();
        own.setNodeId(1L);
        own.setCheckinTime(new Date());
        when(progress.selectOwnVisits(MEMBER, ACTIVITY, TOPIC)).thenReturn(Collections.singletonList(own));
        when(progress.selectTeamVisitedNodeIds(MEMBER, ACTIVITY, TOPIC)).thenReturn(Collections.singletonList(2L));
        ReflectionTestUtils.setField(app, "cmsTopicService", topics);
        ReflectionTestUtils.setField(app, "cmsActivityService", activities);
        ReflectionTestUtils.setField(app, "cmsTopicNodeService", nodes);
        ReflectionTestUtils.setField(app, "visitChapterService", chapters);
        ReflectionTestUtils.setField(app, "visitProgressMapper", progress);
        ReflectionTestUtils.setField(app, "playCompletionFacts", facts);
    }

    @SuppressWarnings("unchecked")
    @Test
    void visitedChaptersStayReadableAfterExpiryAndUnvisitedStayLocked() {
        AjaxResult result = app.freeExploreVisitedHistory(MEMBER, ACTIVITY, TOPIC);
        assertEquals(200, result.get("code"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(Boolean.TRUE, data.get("readOnly"));
        assertEquals(2, data.get("storyUnlocked"));
        assertEquals(3, data.get("storyTotal"));
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) data.get("nodes");
        assertEquals(2, nodes.size());
        String body = nodes.toString() + data.get("chapters");
        assertTrue(body.contains("已到访的正文") && body.contains("队友到访的正文"));
        assertTrue(!body.contains("没到访的正文") && !body.contains("示例章103正文"), "未到访章节不下发");
        Map<String, Object> teamNode = nodes.stream().filter(n -> "2".equals(n.get("nodeId"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, teamNode.get("visitedByTeam"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void strangerGetsNothing() {
        when(progress.selectOwnVisits(99L, ACTIVITY, TOPIC)).thenReturn(Collections.emptyList());
        when(facts.isRegistered(99L, ACTIVITY)).thenReturn(false);
        Map<String, Object> data = (Map<String, Object>) app.freeExploreVisitedHistory(99L, ACTIVITY, TOPIC).get("data");
        assertEquals(0, ((List<Object>) data.get("nodes")).size());
        assertEquals(0, ((List<Object>) data.get("chapters")).size());
    }

    @Test
    void cityRoutesStillNeedTheOriginalRunSession() {
        topic.setProductType(1);
        assertEquals("必须提供原游玩会话", app.freeExploreVisitedHistory(MEMBER, ACTIVITY, TOPIC).get("msg"));
    }

    @Test
    void activityMustBelongToTopic() {
        assertEquals("活动或主题不存在", app.freeExploreVisitedHistory(MEMBER, 404L, TOPIC).get("msg"));
    }
}
