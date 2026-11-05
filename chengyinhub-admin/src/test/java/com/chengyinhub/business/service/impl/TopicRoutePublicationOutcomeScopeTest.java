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
        f.nodes.selectCmsTopicNodeList(new CmsTopicNode()).get(0).setName("路灯照片");
        f.templates.selectCmsMemberTemplateByIds(Collections.singletonList(701L)).get(0).setValidationMethod(2);
        for (String completion : new String[]{"", ",\"completion\":{\"type\":\"ALL\"}"}) {
            for (String eggs : new String[]{"[{\"id\":\"egg1\",\"name\":\"来信\",\"content\":\"灯亮了\",\"trigger\":\"TIME\",\"threshold\":1,\"rewards\":[]}]", "[]"}) {
                f.topic.setThemeRuntimeConfigJson("{\"schemaVersion\":1,\"capacity\":0,\"gameplayRuntime\":{\"runtimeScope\":\"PLAYER\",\"survival\":{\"zeroHpPolicy\":\"BLOCK_HP_COST\",\"actionLimitPolicy\":\"BLOCK_OVERFLOW\"}},\"chapters\":[{\"index\":0,\"hpCost\":0,\"luckCost\":0,\"grants\":[],\"eggs\":" + eggs + completion + "}]}");
                String saved = f.compiler.compileTemplateRules(f.topic);
                assertEquals(1, quests(saved)); f.topic.setThemeRuntimeConfigJson(saved);
                assertEquals(1, JSON.readTree(saved).path("gameplayRuntime").path("acts").size());
                assertDoesNotThrow(() -> f.compiler.freezePublication(f.topic, null));
            }
        }
    }

    /* 第十三轮 m1:章节有互斥组又有途中彩蛋的个人主题,每种带结果规则、定位到达的玩法都能保存并冻结(作者重开玩法存一次后的形状) */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"coinFlip","diceRoll","random","estimate","pricePair","hiddenObject","profile","note","typeIn","album","photoCheck"})
    void resavedGameWithBranchAndEggSavesAndFreezes(String kit) throws Exception {
        Fixture f = new Fixture(TopicRoutePublicationOutcomeScopeTest.settledConfig(kit));
        CmsTopicChapter chapter = new CmsTopicChapter(); chapter.setId(21L); chapter.setTopicId(10L); chapter.setSortId(0L); chapter.setName("分岔");
        when(f.chapters.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(chapter));
        f.nodes.selectCmsTopicNodeList(new CmsTopicNode()).get(0).setChapterId(21L);
        f.topic.setThemeRuntimeConfigJson("{\"schemaVersion\":1,\"capacity\":0,\"chapters\":[{\"index\":0,\"hpCost\":0,\"luckCost\":0,\"grants\":[],\"completion\":{\"type\":\"ALL\"},\"eggs\":[{\"id\":\"egg1\",\"name\":\"涂鸦\",\"content\":\"墙上的猫\",\"trigger\":\"TIME\",\"threshold\":1,\"rewards\":[]}]}]}");
        String saved = f.compiler.compileTemplateRules(f.topic);
        assertEquals(1, quests(saved)); f.topic.setThemeRuntimeConfigJson(saved);
        assertDoesNotThrow(() -> f.compiler.freezePublication(f.topic, null));
    }

    /* 第十三轮 m7:业主的游戏要扫码站和互斥组、彩蛋同在一个主题里。主玩法(文字问答 / 拍照问答)带结果规则,参与方式选「扫码」:
       保存编出任务、发布冻结都放行;站点没有自己的码就拒;参与方式不是扫码就拒,并说人话。 */
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"TYPE", "SHOT"})
    void qrStationWithBranchAndEggSavesAndFreezes(String mode) throws Exception {
        String qa = "TYPE".equals(mode) ? "\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"title\":\"门牌上的年份\",\"answerText\":\"1931\",\"maxTries\":0}"
                : "\"qa\":{\"enabled\":true,\"mode\":\"SHOT\",\"title\":\"拍下码头\",\"maxTries\":0}";
        Fixture f = new Fixture("{\"schemaVersion\":1," + qa + ",\"scan\":{\"enabled\":true,\"kind\":\"TEXT\",\"reply\":\"欢迎\"},\"outcomeRules\":{\"version\":1,\"pass\":{},\"fail\":{}}}");
        CmsTopicChapter chapter = new CmsTopicChapter(); chapter.setId(21L); chapter.setTopicId(10L); chapter.setSortId(0L); chapter.setName("码头");
        when(f.chapters.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(chapter));
        CmsTopicNode node = f.nodes.selectCmsTopicNodeList(new CmsTopicNode()).get(0); node.setChapterId(21L); node.setName("旧码头"); node.setCheckinCode("rig-code-11");
        CmsMemberTemplate template = f.templates.selectCmsMemberTemplateByIds(Collections.singletonList(701L)).get(0); template.setValidationMethod(4);
        String theme = "{\"schemaVersion\":1,\"capacity\":0,\"chapters\":[{\"index\":0,\"hpCost\":0,\"luckCost\":0,\"grants\":[],\"completion\":{\"type\":\"ALL\"},\"eggs\":[{\"id\":\"egg1\",\"name\":\"雨中便条\",\"content\":\"船票\",\"trigger\":\"TIME\",\"threshold\":1,\"rewards\":[]}]}]}";
        f.topic.setThemeRuntimeConfigJson(theme);
        String saved = f.compiler.compileTemplateRules(f.topic);
        assertEquals(1, quests(saved)); f.topic.setThemeRuntimeConfigJson(saved);
        assertDoesNotThrow(() -> f.compiler.freezePublication(f.topic, null));
        node.setCheckinCode(" ");
        f.topic.setThemeRuntimeConfigJson(theme);
        String noCode = assertThrows(IllegalArgumentException.class, () -> { f.topic.setThemeRuntimeConfigJson(f.compiler.compileTemplateRules(f.topic)); f.compiler.freezePublication(f.topic, null); }).getMessage();
        assertTrue(noCode.contains("扫码"), noCode);
        node.setCheckinCode("rig-code-11"); template.setValidationMethod(0);
        f.topic.setThemeRuntimeConfigJson(theme);
        String direct = assertThrows(IllegalArgumentException.class, () -> f.compiler.compileTemplateRules(f.topic)).getMessage();
        assertEquals("「旧码头」的扫码：扫码玩法的参与方式要选「扫码」", direct);
    }

    static String settledConfig(String kit) {
        String game;
        switch (kit) {
            case "coinFlip": game = "\"coinFlip\":{\"enabled\":true,\"title\":\"抛\",\"heads\":{\"label\":\"正面\",\"action\":\"往左\"},\"tails\":{\"label\":\"反面\",\"action\":\"往右\"}}"; break;
            case "diceRoll": game = "\"diceRoll\":{\"enabled\":true,\"mode\":\"d6\",\"title\":\"掷\",\"count\":1,\"faces\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\"]}"; break;
            case "random": game = "\"random\":{\"enabled\":true,\"drawCount\":1,\"items\":[{\"id\":\"a\",\"label\":\"甲\",\"weight\":1},{\"id\":\"b\",\"label\":\"乙\",\"weight\":1}]}"; break;
            case "estimate": game = "\"estimate\":{\"enabled\":true,\"title\":\"猜\",\"min\":0,\"max\":100,\"answer\":50,\"tolerance\":5}"; break;
            case "pricePair": game = "\"pricePair\":{\"enabled\":true,\"title\":\"挑\",\"items\":[{\"id\":\"a\",\"name\":\"甲\",\"correct\":true},{\"id\":\"b\",\"name\":\"乙\",\"correct\":false},{\"id\":\"c\",\"name\":\"丙\",\"correct\":false}]}"; break;
            case "hiddenObject": game = "\"hiddenObject\":{\"enabled\":true,\"title\":\"找\",\"imageUrl\":\"https://static.chengyinhub.com/a.png\",\"hotspots\":[{\"id\":\"h1\",\"label\":\"一\",\"x\":0.2,\"y\":0.2,\"r\":0.1},{\"id\":\"h2\",\"label\":\"二\",\"x\":0.8,\"y\":0.8,\"r\":0.1},{\"id\":\"h3\",\"label\":\"三\",\"x\":0.5,\"y\":0.5,\"r\":0.1}]}"; break;
            case "profile": game = "\"profile\":{\"enabled\":true,\"questions\":[{\"key\":\"name\",\"label\":\"名字\",\"kind\":\"text\"}]}"; break;
            case "note": game = "\"note\":{\"enabled\":true,\"title\":\"留一句\",\"prompt\":\"写给下一个人\",\"maxLength\":40}"; break;
            case "typeIn": game = "\"typeIn\":{\"enabled\":true,\"title\":\"打字\",\"target\":\"hello\",\"seconds\":10}"; break;
            case "check": game = "\"check\":{\"enabled\":true,\"checkId\":\"c1\",\"tiers\":[{\"dc\":10}]}"; break;
            case "album": game = "\"album\":{\"enabled\":true,\"images\":[{\"url\":\"https://static.chengyinhub.com/a.png\"}]}"; break;
            default: game = "\"photoCheck\":{\"enabled\":true,\"title\":\"拍门牌\",\"requirement\":\"拍到门牌号\",\"minConfidence\":60,\"maxTries\":3,\"fallback\":\"retake\"}";
        }
        boolean once = java.util.Arrays.asList("profile", "album", "note").contains(kit);
        return "{\"schemaVersion\":1," + game + ",\"outcomeRules\":{\"version\":1,\"pass\":{}" + (once ? "" : ",\"fail\":{}") + "}}";
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
