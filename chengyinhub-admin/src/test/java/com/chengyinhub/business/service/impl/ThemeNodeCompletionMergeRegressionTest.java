package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.route.NodeOutcome;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.support.ThemeRuntimeEngine;
import com.chengyinhub.business.service.support.ThemeRuntimeFeaturePolicy;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Exercises the public completion API and real engines with only persistence boundaries mocked. */
class ThemeNodeCompletionMergeRegressionTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void graphFinalNodeSettlesChapterBeforeSignalAndFreezesResultAfterSignal() throws Exception {
        completeFinalNode(true);
    }

    @Test
    void linearFinalNodeSettlesChapterBeforeSignalAndFreezesResultAfterSignal() throws Exception {
        completeFinalNode(false);
    }

    private void completeFinalNode(boolean graph) throws Exception {
        TopicRouteSessionMapper routes = mock(TopicRouteSessionMapper.class);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        PlayerNodeProgressMapper progress = mock(PlayerNodeProgressMapper.class);
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        TopicRouteRuntimeServiceImpl runtime = new TopicRouteRuntimeServiceImpl(routes, topics, nodes,
                progress, mock(CmsActivityMapper.class), registrations);
        ThemeRuntimeFeaturePolicy policy = new ThemeRuntimeFeaturePolicy();
        ReflectionTestUtils.setField(policy, "enabled", true);
        ReflectionTestUtils.setField(runtime, "themeRuntimePolicy", policy);

        CmsTopic topic = new CmsTopic();
        topic.setId(10L);
        topic.setRouteMode(graph ? "BRANCH_GRAPH" : "LINEAR");
        topic.setPublishedConfigVersion(1L);
        when(topics.selectCmsTopicById(10L)).thenReturn(topic);
        CmsTopicNode node = new CmsTopicNode();
        node.setId(1L); node.setTopicId(10L); node.setSortId(1L); node.setStatus(1L);
        when(nodes.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(node));
        when(nodes.selectCmsTopicNodeById(1L)).thenReturn(node);
        when(registrations.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(Collections.singletonList(new CmsRegistration()));
        PlayerNodeProgress completed = new PlayerNodeProgress();
        completed.setId(701L); completed.setMemberId(7L); completed.setTopicId(10L);
        completed.setActivityId(0L); completed.setNodeId(1L); completed.setStatus(1);
        when(progress.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.singletonList(completed));

        // A NODE grant is eligible only after the chapter END grant has been received.
        // Omitting pre-signal chapter sync fails that assertion; terminal-capable pre-sync
        // also fails because the feature engine correctly refuses post-result signals.
        JsonNode theme = json.readTree("{\"schemaVersion\":1,\"capacity\":0,"
                + "\"items\":[{\"id\":\"chapter_token\",\"kind\":\"MEDAL\"},"
                + "{\"id\":\"node_token\",\"kind\":\"MEDAL\"}],"
                + "\"grants\":[{\"id\":\"final_node\",\"source\":\"NODE\",\"nodeId\":1,"
                + "\"itemId\":\"node_token\",\"quantity\":1,"
                + "\"when\":[{\"op\":\"ITEM_HELD\",\"itemId\":\"chapter_token\",\"quantity\":1}]}],"
                + "\"chapters\":[{\"index\":0,\"binding\":{\"chapterId\":11,\"nodeIds\":[1]},"
                + "\"grants\":[{\"timing\":\"END\",\"itemId\":\"chapter_token\",\"quantity\":1}]}]}");
        ObjectNode snapshot = json.createObjectNode();
        snapshot.put("schemaVersion", 1); snapshot.put("startNodeId", 1);
        snapshot.putArray("terminalNodeIds").add(1);
        if (graph) snapshot.putArray("edges");
        snapshot.putObject("nodeJourney").putObject("1");
        snapshot.putObject("journeyRules").put("schemaVersion", 1);
        snapshot.set("themeConfig", theme);
        TopicRouteEngine.State state = new TopicRouteEngine().initial(snapshot.toString());
        ThemeRuntimeEngine themeEngine = new ThemeRuntimeEngine();
        themeEngine.initialize(theme, state);
        themeEngine.chapterStart(theme, state, JourneyRules.parse("{\"schemaVersion\":1}"),
                theme.path("chapters").get(0), 1L);

        TopicRouteSession session = new TopicRouteSession();
        session.setId(99L); session.setMemberId(7L); session.setTopicId(10L);
        session.setActivityId(0L); session.setPlayInstanceId("default");
        session.setVersion(0); session.setConfigVersion(1L); session.setSeed(12345L);
        session.setStatus(TopicRouteSession.STATUS_RUNNING);
        session.setCurrentNodeId(1L); session.setRecommendedNodeId(1L);
        session.setConfigSnapshotJson(snapshot.toString());
        session.setStateJson(json.writeValueAsString(state));
        when(routes.selectCurrentByOwner(0L, 10L, 7L)).thenReturn(session);
        when(routes.selectByOwner(0L, 10L, 7L, "default")).thenReturn(session);
        when(routes.insertAction(any(TopicRouteAction.class))).thenAnswer(invocation -> {
            TopicRouteAction action = invocation.getArgument(0); action.setId(501L); return 1;
        });
        when(routes.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);
        when(routes.updateActionResponse(anyLong(), anyString())).thenReturn(1);

        TopicRouteState view = runtime.advance(7L, 10L, 0L, 0, "finish-node", "CHOICE",
                new NodeOutcome(1L, "COMPLETED", "player-node-progress:701"));

        JsonNode saved = json.readTree(session.getStateJson());
        JsonNode result = saved.path("themeRuntime").path("result");
        assertTrue(result.isObject(), "final completion must freeze a result");
        assertEquals("COMPLETED", result.path("reason").asText());
        assertEquals(1, result.path("actions").asInt(), "the final NODE action must precede result freezing");
        assertEquals(1, result.path("inventory").path("chapter_token").asInt());
        assertEquals(1, result.path("inventory").path("node_token").asInt(),
                "chapter END effects must be visible to the final NODE grant conditions");
        assertEquals(1, result.path("acquired").path("node_token").asInt());
        assertTrue(saved.path("themeRuntime").path("sources").path("signal:node:1").asBoolean());
        assertTrue(saved.path("themeRuntime").path("chapters").path("0").path("ended").asBoolean());
        assertEquals(TopicRouteState.COMPLETED, view.getStatus());
        assertEquals(1, session.getVersion());
        verify(routes).updateStateCas(session);
        verify(routes).updateActionResponse(anyLong(), anyString());
    }
}
