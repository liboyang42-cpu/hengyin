package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.support.TopicRouteConfigValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 第十一轮 h5 第 1 项:站点自己的结果反馈(只有回复文字)不再把整个主题编成通用玩法局 ——
 * 主题没配状态时通用局没有 sys.hp,玩家一开就停在「数值状态必须为已初始化的整数：sys.hp」。
 * 配置取自编辑器自己的 defaultConfig / applyToConfig / serialize(选项问答 + 作者写的成功/失败反馈、D20 三档)。
 */
class TopicRoutePublicationOutcomeScopeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String QA_WITH_FEEDBACK = "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"title\":\"老洋房门口那棵树是什么树？\",\"lead\":\"\","
            + "\"answerText\":\"\",\"reveal\":false,\"maxTries\":0,\"xp\":0,\"multi\":false,\"options\":[{\"id\":\"opt_1\",\"label\":\"梧桐\",\"fb\":\"对，法国梧桐。\",\"correct\":true},"
            + "{\"id\":\"opt_2\",\"label\":\"银杏\",\"fb\":\"再看看叶子。\",\"correct\":false}]},"
            + "\"outcomeRules\":{\"version\":1,\"pass\":{\"reply\":\"对了，钟楼就在江边。\"},\"fail\":{\"reply\":\"再看看，换个方向想想。\"}}}";
    static final String D20_CHECK = "{\"schemaVersion\":1,\"check\":{\"enabled\":true,\"checkId\":\"c1\",\"tier\":\"medium\",\"skill\":\"观察\",\"successText\":\"你看见了暗门。\","
            + "\"failText\":\"什么也没发现。\",\"successEffects\":[],\"failEffects\":[],\"mods\":[]},\"outcomeRules\":{\"version\":1,\"pass\":{},\"fail\":{}}}";
    static final String STATE_RULES = "{\"schemaVersion\":1,\"checkEnabled\":true,\"stateEnabled\":true,\"hp\":{\"enabled\":true,\"max\":10,\"init\":10}}";

    @Test
    void stationFeedbackAloneNoLongerCompilesTheWholeThemeAsAGenericRun() throws Exception {
        Fixture f = new Fixture(QA_WITH_FEEDBACK);
        assertEquals(0, quests(f.compiler.compileTemplateRules(f.topic)));
    }

    @Test
    void themeWithStateRulesStillCompilesItsStationFeedbackIntoTheGenericRun() throws Exception {
        Fixture f = new Fixture(QA_WITH_FEEDBACK);
        f.topic.setJourneyRulesJson(STATE_RULES);
        assertEquals(1, quests(f.compiler.compileTemplateRules(f.topic)));
    }

    @Test
    void d20StationStillNeedsTheGenericRunAndPublishRefusesWhenD20CheckIsOff() throws Exception {
        Fixture f = new Fixture(D20_CHECK);
        assertEquals(1, quests(f.compiler.compileTemplateRules(f.topic)));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> f.compiler.freezePublication(f.topic, null));
        assertEquals("节点 11（01 D20）：D20 检定要先在主题设置里打开「d20 检定」", refused.getMessage());
        f.topic.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true}");
        assertDoesNotThrow(() -> f.compiler.freezePublication(f.topic, null));
    }

    @Test
    void feedbackThatMovesValuesOrBranchesNeedsTheGenericRun() throws Exception {
        assertFalse(TopicRoutePublicationCompiler.outcomeNeedsGenericRun(JSON.readTree(QA_WITH_FEEDBACK)));
        assertTrue(TopicRoutePublicationCompiler.outcomeNeedsGenericRun(JSON.readTree(D20_CHECK)));
        JsonNode effects = JSON.readTree(QA_WITH_FEEDBACK.replace("\"pass\":{\"reply\":\"对了，钟楼就在江边。\"}",
                "\"pass\":{\"reply\":\"对了\",\"effects\":[{\"var\":\"counter.keys\",\"op\":\"INC\",\"value\":1}]}"));
        assertTrue(TopicRoutePublicationCompiler.outcomeNeedsGenericRun(effects));
        JsonNode next = JSON.readTree(QA_WITH_FEEDBACK.replace("\"pass\":{\"reply\":\"对了，钟楼就在江边。\"}", "\"pass\":{\"nextStepId\":\"s2\"}"));
        assertTrue(TopicRoutePublicationCompiler.outcomeNeedsGenericRun(next));
        assertFalse(TopicRoutePublicationCompiler.outcomeNeedsGenericRun(JSON.readTree("{\"qa\":{\"enabled\":true}}")));
    }

    @Test
    void ordinaryPhotoWithEggAndRestoredAllChapterCanSaveAndFreeze() throws Exception {
        Fixture f = new Fixture("{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"SHOT\",\"title\":\"拍下路灯\",\"maxTries\":0}}");
        CmsTopicChapter chapter = new CmsTopicChapter(); chapter.setId(21L); chapter.setTopicId(10L); chapter.setSortId(0L); chapter.setName("起点");
        when(f.chapters.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(chapter));
        f.nodes.selectCmsTopicNodeList(new CmsTopicNode()).get(0).setChapterId(21L);
        f.templates.selectCmsMemberTemplateByIds(Collections.singletonList(701L)).get(0).setValidationMethod(2);
        for (String eggs : new String[]{"[{\"id\":\"egg1\",\"name\":\"来信\",\"content\":\"灯亮了\",\"trigger\":\"TIME\",\"threshold\":1,\"rewards\":[]}]", "[]"}) {
            f.topic.setThemeRuntimeConfigJson("{\"schemaVersion\":1,\"capacity\":0,\"chapters\":[{\"index\":0,\"hpCost\":0,\"luckCost\":0,\"grants\":[],\"eggs\":" + eggs + ",\"completion\":{\"type\":\"ALL\"}}]}");
            String saved = f.compiler.compileTemplateRules(f.topic);
            assertEquals(1, quests(saved)); f.topic.setThemeRuntimeConfigJson(saved);
            assertDoesNotThrow(() -> f.compiler.freezePublication(f.topic, null));
        }
    }

    private static int quests(String compiled) throws Exception {
        return compiled == null ? 0 : JSON.readTree(compiled).path("gameplayRuntime").path("quests").size();
    }

    private static final class Fixture {
        final CmsTopicMapper topics = mock(CmsTopicMapper.class);
        final CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        final CmsTopicChapterMapper chapters = mock(CmsTopicChapterMapper.class);
        final CmsMemberTemplateMapper templates = mock(CmsMemberTemplateMapper.class);
        final ThemeStateKeyMapper keys = mock(ThemeStateKeyMapper.class);
        final CmsTopic topic = new CmsTopic();
        final TopicRoutePublicationCompiler compiler;
        Fixture(String advancedConfig) {
            topic.setId(10L); topic.setRouteMode("LINEAR");
            CmsTopicNode node = new CmsTopicNode();
            node.setId(11L); node.setTopicId(10L); node.setTemplateId(701L); node.setStatus(1L); node.setName("01 D20");
            node.setLatitude("31.238000"); node.setLongitude("121.490000");
            CmsMemberTemplate template = new CmsMemberTemplate();
            template.setId(701L); template.setValidationMethod(5); template.setAdvancedConfigJson(advancedConfig);
            when(topics.selectCmsTopicByIdForUpdate(10L)).thenReturn(topic);
            when(chapters.selectCmsTopicChapterList(any())).thenReturn(Collections.emptyList());
            when(nodes.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
            when(templates.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.singletonList(template));
            when(keys.selectByTopicId(10L)).thenReturn(Collections.emptyList());
            compiler = new TopicRoutePublicationCompiler(nodes, templates, chapters, keys, Clock.systemUTC(),
                    new ObjectMapper(), new TopicRouteConfigValidator(), topics);
        }
    }
}
