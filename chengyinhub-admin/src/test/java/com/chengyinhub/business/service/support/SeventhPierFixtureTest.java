package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.domain.dto.ChapterBlockDTO;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.PlayerActivityRoleMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.impl.NpcProfileService;
import com.chengyinhub.business.service.impl.PlayJourneyStoryServiceImpl;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.business.util.StateKeyRefs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R12-SEED:「消失的第七码头」完整演示配置的整份校验(不落库、不连库)。
 *
 * <p>真源是原型 corner-replica 的 season.ts / theme.ts / engine.ts;本测试把演示配置当成
 * 一位作者的投稿:键走 {@link ThemeStateKeyService} 注册校验,数值走 {@link JourneyRulesValidator},
 * 剧情走 {@link JourneyStoryValidator}(章节 / NPC 用桩集合),九站块配置走
 * {@link AdvancedGameConfigValidator},再用 {@link StateKeyRefs} 把全部条件引用收齐,
 * 确认没有一处引用未注册的键。整份配置任一处不合法,本测试必须变红。
 */
class SeventhPierFixtureTest {

    private static final String ROOT = "/journey/seventh-pier/";
    private static final Long TOPIC_ID = 90001L;
    /** 阶段章节用占位 id(建题时才由数据库分配),这里作为桩集合喂给剧情校验器。 */
    private static final Set<Long> STUB_CHAPTERS = new LinkedHashSet<Long>(
            Arrays.asList(1001L, 1002L, 1003L));
    /** 五个 NPC 的桩 id:乔岚 101 / 阿蓉 102 / 许闻 103 / 老周 104 / 林舟 105。 */
    private static final Set<Long> STUB_NPCS = new LinkedHashSet<Long>(
            Arrays.asList(101L, 102L, 103L, 104L, 105L));
    /** 九站各自的专属谜题必须落在现有玩法里,一站恰好一种。 */
    private static final String[] GAMEPLAY_SECTIONS = {
            "sort", "match", "classify", "qa", "hiddenObject", "pricePair", "branch"};

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ThemeStateKeyService stateKeyService;
    private Set<String> registeredKeys;

    @BeforeEach
    void registerStateKeys() throws Exception {
        ThemeStateKeyMapper declarations = mock(ThemeStateKeyMapper.class);
        when(declarations.lockTopicForStateKeyWrite(TOPIC_ID)).thenReturn(TOPIC_ID);
        stateKeyService = new ThemeStateKeyService(declarations);
        List<ThemeStateKey> keys = readStateKeys();
        stateKeyService.register(TOPIC_ID, keys);
        registeredKeys = new LinkedHashSet<String>();
        for (ThemeStateKey key : keys) {
            assertTrue(registeredKeys.add(key.getStateKey()),
                    "状态键重复注册:" + key.getStateKey());
            assertNotNull(key.getLabel(), "状态键缺 label:" + key.getStateKey());
            assertFalse(key.getLabel().trim().isEmpty(), "状态键 label 不能为空:" + key.getStateKey());
        }
    }

    @Test
    void everyStateKeyIsRegisteredWithItsDeclaredType() throws Exception {
        assertEquals(28, registeredKeys.size(), "原型全部状态都必须登记:" + registeredKeys);
        assertTrue(registeredKeys.contains("clue.receipt_071"), registeredKeys.toString());
        assertTrue(registeredKeys.contains("relation.arong"), registeredKeys.toString());
        assertTrue(registeredKeys.contains("tag.injured"), registeredKeys.toString());
        assertTrue(registeredKeys.contains("counter.disposition"), registeredKeys.toString());
    }

    @Test
    void journeyRulesMatchThePrototypeEngineNumbers() throws Exception {
        String normalized = new JourneyRulesValidator()
                .validateAndNormalize(read("journey-rules.json"));
        assertNotNull(normalized);
        JsonNode rules = objectMapper.readTree(normalized);

        assertEquals(10, rules.path("hp").path("max").asInt());
        assertEquals(10, rules.path("hp").path("init").asInt());
        assertEquals(3, rules.path("luck").path("max").asInt());
        assertEquals(3, rules.path("luck").path("init").asInt());
        // engine.ts 的 dcDamage:13 以下 1、13–15 为 2、16+ 为 3 —— 三档 DC 取 10 / 13 / 16。
        assertEquals(10, rules.path("tiers").path("easy").path("dc").asInt());
        assertEquals(1, rules.path("tiers").path("easy").path("damage").asInt());
        assertEquals(13, rules.path("tiers").path("medium").path("dc").asInt());
        assertEquals(2, rules.path("tiers").path("medium").path("damage").asInt());
        assertEquals(16, rules.path("tiers").path("hard").path("dc").asInt());
        assertEquals(3, rules.path("tiers").path("hard").path("damage").asInt());
        // engine.ts 的恢复:站末受伤回 2、阶段休整生命下限 6 / 幸运下限 1、力竭休整到 5。
        assertEquals(2, rules.path("recovery").path("stationEndIfHurt").asInt());
        assertEquals(6, rules.path("recovery").path("stageRestHpFloor").asInt());
        assertEquals(1, rules.path("recovery").path("stageRestLuckFloor").asInt());
        assertEquals(5, rules.path("recovery").path("exhaustRestHp").asInt());
        assertEquals("tag.injured", rules.path("recovery").path("exhaustTag").asText());
        assertEquals(1, rules.path("fumbleExtraDamage").asInt());
        assertEquals(6, rules.path("modCap").asInt());
        assertTrue(registeredKeys.contains(rules.path("recovery").path("exhaustTag").asText()),
                "力竭标记必须已在键注册表里");
    }

    @Test
    void journeyStoryPassesWithStubbedChaptersAndNpcs() throws Exception {
        String story = read("journey-story.json");
        String returned = new JourneyStoryValidator().validate(story,
                JourneyStoryValidator.Context.of(STUB_CHAPTERS, STUB_NPCS));

        assertNotNull(returned);
        JsonNode parsed = objectMapper.readTree(returned);
        assertEquals(3, parsed.path("stages").size(), "三个阶段各对应一个现有章节");
        assertEquals(5, parsed.path("ending").path("epilogues").size(), "五个 NPC 都有尾声");
        assertEquals(5009L, parsed.path("ending").path("dispositionNodeId").asLong(),
                "处置由第九站(分支玩法)的结果决定");
        assertReferencedKeysAreRegistered("journey-story.json",
                StateKeyRefs.collectJourneyStory(story));
    }

    @Test
    void everyStationBlockConfigIsValidAndReferencesRegisteredKeys() throws Exception {
        Set<String> allRefs = new LinkedHashSet<String>();
        for (int station = 1; station <= 9; station++) {
            String name = String.format("nodes/station-%02d.json", station);
            String config = read(name);
            AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();
            assertNotNull(validator.validateAndNormalize(config), name + " 不应为空配置");
            assertTrue(validator.hasEnabledMechanic(config), name + " 必须带一个启用的玩法段");

            JsonNode root = objectMapper.readTree(config);
            int enabledGameplays = 0;
            for (String section : GAMEPLAY_SECTIONS) {
                if (root.path(section).path("enabled").asBoolean(false)) enabledGameplays++;
            }
            assertEquals(1, enabledGameplays, name + " 必须恰好映射到一种现有玩法");

            Set<String> refs = StateKeyRefs.collect(config);
            assertReferencedKeysAreRegistered(name, refs);
            allRefs.addAll(refs);
        }
        assertFalse(allRefs.isEmpty(), "九站配置合计必须引用到状态键");
    }

    @Test
    void theNarrativeFieldsCarryThePrototypeText() throws Exception {
        JsonNode chapters = objectMapper.readTree(read("story-flow.json"));
        JsonNode chapter1 = chapters.get(0);
        assertEquals(3, countBlocks(chapter1, 0, "enter", "q"), "第一站三个问题");
        assertTrue(block(chapter1, 0, "check", null) == null, "玩法段不搬进故事流");
        assertEquals("night", block(chapter1, 0, "enter", "q").path("qid").asText());
        assertEquals("约 6 分钟", block(chapter1, 0, "route", "walk").path("content").asText());
        assertEquals(2, countBlocks(chapter1, 0, "outcome", "fact"), "第一站两条事实");
        assertEquals(2, countBlocks(chapters.get(1), 0, "revisit", "change"), "复访站必须有变化段");

        JsonNode station1 = objectMapper.readTree(read("nodes/station-01.json"));
        assertTrue(station1.path("check").path("successText").asText().startsWith("阿蓉把联系卡推过来:"),
                station1.path("check").path("successText").asText());
        assertFalse(station1.has("enter"), "模板不再承载叙事段");
        JsonNode station9 = objectMapper.readTree(read("nodes/station-09.json"));
        assertEquals(4, station9.path("branch").path("steps").size(),
                "分支共四个步骤(一个入口 + 三个终点)");
    }

    /** R18:九站叙事全部住故事流,过与写接口同一套形状校验,when 引用同样对账注册表。 */
    @Test
    void everyStationNarrativeBlockIsValidAndReferencesRegisteredKeys() throws Exception {
        JsonNode chapters = objectMapper.readTree(read("story-flow.json"));
        assertTrue(chapters.isArray(), "story-flow.json 必须是章节数组");
        assertEquals(3, chapters.size(), "演示配置九站分三章");

        Set<String> narrativeRefs = new LinkedHashSet<String>();
        for (int chapterIndex = 0; chapterIndex < chapters.size(); chapterIndex++) {
            JsonNode chapter = chapters.get(chapterIndex);
            List<ChapterBlockDTO> blocks = new ArrayList<ChapterBlockDTO>();
            for (JsonNode block : chapter.path("blocks")) {
                blocks.add(objectMapper.treeToValue(block, ChapterBlockDTO.class));
            }
            assertEquals(3, chapter.path("nodes").size(), "每章三个站");
            ChapterNarrativeBlocks.validateChapter(blocks, chapterIndex + 1, 3);
            for (ChapterBlockDTO block : blocks) {
                if (block.getWhen() != null) {
                    narrativeRefs.addAll(StateKeyRefs.collectWhen(block.getWhen()));
                }
            }
        }
        assertFalse(narrativeRefs.isEmpty(), "叙事块的 when 必须真的引用到状态键");
        assertReferencedKeysAreRegistered("story-flow.json", narrativeRefs);
    }

    private int countBlocks(JsonNode chapter, int nodeIndex, String beat, String field) {
        int count = 0;
        for (JsonNode block : chapter.path("blocks")) {
            if (block.path("beat").asText().equals(beat)
                    && block.path("field").asText().equals(field)
                    && block.path("nodeIndex").asInt(-1) == nodeIndex) {
                count++;
            }
        }
        return count;
    }

    private JsonNode block(JsonNode chapter, int nodeIndex, String beat, String field) {
        for (JsonNode block : chapter.path("blocks")) {
            if (block.path("beat").asText().equals(beat)
                    && (field == null || block.path("field").asText().equals(field))
                    && block.path("nodeIndex").asInt(-1) == nodeIndex) {
                return block;
            }
        }
        return null;
    }

    @Test
    void everyGameplayTypeOfTheMappingIsExercised() throws Exception {
        Set<String> used = new HashSet<String>();
        for (int station = 1; station <= 9; station++) {
            JsonNode root = objectMapper.readTree(read(String.format("nodes/station-%02d.json", station)));
            for (String section : GAMEPLAY_SECTIONS) {
                if (root.path(section).path("enabled").asBoolean(false)) used.add(section);
            }
        }
        assertEquals(new HashSet<String>(Arrays.asList(GAMEPLAY_SECTIONS)), used,
                "七种玩法必须全部出现:" + used);
    }

    @Test
    void demoEpiloguesPickTheLineByRelationTriStateThroughTheRealService() throws Exception {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setDelFlag(0);
        topic.setJourneyStoryJson(read("journey-story.json"));
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);

        TopicRouteEngine.State state = new TopicRouteEngine.State();
        state.setStatus("ACTIVE");
        state.getVariables().put("relation.qiao", Integer.valueOf(2));
        state.getVariables().put("relation.arong", Integer.valueOf(-1));
        TopicRouteSessionMapper sessionMapper = mock(TopicRouteSessionMapper.class);
        TopicRouteSession endingSession = session(objectMapper.writeValueAsString(state));
        when(sessionMapper.selectMemberTopicSessions(900L, TOPIC_ID)).thenReturn(java.util.Collections.singletonList(endingSession));
        when(sessionMapper.selectCurrentByOwner(0L, TOPIC_ID, 900L)).thenAnswer(inv ->
                Long.valueOf(900L).equals(endingSession.getMemberId())
                        && Long.valueOf(TOPIC_ID).equals(endingSession.getTopicId())
                        && Long.valueOf(0L).equals(endingSession.getActivityId()) ? endingSession : null);

        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(new ArrayList<CmsTopicNode>());

        PlayJourneyStoryServiceImpl service = new PlayJourneyStoryServiceImpl(
                topicMapper, mock(CmsTopicChapterMapper.class), nodeMapper, sessionMapper,
                new ThemeStateKeyService(mock(ThemeStateKeyMapper.class)), mock(NpcProfileService.class));

        Map<String, Object> view = service.ending(900L, TOPIC_ID);

        List<?> epilogues = (List<?>) view.get("epilogues");
        assertEquals(5, epilogues.size(), "五个 NPC 都有尾声");
        Map<?, ?> qiao = (Map<?, ?>) epilogues.get(0);
        assertEquals("ally", qiao.get("state"), "relation.qiao=2 取 ally");
        assertEquals("「这次,我把依据和限制一起写进去。核对人:乔岚。」", qiao.get("line"));
        Map<?, ?> arong = (Map<?, ?>) epilogues.get(1);
        assertEquals("guarded", arong.get("state"), "relation.arong=-1 取 guarded");
        assertEquals("「授权页写得很清楚。照着做就行,别再追问。」", arong.get("line"));
        Map<?, ?> linzhou = (Map<?, ?>) epilogues.get(4);
        assertEquals("neutral", linzhou.get("state"), "relation.linzhou 缺省取 neutral");
        assertEquals("「老周转告我了。谢谢。」", linzhou.get("line"));
    }

    /**
     * R14-PLAY:第九站的三个处置选项经服务层写状态后,分别命中三个不同结局。
     *
     * <p>路径与生产一致:建局快照由 {@link NodeJourneySnapshot#copyJourneySegments} 冻结
     * (分支选项 effects + sys.outcome),选择走 {@link TopicRouteRuntimeServiceImpl#recordChoice}
     * 写进本局 state,结局走 {@link PlayJourneyStoryServiceImpl#ending} 按
     * journey-story 的 endings[].when(sys.outcome.5009)求值 —— 不需要真库。
     *
     * <p>负控:把 station-09 的分支选项 effects 删掉(退回节点级 SET 兜底)时,处置计数没人写,
     * 三个结局条件都不成立、全落进 default,本用例先在 counter.disposition 断言上红;
     * 把结局的 sys.outcome 条件删掉时,三个选项都落进 default,结局断言红。
     */
    @Test
    void threeDispositionChoicesHitThreeDifferentEndings() throws Exception {
        Set<String> codes = new LinkedHashSet<String>();
        codes.add(endingAfterBranchChoice("to_correct", "correct", 1));
        codes.add(endingAfterBranchChoice("to_exhibit", "exhibit", 2));
        codes.add(endingAfterBranchChoice("to_custody", "custody", 3));
        assertEquals(new LinkedHashSet<String>(Arrays.asList("correct", "exhibit", "custody")), codes,
                "三个处置必须各命中一个结局");
    }

    /**
     * 走一遍「选择写状态 + 结局选择」:返回结局 code,并断言处置计数与 sys.outcome 都按选项落位。
     */
    private String endingAfterBranchChoice(String optionId, String expectedCode, int expectedIndex)
            throws Exception {
        final Long memberId = 900L;
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setDelFlag(0);
        topic.setJourneyStoryJson(read("journey-story.json"));
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);

        TopicRouteSession[] stored = new TopicRouteSession[1];
        TopicRouteSessionMapper sessionMapper = mock(TopicRouteSessionMapper.class);
        when(sessionMapper.selectMemberTopicSessions(memberId, TOPIC_ID)).thenAnswer(inv -> java.util.Collections.singletonList(stored[0]));
        when(sessionMapper.selectCurrentByOwner(0L, TOPIC_ID, memberId)).thenAnswer(inv -> {
            TopicRouteSession current = stored[0];
            return current != null && memberId.equals(current.getMemberId())
                    && Long.valueOf(TOPIC_ID).equals(current.getTopicId())
                    && Long.valueOf(0L).equals(current.getActivityId()) ? current : null;
        });
        when(sessionMapper.insertAction(any())).thenReturn(1);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        // CORE OWN-C06-25：动作回执总以最终结算值回写原动作。
        when(sessionMapper.updateActionResponse(any(), any())).thenReturn(1);

        CmsTopicNode node = new CmsTopicNode();
        node.setId(5009L);
        node.setTopicId(TOPIC_ID);
        node.setStatus(1L);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        when(nodeMapper.selectCmsTopicNodeById(5009L)).thenReturn(node);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(new ArrayList<CmsTopicNode>());

        CmsRegistration pass = new CmsRegistration();
        pass.setExpiresAt(null);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        when(registrationMapper.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(java.util.Collections.singletonList(pass));

        TopicRouteSession session = new TopicRouteSession();
        session.setId(99L);
        session.setMemberId(memberId);
        session.setTopicId(TOPIC_ID);
        session.setActivityId(0L);
        session.setActivityId(0L);
        session.setStatus(TopicRouteSession.STATUS_RUNNING);
        session.setVersion(0);
        session.setStateJson("{\"status\":\"ACTIVE\",\"variables\":{}}");
        session.setConfigSnapshotJson(stationNineSnapshot());
        stored[0] = session;

        TopicRouteRuntimeServiceImpl runtime = new TopicRouteRuntimeServiceImpl(
                sessionMapper, topicMapper, nodeMapper, mock(CmsMemberTemplateMapper.class),
                mock(PlayerNodeProgressMapper.class), mock(CmsActivityMapper.class), registrationMapper,
                mock(PlayerActivityRoleMapper.class), mock(IShopDayProofService.class),
                () -> 12345L, () -> 10, java.time.Clock.systemUTC(), RouteReachabilityProvider.UNKNOWN);
        runtime.recordChoice(memberId, TOPIC_ID, 5009L, "ask_linzhou", optionId);

        JsonNode state = objectMapper.readTree(session.getStateJson());
        assertEquals(expectedIndex, state.path("variables").path("counter.disposition").asInt(),
                optionId + " 的 effects 必须当场写入");
        assertEquals(expectedIndex, state.path("variables").path("sys.outcome.5009").asInt(),
                optionId + " 的分支序号必须写进 sys.outcome");

        session.setStatus(TopicRouteSession.STATUS_COMPLETED);
        PlayJourneyStoryServiceImpl stories = new PlayJourneyStoryServiceImpl(
                topicMapper, mock(CmsTopicChapterMapper.class), nodeMapper, sessionMapper,
                new ThemeStateKeyService(mock(ThemeStateKeyMapper.class)), mock(NpcProfileService.class));
        Map<String, Object> view = stories.ending(memberId, TOPIC_ID);

        assertEquals(expectedCode, view.get("code"), optionId + " 应命中 " + expectedCode);
        return String.valueOf(view.get("code"));
    }

    /** 与建局冻结同一条链路:把 station-09 的块配置冻进 nodeJourney(含 choiceEffects 投影)。 */
    private String stationNineSnapshot() throws Exception {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("schemaVersion", 1);
        snapshot.put("startNodeId", 5009L);
        ObjectNode block = snapshot.putObject(NodeJourneySnapshot.NODE_JOURNEY_KEY)
                .putObject("5009");
        NodeJourneySnapshot.copyJourneySegments(objectMapper.readTree(read("nodes/station-09.json")), block);
        return objectMapper.writeValueAsString(snapshot);
    }

    private static TopicRouteSession session(String stateJson) {
        TopicRouteSession session = new TopicRouteSession();
        session.setMemberId(900L);
        session.setTopicId(TOPIC_ID);
        session.setActivityId(0L);
        session.setStatus(TopicRouteSession.STATUS_COMPLETED);
        session.setStateJson(stateJson);
        return session;
    }

    private void assertReferencedKeysAreRegistered(String where, Set<String> refs) {
        Set<String> missing = new LinkedHashSet<String>(refs);
        missing.removeAll(registeredKeys);
        assertTrue(missing.isEmpty(), where + " 引用了未注册的状态键:" + missing);
    }

    private List<ThemeStateKey> readStateKeys() throws Exception {
        JsonNode rows = objectMapper.readTree(read("state-keys.json"));
        assertTrue(rows.isArray(), "state-keys.json 必须是数组");
        List<ThemeStateKey> keys = new ArrayList<ThemeStateKey>();
        for (JsonNode row : rows) {
            ThemeStateKey key = new ThemeStateKey();
            key.setStateKey(row.path("key").asText());
            key.setKeyType(row.path("type").asText());
            key.setScope(row.path("scope").asText());
            key.setLabel(row.path("label").asText());
            key.setInitialValue(row.path("initialValue").isMissingNode() ? null : row.path("initialValue").asInt());
            keys.add(key);
        }
        return keys;
    }

    private String read(String resource) throws Exception {
        InputStream in = getClass().getResourceAsStream(ROOT + resource);
        assertNotNull(in, "缺少演示配置资源:" + ROOT + resource);
        Scanner scanner = new Scanner(in, "UTF-8").useDelimiter("\\A");
        String text = scanner.hasNext() ? scanner.next() : "";
        scanner.close();
        return text;
    }
}
