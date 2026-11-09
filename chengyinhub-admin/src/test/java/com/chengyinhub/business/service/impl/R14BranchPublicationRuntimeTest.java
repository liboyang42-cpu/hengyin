package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.support.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real publication/compiler and task engine; mapper fixtures do not prove a database or device walk. */
class R14BranchPublicationRuntimeTest {
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(longs = {12, 13})
    void frozenExclusiveSidesCompleteChapterWithoutSettlingSibling(long chosen) throws Exception {
        CmsTopic topic = new CmsTopic(); topic.setId(10L); topic.setRouteMode("LINEAR");
        topic.setThemeRuntimeConfigJson("{\"schemaVersion\":1,\"capacity\":0,\"chapters\":[{\"index\":1,\"hpCost\":0,\"luckCost\":0,\"grants\":[],\"exclusiveGroups\":[[12,13]],\"completion\":{\"type\":\"ALL\"}}]}");
        List<CmsTopicChapter> chapterRows = new ArrayList<>();
        for (long id : new long[]{20, 21, 22}) {
            CmsTopicChapter chapter = new CmsTopicChapter(); chapter.setId(id); chapter.setTopicId(10L); chapter.setSortId(id); chapter.setName("章" + id); chapterRows.add(chapter);
        }
        List<CmsTopicNode> nodeRows = new ArrayList<>();
        List<CmsMemberTemplate> templateRows = new ArrayList<>();
        for (long id : new long[]{11, 12, 13}) {
            CmsTopicNode node = new CmsTopicNode(); node.setId(id); node.setTopicId(10L); node.setChapterId(21L); node.setTemplateId(700L + id);
            node.setStatus(1L); node.setName("站" + id); node.setLatitude("31.238000"); node.setLongitude("121.490000"); nodeRows.add(node);
            CmsMemberTemplate template = new CmsMemberTemplate(); template.setId(700L + id); template.setValidationMethod(5);
            template.setAdvancedConfigJson(TopicRoutePublicationOutcomeScopeTest.QA_WITH_FEEDBACK); templateRows.add(template);
        }
        CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class); CmsMemberTemplateMapper templates = mock(CmsMemberTemplateMapper.class);
        CmsTopicChapterMapper chapters = mock(CmsTopicChapterMapper.class); ThemeStateKeyMapper keys = mock(ThemeStateKeyMapper.class); CmsTopicMapper topics = mock(CmsTopicMapper.class);
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(nodeRows); when(templates.selectCmsMemberTemplateByIds(any())).thenReturn(templateRows);
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(chapterRows); when(keys.selectByTopicId(10L)).thenReturn(Collections.emptyList());
        TopicRoutePublicationCompiler compiler = new TopicRoutePublicationCompiler(nodes, templates, chapters, keys, Clock.systemUTC(), json, new TopicRouteConfigValidator(), topics);
        topic.setThemeRuntimeConfigJson(compiler.compileTemplateRules(topic));
        JsonNode frozen = json.readTree(compiler.freezePublication(topic, null));
        JsonNode theme = frozen.path("themeConfig"), quests = theme.path("gameplayRuntime").path("quests");
        assertEquals(3, quests.size());
        assertEquals(quests.get(1).path("exclusiveGroup"), quests.get(2).path("exclusiveGroup"));
        assertFalse(quests.get(1).path("exclusiveGroup").asText().isEmpty());
        GameplayTaskEngine engine = new GameplayTaskEngine(); JourneyRules rules = new JourneyRules();
        TopicRouteEngine.State state = new TopicRouteEngine.State(); state.setStatus("ACTIVE"); JourneyInitialState.initialize(rules, state);
        engine.initialize(theme, rules, state, 1000);
        settle(engine, theme, rules, state, 11);
        assertEquals("ACTIVE", state.getStatus());
        settle(engine, theme, rules, state, chosen);
        long sibling = chosen == 12 ? 13 : 12;
        assertEquals("COMPLETED", state.getStatus()); assertNull(state.getCurrentNodeId());
        assertEquals("DISCOVERED_LOCKED", state.getNodeStates().get(sibling));
        assertFalse(state.getCompletedNodeIds().contains(sibling), "未选分支不能伪装已完成或拿奖励");
        assertTrue(state.getThemeRuntime().path("chapters").path("1").path("ended").asBoolean());
        JsonNode before = json.valueToTree(state);
        engine.execute(theme, rules, state, "node_" + chosen, "QUEST_RESOLVE", null, "retry", "MEMBER:9", 2000);
        assertEquals(before, json.valueToTree(state), "原结算回放不能再发奖励或扣费");
        assertThrows(IllegalStateException.class, () -> engine.execute(theme, rules, state, "node_" + sibling, "QUEST_START", null, "sibling", "MEMBER:9", 2000));
    }

    private void settle(GameplayTaskEngine engine, JsonNode theme, JourneyRules rules, TopicRouteEngine.State state, long node) {
        engine.execute(theme, rules, state, "node_" + node, "QUEST_START", null, "start" + node, "MEMBER:9", 1100);
        state.getThemeRuntime().with("verifiedNodes").put(String.valueOf(node), 100 + node);
        engine.execute(theme, rules, state, "node_" + node, "QUEST_RESOLVE", null, "settle" + node, "MEMBER:9", 1200);
    }
}
