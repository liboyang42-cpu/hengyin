package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.PlayerActivityRoleMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.IPlayEncounterService;
import com.chengyinhub.business.service.support.RouteReachabilityProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SC-THOUGHT-CLAIM 验收:读到念头块后认领念头。
 * <ul>
 *   <li>同一念头重复认领:不重置已累计的进度,只算一次;</li>
 *   <li>想通(done)之后再认领:不会被重新加回进度变量、幸运上限不再 +1;</li>
 *   <li>另一局(别的玩家 / 同玩家别的活动场次)看不到这一局认领的念头;</li>
 *   <li>不存在的 key:报「念头不存在」,本局状态不写(无 updateStateCas)。</li>
 * </ul>
 * 会话表按 (activityId, memberId) 分行的内存实现,syncThoughts 走真实 TopicRouteRuntimeServiceImpl。
 */
class ThoughtClaimOnceAcceptanceTest {
    private final TopicRouteSessionMapper routeMapper = mock(TopicRouteSessionMapper.class);
    private final CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
    private final CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
    private final CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
    private final CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
    private final CmsTopicChapterMapper chapterMapper = mock(CmsTopicChapterMapper.class);
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, TopicRouteSession> sessions = new HashMap<>();
    private TopicRouteRuntimeServiceImpl runtime;

    private static String key(Object activity, Object member) {
        return (activity == null ? 0L : ((Number) activity).longValue()) + ":" + member;
    }

    @BeforeEach
    void setUp() {
        runtime = new TopicRouteRuntimeServiceImpl(routeMapper, topicMapper, nodeMapper,
                mock(CmsMemberTemplateMapper.class), mock(PlayerNodeProgressMapper.class), activityMapper,
                registrationMapper, mock(PlayerActivityRoleMapper.class), () -> 12345L,
                Clock.systemUTC(), RouteReachabilityProvider.UNKNOWN);
        IPlayEncounterService encounters = mock(IPlayEncounterService.class);
        ReflectionTestUtils.setField(runtime, "playEncounterService", encounters);
        ReflectionTestUtils.setField(runtime, "cmsTopicChapterMapper", chapterMapper);

        CmsTopic topic = new CmsTopic();
        topic.setId(10L); topic.setRouteMode("BRANCH_GRAPH"); topic.setConfigVersion(3L);
        topic.setRouteGraphJson("{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2],"
                + "\"edges\":[{\"id\":\"go\",\"fromNodeId\":1,\"toNodeId\":2,"
                + "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}]}");
        topic.setPublishedRouteGraphJson(topic.getRouteGraphJson());
        topic.setPublishedConfigVersion(3L);
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true,\"luck\":{\"enabled\":true,\"max\":3,\"init\":1},"
                + "\"thoughts\":[{\"key\":\"afternoon\",\"name\":\"那个下午\",\"need\":120}]}");
        when(topicMapper.selectCmsTopicById(10L)).thenReturn(topic);
        CmsTopicNode one = node(1L, 1L), two = node(2L, 2L);
        one.setChapterId(1L); two.setChapterId(2L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Arrays.asList(one, two));
        when(nodeMapper.selectCmsTopicNodeById(1L)).thenReturn(one);
        when(nodeMapper.selectCmsTopicNodeById(2L)).thenReturn(two);
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(1L); chapter.setTopicId(10L); chapter.setSortId(1L); chapter.setRequired(1);
        chapter.setBlocksJson("[{\"type\":\"thought\",\"thoughtKey\":\"afternoon\"},{\"type\":\"node\",\"nodeId\":1}]");
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.singletonList(chapter));
        CmsRegistration pass = new CmsRegistration(); pass.setExpiresAt(null);
        when(registrationMapper.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(Collections.singletonList(pass));
        CmsActivity activity = new CmsActivity();
        activity.setId(44L); activity.setTopicId(10L); activity.setStatus(1L);
        activity.setPublishStatus(1L); activity.setDelFlag(0);
        when(activityMapper.selectCmsActivityById(44L)).thenReturn(activity);

        when(routeMapper.selectByOwner(anyLong(), eq(10L), anyLong(), eq("default")))
                .thenAnswer(inv -> sessions.get(key(inv.getArgument(0), inv.getArgument(2))));
        when(routeMapper.insertSession(any(TopicRouteSession.class))).thenAnswer(inv -> {
            TopicRouteSession s = inv.getArgument(0);
            s.setId(99L + sessions.size());
            sessions.put(key(s.getActivityId(), s.getMemberId()), s);
            return 1;
        });
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);
        when(routeMapper.selectActionByAction(anyLong(), anyLong(), anyString())).thenReturn(null);
        when(routeMapper.insertAction(any(com.chengyinhub.business.domain.TopicRouteAction.class))).thenAnswer(inv -> {
            ((com.chengyinhub.business.domain.TopicRouteAction) inv.getArgument(0)).setId(501L); return 1;
        });
        when(routeMapper.updateActionResponse(anyLong(), anyString())).thenReturn(1);
    }

    private static CmsTopicNode node(Long id, Long sort) {
        CmsTopicNode n = new CmsTopicNode(); n.setId(id); n.setTopicId(10L); n.setSortId(sort); n.setStatus(1L);
        return n;
    }

    private JsonNode stateOf(long activity, long member) throws Exception {
        return json.readTree(sessions.get(key(activity, member)).getStateJson());
    }

    @Test
    void claimingTheSameThoughtTwiceKeepsProgressAndCountsOnce() throws Exception {
        runtime.state(7L, 10L, 0L);
        runtime.syncThoughts(7L, 10L, 0L, Collections.singletonList("afternoon"), 3000);
        runtime.syncThoughts(7L, 10L, 0L, Collections.<String>emptyList(), 3060);
        assertEquals(60, stateOf(0, 7).path("variables").path("thought.afternoon").asInt());

        // 再认领一次(同读数,不带新步数):进度必须仍是 60,不得被重置为 0
        runtime.syncThoughts(7L, 10L, 0L, Arrays.asList("afternoon", "afternoon"), 3060);
        assertEquals(60, stateOf(0, 7).path("variables").path("thought.afternoon").asInt(),
                "重复认领不能把已走的步数清零");
        runtime.syncThoughts(7L, 10L, 0L, Collections.singletonList("afternoon"), -1);
        assertEquals(60, stateOf(0, 7).path("variables").path("thought.afternoon").asInt());

        // 视图里只有一个念头
        JsonNode thoughts = json.valueToTree(
                runtime.syncThoughts(7L, 10L, 0L, Collections.singletonList("afternoon"), -1)).path("thoughts");
        assertEquals(1, thoughts.size());
        assertEquals(60, thoughts.get(0).path("progress").asInt());
    }

    @Test
    void claimingAfterThoughtIsDoneDoesNotReAddItOrRaiseLuckAgain() throws Exception {
        runtime.state(7L, 10L, 0L);
        runtime.syncThoughts(7L, 10L, 0L, Collections.singletonList("afternoon"), 3000);
        runtime.syncThoughts(7L, 10L, 0L, Collections.<String>emptyList(), 3200);
        JsonNode done = stateOf(0, 7);
        assertTrue(done.path("tags").toString().contains("thought.afternoon.done"));
        assertEquals(120, done.path("variables").path("thought.afternoon").asInt());
        assertEquals(4, done.path("variables").path("sys.luck.max").asInt());

        // 想通后,把进度变量去掉(只留 done 标签)模拟最坏形态,再认领:不得重新加回 0 进度
        ObjectNode root = (ObjectNode) done;
        ((ObjectNode) root.get("variables")).remove("thought.afternoon");
        sessions.get(key(0L, 7L)).setStateJson(json.writeValueAsString(root));
        runtime.syncThoughts(7L, 10L, 0L, Collections.singletonList("afternoon"), 3300);
        JsonNode after = stateOf(0, 7);
        assertTrue(after.path("variables").path("thought.afternoon").isMissingNode(),
                "已想通的念头再认领不能重新开始累计");
        assertEquals(4, after.path("variables").path("sys.luck.max").asInt(), "幸运上限只 +1 一次");

        // 正常形态(进度 120 + done)再认领,同样保持不变
        runtime.syncThoughts(7L, 10L, 0L, Collections.singletonList("afternoon"), 3400);
        assertEquals(4, stateOf(0, 7).path("variables").path("sys.luck.max").asInt());
    }

    @Test
    void anotherSessionDoesNotSeeTheClaimedThought() throws Exception {
        runtime.state(7L, 10L, 0L);
        runtime.syncThoughts(7L, 10L, 0L, Collections.singletonList("afternoon"), 3000);
        runtime.syncThoughts(7L, 10L, 0L, Collections.<String>emptyList(), 3050);

        // 另一个玩家同主题自玩局
        runtime.state(8L, 10L, 0L);
        JsonNode otherPlayer = json.valueToTree(
                runtime.syncThoughts(8L, 10L, 0L, Collections.<String>emptyList(), -1));
        assertEquals(0, otherPlayer.path("thoughts").size(), "别人的局不会出现我认领的念头");
        assertTrue(stateOf(0, 8).path("variables").path("thought.afternoon").isMissingNode());

        // 同一玩家在另一场活动(另一局)
        runtime.state(7L, 10L, 44L);
        org.junit.jupiter.api.Assertions.assertNotNull(sessions.get(key(44L, 7L)), "另一场活动必须开出独立的一局");
        assertNotEquals(sessions.get(key(0L, 7L)).getId(), sessions.get(key(44L, 7L)).getId());
        JsonNode otherRun = json.valueToTree(
                runtime.syncThoughts(7L, 10L, 44L, Collections.<String>emptyList(), -1));
        assertEquals(0, otherRun.path("thoughts").size(), "另一局不继承本局念头");
        assertTrue(stateOf(44, 7).path("variables").path("thought.afternoon").isMissingNode());

        // 原局不受影响
        assertEquals(50, stateOf(0, 7).path("variables").path("thought.afternoon").asInt());
    }

    @Test
    void unknownThoughtKeyIsRejectedWithoutWritingState() throws Exception {
        runtime.state(7L, 10L, 0L);
        String before = sessions.get(key(0L, 7L)).getStateJson();
        clearInvocations(routeMapper);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> runtime.syncThoughts(7L, 10L, 0L, Collections.singletonList("no_such_thought"), 3000));
        assertEquals("念头不存在", e.getMessage());
        verify(routeMapper, never()).updateStateCas(any(TopicRouteSession.class));
        assertEquals(before, sessions.get(key(0L, 7L)).getStateJson());

        // 合法 + 非法混在一起:整批拒,不落半截
        assertEquals("念头不存在", assertThrows(IllegalArgumentException.class,
                () -> runtime.syncThoughts(7L, 10L, 0L, Arrays.asList("afternoon", "no_such_thought"), 3000)).getMessage());
        verify(routeMapper, never()).updateStateCas(any(TopicRouteSession.class));
        assertEquals(before, sessions.get(key(0L, 7L)).getStateJson());

        // 对照:合法 key 正常写一次
        runtime.syncThoughts(7L, 10L, 0L, Collections.singletonList("afternoon"), 3000);
        verify(routeMapper, times(1)).updateStateCas(any(TopicRouteSession.class));
    }
}
