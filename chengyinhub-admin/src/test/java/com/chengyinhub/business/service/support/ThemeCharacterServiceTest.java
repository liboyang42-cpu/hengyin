package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.ThemeCharacter;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.ThemeCharacterMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** F2 T02:动态角色卡模板 / 跨局结转 / 每局重置 / 幂等 / 归属的书面契约。 */
class ThemeCharacterServiceTest {

    private static final long MEMBER = 11L;
    private static final long OWNER = 99L;
    private static final long TOPIC = 7L;
    private static final String RULES = "{\"schemaVersion\":1,\"stateEnabled\":true,\"attributes\":["
            + "{\"key\":\"counter.str\",\"label\":\"力量\",\"initial\":3,\"min\":0,\"max\":20},"
            + "{\"key\":\"counter.focus\",\"label\":\"专注\",\"initial\":5,\"min\":0,\"max\":10},"
            + "{\"key\":\"counter.secret\",\"label\":\"暗值\",\"initial\":1,\"visible\":false}]}";

    private ThemeStateKeyMapper keys;
    private ThemeCharacterMapper characters;
    private TopicRouteSessionMapper sessions;
    private CmsTopicMapper topics;
    private ThemeCharacterService service;

    private String freezeDefinitions() throws Exception {
        ObjectMapper json = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode snapshot = json.createObjectNode();
        snapshot.set("journeyRules", json.readTree(RULES));
        JourneyInitialState.freezeKeys(snapshot, keys.selectByTopicId(TOPIC), JourneyRules.parse(RULES), TOPIC);
        assertEquals(1, snapshot.path("stateKeySnapshotVersion").asInt(), "use the real new producer, not hand-built historical metadata");
        return snapshot.toString();
    }

    @Test
    void originalCardUsesPublishedDefinitionsAndRulesAfterLiveAuthorChanges() throws Exception {
        String snapshot = freezeDefinitions();
        TopicRouteSession original = session(41L, 6, TopicRouteSession.STATUS_RUNNING,
                "{\"variables\":{\"counter.str\":99,\"counter.focus\":7}}");
        original.setConfigSnapshotJson(snapshot);
        when(sessions.selectHistoryPage(eq(MEMBER), eq(TOPIC), isNull(), eq(1))).thenReturn(Collections.singletonList(original));
        topics.selectCmsTopicById(TOPIC).setJourneyRulesJson("{\"schemaVersion\":1,\"stateEnabled\":false}");
        when(keys.selectByTopicId(TOPIC)).thenThrow(new AssertionError("original card must not reread author definitions"));

        List<Map<String, Object>> rows = rows(service.card(MEMBER, TOPIC));
        assertEquals(2, rows.size()); assertEquals("counter.focus", rows.get(0).get("stateKey"));
        assertEquals(7, rows.get(0).get("value"));
        assertEquals("力量", rows.get(1).get("label")); assertEquals(20, rows.get(1).get("value"));
        assertEquals("topic", rows.get(1).get("persistScope"));
        verify(characters, never()).updateIfNewer(any()); verify(characters, never()).insertIfAbsent(any());
        assertEquals(snapshot, original.getConfigSnapshotJson());
    }

    @Test
    void newRunSeedsOriginalPublishedPersistPolicyWithoutChangingCarryLedgerPolicy() throws Exception {
        ObjectMapper json = new ObjectMapper();
        ObjectNode published = (ObjectNode) json.readTree(freezeDefinitions());
        published.set(ThemeAttributeDefinition.KEY, json.readTree(definitionSnapshot("topic", 20)).get(ThemeAttributeDefinition.KEY));
        String snapshot = published.toString();
        assertTrue(ThemeAttributeDefinition.read(snapshot).complete);
        TopicRouteSession previous = carrySession(41L, 6, TopicRouteSession.STATUS_COMPLETED,
                "{\"variables\":{\"counter.str\":9,\"counter.focus\":1}}");
        when(sessions.selectHistoryPage(eq(MEMBER), eq(TOPIC), isNull(), eq(1))).thenReturn(Collections.singletonList(previous));
        when(keys.selectByTopicId(TOPIC)).thenThrow(new AssertionError("new run must use its publication's definitions"));
        when(characters.updateIfNewer(any(ThemeCharacter.class))).thenReturn(1);
        TopicRouteEngine.State next = new TopicRouteEngine.State();
        JourneyRules rules = JourneyRules.parse(RULES); JourneyInitialState.initialize(rules, next);

        service.seedRun(MEMBER, TOPIC, rules, next, snapshot);

        assertEquals(9, next.getVariables().get("counter.str").intValue());
        assertEquals(5, next.getVariables().get("counter.focus").intValue());
        ArgumentCaptor<ThemeCharacter> written = ArgumentCaptor.forClass(ThemeCharacter.class);
        verify(characters).updateIfNewer(written.capture());
        assertEquals(41L, written.getValue().getSourceSessionId()); assertEquals(6, written.getValue().getSourceVersion());
        assertEquals("{\"counter.str\":9}", written.getValue().getValuesJson());
    }

    @Test
    void unsupportedDefinitionVersionDoesNotReadLiveOrWriteCarryValues() {
        TopicRouteEngine.State next = new TopicRouteEngine.State();
        assertThrows(IllegalArgumentException.class, () -> service.seedRun(MEMBER, TOPIC, JourneyRules.parse(RULES), next,
                "{\"stateKeySnapshotVersion\":2,\"stateKeys\":[]}"));
        verifyNoInteractions(keys, characters, sessions);
        assertTrue(next.getVariables().isEmpty(), "unsupported metadata must fail before changing new-run values");
        ObjectNode marked = new ObjectMapper().valueToTree(Map.of(
                "stateKeySnapshotVersion", 2, "stateKeys", java.util.Collections.emptyList()));
        try {
            marked.set(ThemeAttributeDefinition.KEY,
                    new ObjectMapper().readTree(definitionSnapshot("topic", 20)).get(ThemeAttributeDefinition.KEY));
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossibleFixture) {
            throw new AssertionError(impossibleFixture);
        }
        assertThrows(IllegalArgumentException.class, () -> service.seedRun(
                MEMBER, TOPIC, JourneyRules.parse(RULES), next, marked.toString()));
        assertTrue(next.getVariables().isEmpty());
        verifyNoInteractions(keys, characters, sessions);
    }

    @BeforeEach
    void setUp() {
        keys = mock(ThemeStateKeyMapper.class);
        characters = mock(ThemeCharacterMapper.class);
        sessions = mock(TopicRouteSessionMapper.class);
        topics = mock(CmsTopicMapper.class);
        service = new ThemeCharacterService(keys, characters, sessions, topics);
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(OWNER);
        topic.setPublishedConfigVersion(3L);
        topic.setJourneyRulesJson(RULES);
        when(topics.selectCmsTopicById(TOPIC)).thenReturn(topic);
        ThemeStateKey str = key(1L, "counter.str", "力量", ThemeStateKey.PERSIST_TOPIC);
        str.setSortOrder(2); str.setInitialValue(3); str.setMaxValue(20);
        ThemeStateKey focus = key(2L, "counter.focus", "专注", null);
        focus.setSortOrder(1); focus.setInitialValue(5);
        focus.setMaxValue(10);
        ThemeStateKey secret = key(3L, "counter.secret", "暗值", null);
        secret.setVisible(Boolean.FALSE);
        ThemeStateKey offCard = key(4L, "counter.off", "不上卡", null);
        offCard.setVisible(Boolean.FALSE);
        ThemeStateKey tag = key(5L, "tag.hurt", "受伤", null);
        tag.setKeyType(ThemeStateKey.TYPE_TAG);
        when(keys.selectByTopicId(TOPIC)).thenReturn(Arrays.asList(str, focus, secret, offCard, tag));
    }

    @Test
    void noCharacterYetReturnsTheTemplateWithInitialValues() {
        Map<String, Object> card = service.card(MEMBER, TOPIC);

        assertEquals(Boolean.FALSE, card.get("created"));
        assertEquals("REGISTRY_TEMPLATE", card.get("definitionSource"));
        assertNull(card.get("configVersion"), "发布版本不能冒充模板定义版本");
        List<Map<String, Object>> rows = rows(card);
        assertEquals(2, rows.size(), "只展示可见的 counter;journeyRules 隐藏属性、visible=false、tag 都不上卡");
        assertEquals("counter.focus", rows.get(0).get("stateKey"), "按 sortOrder 升序");
        assertEquals(5, rows.get(0).get("value"), "DEC02模板初值取注册定义");
        assertEquals("number", rows.get(0).get("displayType"));
        assertEquals("run", rows.get(0).get("persistScope"));
        assertEquals("counter.str", rows.get(1).get("stateKey"));
        assertEquals(3, rows.get(1).get("value"));
        assertEquals(0, rows.get(1).get("min"));
        assertEquals(20, rows.get(1).get("max"), "模板范围取注册定义");
        assertEquals("topic", rows.get(1).get("persistScope"));
        verify(characters, never()).updateIfNewer(any(ThemeCharacter.class));
        verify(characters, never()).insertIfAbsent(any(ThemeCharacter.class));
    }

    @Test
    void legacyCardHasNoRpgBlockAndRpgTopicAddsFacetsMomentumAndActiveAspects() {
        assertTrue(!service.card(MEMBER, TOPIC).containsKey("rpg"), "F6:没配 rpg 的主题角色卡响应不变");
        String rpgRules = RULES.substring(0, RULES.length() - 1) + ",\"checkEnabled\":true,\"rpg\":{"
                + "\"momentum\":{\"enabled\":true,\"max\":5,\"init\":1},"
                + "\"aspects\":[{\"key\":\"night_owl\",\"label\":\"夜猫子\",\"kind\":\"facet\"},"
                + "{\"key\":\"soaked\",\"label\":\"浑身湿透\"}]}}";
        topics.selectCmsTopicById(TOPIC).setJourneyRulesJson(rpgRules);
        Map<?, ?> template = (Map<?, ?>) service.card(MEMBER, TOPIC).get("rpg");
        assertEquals(1, ((Map<?, ?>) template.get("momentum")).get("value"), "没建局:动量显示初值");
        assertEquals(1, ((List<?>) template.get("facets")).size(), "侧面常驻");
        assertEquals(0, ((List<?>) template.get("aspects")).size(), "特质只在本局挂着时出现");
        when(sessions.selectHistoryPage(eq(MEMBER), eq(TOPIC), isNull(), eq(1)))
                .thenReturn(Collections.singletonList(session(42L, 3, TopicRouteSession.STATUS_RUNNING,
                        "{\"variables\":{\"sys.momentum\":4},\"statusConditions\":[\"night_owl\",\"soaked\"]}")));
        Map<?, ?> live = (Map<?, ?>) service.card(MEMBER, TOPIC).get("rpg");
        assertEquals(4, ((Map<?, ?>) live.get("momentum")).get("value"));
        assertEquals("soaked", ((Map<?, ?>) ((List<?>) live.get("aspects")).get(0)).get("key"));
    }

    @Test
    void topicScopedValueSurvivesIntoTheNextRunWhileRunScopedValueResets() {
        when(sessions.selectHistoryPage(eq(MEMBER), eq(TOPIC), isNull(), eq(1)))
                .thenReturn(Collections.singletonList(carrySession(41L, 6, TopicRouteSession.STATUS_COMPLETED,
                        "{\"variables\":{\"counter.str\":9,\"counter.focus\":1}}")));
        when(characters.updateIfNewer(any(ThemeCharacter.class))).thenReturn(0);
        TopicRouteEngine.State state = freshRun();

        service.seedRun(MEMBER, TOPIC, JourneyRules.parse(RULES), state, definitionSnapshot("topic", 20));

        assertEquals(9, state.getVariables().get("counter.str"), "persistScope=topic 跨局保留上一局的权威值");
        assertEquals(5, state.getVariables().get("counter.focus"), "run 作用域每局从初值重置");
        ArgumentCaptor<ThemeCharacter> written = ArgumentCaptor.forClass(ThemeCharacter.class);
        verify(characters).insertIfAbsent(written.capture());
        assertEquals("{\"counter.str\":9}", written.getValue().getValuesJson(), "台账只存 topic 作用域");
        assertEquals(Long.valueOf(41L), written.getValue().getSourceSessionId());
        assertEquals(Integer.valueOf(6), written.getValue().getSourceVersion());
    }

    @Test
    void replayingTheSameSourceDoesNotRewriteTheLedger() {
        when(sessions.selectHistoryPage(eq(MEMBER), eq(TOPIC), isNull(), eq(1)))
                .thenReturn(Collections.singletonList(carrySession(41L, 6, TopicRouteSession.STATUS_COMPLETED,
                        "{\"variables\":{\"counter.str\":2}}")));
        when(characters.selectByMemberAndTopic(MEMBER, TOPIC)).thenReturn(character("{\"counter.str\":12}", 41L, 6));
        TopicRouteEngine.State state = freshRun();

        service.seedRun(MEMBER, TOPIC, JourneyRules.parse(RULES), state, definitionSnapshot("topic", 20));

        assertEquals(2, state.getVariables().get("counter.str"), "DEC02运行值取来源局state；同版本台账不重复写");
        verify(characters, never()).updateIfNewer(any(ThemeCharacter.class));
        verify(characters, never()).insertIfAbsent(any(ThemeCharacter.class));
    }

    @Test
    void firstRecordRechecksSourceAfterInsertRegardlessOfDriverAffectedRows() {
        when(sessions.selectHistoryPage(eq(MEMBER), eq(TOPIC), isNull(), eq(1)))
                .thenReturn(Collections.singletonList(carrySession(41L, 6, TopicRouteSession.STATUS_COMPLETED,
                        "{\"variables\":{\"counter.str\":9}}")));
        when(characters.updateIfNewer(any(ThemeCharacter.class))).thenReturn(0, 1);
        // MySQL CLIENT_FOUND_ROWS can report 1 for an unchanged duplicate insert.
        when(characters.insertIfAbsent(any(ThemeCharacter.class))).thenReturn(1);

        service.seedRun(MEMBER, TOPIC, JourneyRules.parse(RULES), freshRun(), definitionSnapshot("topic", 20));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(characters);
        ArgumentCaptor<ThemeCharacter> value = ArgumentCaptor.forClass(ThemeCharacter.class);
        order.verify(characters).updateIfNewer(value.capture());
        order.verify(characters).insertIfAbsent(value.getValue());
        order.verify(characters).updateIfNewer(value.getValue());
        assertEquals(Long.valueOf(41L), value.getValue().getSourceSessionId());
        assertEquals(Integer.valueOf(6), value.getValue().getSourceVersion());
    }

    @Test
    void carriedValueIsClampedOnlyToNewFrozenRange() {
        when(characters.selectByMemberAndTopic(MEMBER, TOPIC)).thenReturn(character("{\"counter.str\":80}", 41L, 6));
        when(sessions.selectHistoryPage(MEMBER, TOPIC, null, 1)).thenReturn(Collections.singletonList(
                carrySession(41L, 6, TopicRouteSession.STATUS_COMPLETED, "{\"variables\":{\"counter.str\":80}}")));
        TopicRouteEngine.State state = freshRun();

        service.seedRun(MEMBER, TOPIC, JourneyRules.parse(RULES), state, definitionSnapshot("topic", 20));

        assertEquals(20, state.getVariables().get("counter.str"), "新冻结max=20限制新局；旧局80不被改写");
    }

    @Test
    void topicWithoutPersistedKeysNeverTouchesTheLedger() {
        when(keys.selectByTopicId(TOPIC)).thenReturn(Collections.singletonList(key(2L, "counter.focus", "专注", null)));
        TopicRouteEngine.State state = freshRun();

        service.seedRun(MEMBER, TOPIC, JourneyRules.parse(RULES), state, definitionSnapshot("run", 20));

        verifyNoInteractions(characters, sessions);
        assertEquals(3, state.getVariables().get("counter.str"));
    }

    @Test
    void runningSessionIsTheLiveSourceOfTheCard() {
        when(sessions.selectHistoryPage(eq(MEMBER), eq(TOPIC), isNull(), eq(1)))
                .thenReturn(Collections.singletonList(session(42L, 2, TopicRouteSession.STATUS_RUNNING,
                        "{\"variables\":{\"counter.str\":7,\"counter.focus\":99}}")));

        List<Map<String, Object>> rows = rows(service.card(MEMBER, TOPIC));

        assertEquals(10, rows.get(0).get("value"), "注册表 max=10 封顶展示值");
        assertEquals(7, rows.get(1).get("value"));
    }

    @Test
    void runWithoutCompleteFrozenDefinitionDisplaysCurrentRegistryAfterAuthorEdits() {
        TopicRouteSession running = frozenSession(RULES);
        when(sessions.selectHistoryPage(eq(MEMBER), eq(TOPIC), isNull(), eq(1)))
                .thenReturn(Collections.singletonList(running));
        Map<String, Object> before = service.card(MEMBER, TOPIC);

        topics.selectCmsTopicById(TOPIC).setJourneyRulesJson("{\"schemaVersion\":1,\"stateEnabled\":true,\"attributes\":["
                + "{\"key\":\"counter.str\",\"label\":\"新版力量\",\"initial\":1,\"min\":0,\"max\":2},"
                + "{\"key\":\"counter.secret\",\"label\":\"新版公开\",\"initial\":1,\"visible\":true}]}");
        ThemeStateKey changed = key(1L, "counter.str", "新版注册名", ThemeStateKey.PERSIST_RUN);
        changed.setMaxValue(2);
        when(keys.selectByTopicId(TOPIC)).thenReturn(Collections.singletonList(changed));

        Map<String, Object> after = service.card(MEMBER, TOPIC);

        // D3(2026-10-27 用户决策):本局没有完整冻结定义时,属性定义按当前注册表展示(仅展示)。
        assertEquals("LEGACY_LIVE", before.get("definitionSource"));
        assertEquals(Arrays.asList("专注", "力量"), Arrays.asList(rows(before).get(0).get("label"), rows(before).get(1).get("label")));
        assertEquals("LEGACY_LIVE", after.get("definitionSource"));
        assertNull(after.get("configVersion"), "局版本不能给实时定义背书");
        assertEquals(1, rows(after).size());
        assertEquals("新版注册名", rows(after).get(0).get("label"));
        assertEquals(2, rows(after).get(0).get("value"), "本局运行值按当前注册表范围夹取展示");
        assertEquals(2, rows(after).get(0).get("max"));
        assertEquals("run", rows(after).get(0).get("persistScope"));
        assertEquals("number", rows(after).get(0).get("displayType"));
        verify(keys, org.mockito.Mockito.times(2)).selectByTopicId(TOPIC);
        verify(characters, org.mockito.Mockito.times(2)).selectByMemberAndTopic(MEMBER, TOPIC);
        verify(sessions, org.mockito.Mockito.times(2)).selectHistoryPage(MEMBER, TOPIC, null, 1);
        verify(topics, org.mockito.Mockito.times(3)).selectCmsTopicById(TOPIC);
        verifyNoMoreInteractions(characters, sessions, topics);
    }

    @Test
    void oldSessionWithoutAttributeSnapshotKeepsLiveDefinitionsButHasNoKnownDefinitionVersion() {
        TopicRouteSession legacy = session(42L, 8, TopicRouteSession.STATUS_RUNNING,
                "{\"variables\":{\"counter.str\":7,\"counter.focus\":9}}");
        legacy.setConfigVersion(2L);
        legacy.setConfigSnapshotJson("{\"journeyRules\":{\"checkEnabled\":false},\"stateKeys\":[{\"key\":\"counter.str\",\"label\":\"旧名\"}]}");
        when(sessions.selectHistoryPage(eq(MEMBER), eq(TOPIC), isNull(), eq(1)))
                .thenReturn(Collections.singletonList(legacy));

        Map<String, Object> card = service.card(MEMBER, TOPIC);

        assertEquals("LEGACY_LIVE", card.get("definitionSource"));
        assertNull(card.get("configVersion"), "局版本不能给实时定义背书");
        assertEquals(9, rows(card).get(0).get("value"));
        assertEquals("力量", rows(card).get(1).get("label"));
        verifyCardReadsOnly(true);
    }

    @Test
    void emptyTemplateDoesNotCreateCharacterOrSession() {
        topics.selectCmsTopicById(TOPIC).setJourneyRulesJson(null);
        when(keys.selectByTopicId(TOPIC)).thenReturn(Collections.emptyList());

        Map<String, Object> card = service.card(MEMBER, TOPIC);

        assertEquals(Boolean.FALSE, card.get("created"));
        assertEquals(Boolean.FALSE, card.get("running"));
        assertEquals("REGISTRY_TEMPLATE", card.get("definitionSource"));
        assertNull(card.get("configVersion"));
        assertTrue(rows(card).isEmpty());
        verify(topics, org.mockito.Mockito.times(2)).selectCmsTopicById(TOPIC);
        verify(keys).selectByTopicId(TOPIC);
        verify(characters).selectByMemberAndTopic(MEMBER, TOPIC);
        verify(sessions).selectHistoryPage(MEMBER, TOPIC, null, 1);
        verifyNoMoreInteractions(keys, characters, sessions, topics);
    }

    @Test
    void partialSnapshotDisplaysCurrentRegistryDefinitions() {
        TopicRouteSession partial = frozenSession(RULES);
        partial.setConfigSnapshotJson("{\"journeyRules\":{\"schemaVersion\":1,\"stateEnabled\":true,\"attributes\":["
                + "{\"key\":\"counter.str\",\"label\":\"未知可见性\",\"initial\":3,\"min\":0,\"max\":20},"
                + "{\"key\":\"counter.focus\",\"label\":\"未知范围\",\"initial\":5,\"visible\":true},"
                + "{\"key\":\"counter.secret\",\"label\":\"隐藏\",\"initial\":1,\"min\":0,\"max\":10,\"visible\":false}]}}");
        when(sessions.selectHistoryPage(eq(MEMBER), eq(TOPIC), isNull(), eq(1)))
                .thenReturn(Collections.singletonList(partial));

        Map<String, Object> card = service.card(MEMBER, TOPIC);

        assertEquals("LEGACY_LIVE", card.get("definitionSource"));
        assertEquals("专注", rows(card).get(0).get("label"), "定义取当前注册表,不取残缺快照的标签");
        assertEquals(10, rows(card).get(0).get("value"));
        assertEquals(7, rows(card).get(1).get("value"));
        assertEquals(2, rows(card).size(), "注册表隐藏键仍不上卡");
        verifyCardReadsOnly(true);
    }

    @Test
    void malformedExistingSnapshotDisplaysCurrentRegistryDefinitions() {
        TopicRouteSession invalid = frozenSession(RULES);
        invalid.setConfigVersion(null);
        invalid.setConfigSnapshotJson("{\"journeyRules\":{\"attributes\":null}}");
        when(sessions.selectHistoryPage(eq(MEMBER), eq(TOPIC), isNull(), eq(1)))
                .thenReturn(Collections.singletonList(invalid));

        Map<String, Object> card = service.card(MEMBER, TOPIC);

        assertEquals("LEGACY_LIVE", card.get("definitionSource"));
        assertNull(card.get("configVersion"));
        assertEquals(2, rows(card).size());
        verifyCardReadsOnly(true);
    }

    @Test
    void frozenEmptyAttributesWithoutDefinitionDisplayCurrentRegistry() {
        TopicRouteSession empty = frozenSession("{\"schemaVersion\":1,\"stateEnabled\":true,\"attributes\":[]}");
        when(sessions.selectHistoryPage(eq(MEMBER), eq(TOPIC), isNull(), eq(1)))
                .thenReturn(Collections.singletonList(empty));

        Map<String, Object> card = service.card(MEMBER, TOPIC);

        assertEquals("LEGACY_LIVE", card.get("definitionSource"));
        assertEquals(2, rows(card).size());
        verifyCardReadsOnly(true);
    }

    @Test
    void topicAndConfigVersionRemainExactBeyondJavascriptSafeIntegers() {
        CmsTopic topic = new CmsTopic();
        topic.setId(Long.MAX_VALUE);
        topic.setMemberId(OWNER);
        topic.setPublishedConfigVersion(Long.MAX_VALUE);
        when(topics.selectCmsTopicById(Long.MAX_VALUE)).thenReturn(topic);
        TopicRouteSession running = carrySession(42L, 8, TopicRouteSession.STATUS_RUNNING, "{}");
        running.setConfigVersion(Long.MAX_VALUE);
        running.setConfigSnapshotJson(definitionSnapshot("topic", 20)
                .replace("\"configVersion\":\"3\"", "\"configVersion\":\"9223372036854775807\""));
        when(sessions.selectHistoryPage(eq(MEMBER), eq(Long.MAX_VALUE), isNull(), eq(1)))
                .thenReturn(Collections.singletonList(running));

        Map<String, Object> card = service.card(MEMBER, Long.MAX_VALUE);

        assertEquals("9223372036854775807", card.get("topicId"));
        assertEquals("9223372036854775807", card.get("configVersion"));
        ObjectNode serialized = new ObjectMapper().valueToTree(card);
        assertTrue(serialized.path("topicId").isTextual());
        assertTrue(serialized.path("configVersion").isTextual());
        verifyNoInteractions(keys);
    }

    @Test
    void invalidFrozenIntegerRangesNeverReachTheCardAndRegistryIsShownInstead() {
        TopicRouteSession invalid = frozenSession(RULES);
        when(sessions.selectHistoryPage(eq(MEMBER), eq(TOPIC), isNull(), eq(1)))
                .thenReturn(Collections.singletonList(invalid));
        for (String range : Arrays.asList("\"initial\":3,\"min\":0,\"max\":9223372036854775807",
                "\"initial\":9223372036854775807,\"min\":0,\"max\":20",
                "\"initial\":3,\"min\":9223372036854775807,\"max\":20",
                "\"initial\":3,\"min\":10,\"max\":2")) {
            invalid.setConfigSnapshotJson("{\"journeyRules\":{\"schemaVersion\":1,\"stateEnabled\":true,\"attributes\":["
                    + "{\"key\":\"counter.str\",\"label\":\"坏范围\",\"visible\":true," + range + "}]}}");

            Map<String, Object> card = service.card(MEMBER, TOPIC);

            assertEquals("LEGACY_LIVE", card.get("definitionSource"));
            assertEquals(Arrays.asList(10, 20), Arrays.asList(rows(card).get(0).get("max"), rows(card).get(1).get("max")),
                    "原始整数溢出或 min>max 的冻结范围不能进卡,只按注册表范围展示");
        }
        verify(characters, never()).updateIfNewer(any(ThemeCharacter.class));
        verify(characters, never()).insertIfAbsent(any(ThemeCharacter.class));
    }

    private void verifyCardReadsOnly(boolean liveKeys) {
        verify(topics).selectCmsTopicById(TOPIC);
        verify(characters).selectByMemberAndTopic(MEMBER, TOPIC);
        verify(sessions).selectHistoryPage(MEMBER, TOPIC, null, 1);
        if (liveKeys) verify(keys).selectByTopicId(TOPIC);
        verifyNoMoreInteractions(topics, characters, sessions, keys);
    }

    private static TopicRouteSession frozenSession(String rules) {
        ObjectMapper json = new ObjectMapper();
        ObjectNode snapshot = json.createObjectNode();
        JourneyRules parsed = JourneyRules.parse(rules);
        snapshot.set(NodeJourneySnapshot.JOURNEY_RULES_KEY, json.valueToTree(parsed));
        JourneyInitialState.freezeKeys(snapshot, Collections.emptyList(), parsed);
        TopicRouteSession session = session(42L, 8, TopicRouteSession.STATUS_RUNNING,
                "{\"variables\":{\"counter.str\":7,\"counter.focus\":99,\"counter.secret\":8}}");
        session.setConfigVersion(2L);
        session.setConfigSnapshotJson(snapshot.toString());
        return session;
    }

    @Test
    void unpublishedTopicOfSomeoneElseIsNotFound() {
        CmsTopic draft = new CmsTopic();
        draft.setId(8L);
        draft.setMemberId(OWNER);
        when(topics.selectCmsTopicById(8L)).thenReturn(draft);

        ServiceException denied = assertThrows(ServiceException.class, () -> service.card(MEMBER, 8L));
        assertEquals(Integer.valueOf(404), denied.getCode());
        assertEquals("CHARACTER_TOPIC_NOT_FOUND", denied.getDetailMessage());
        assertEquals(Boolean.FALSE, service.card(OWNER, 8L).get("created"), "作者本人可预览自己未发布主题的模板");
        assertThrows(ServiceException.class, () -> service.card(null, TOPIC));
    }

    @Test
    void cardRulesComeBackAsObject() throws Exception {
        topics.selectCmsTopicById(TOPIC).setJourneyRulesJson("{\"schemaVersion\":1,\"stateEnabled\":true,\"attributes\":[{\"key\":\"counter.rank\",\"label\":\"阶位\",\"initial\":0,\"min\":0,\"max\":100}]}");
        ThemeStateKey leveled = key(6L, "counter.rank", "阶位", null);
        leveled.setRulesJson("{\"levels\":[{\"at\":10,\"label\":\"新手\"}]}");
        when(keys.selectByTopicId(TOPIC)).thenReturn(Collections.singletonList(leveled));

        Map<String, Object> row = rows(service.card(MEMBER, TOPIC)).get(0);

        assertEquals("新手", new ObjectMapper().valueToTree(row.get("rules")).path("levels").path(0).path("label").asText());
        assertNull(row.get("color"));
        assertTrue(row.containsKey("icon"));
    }

    private static TopicRouteEngine.State freshRun() {
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        JourneyInitialState.initialize(JourneyRules.parse(RULES), state);
        return state;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> card) {
        return (List<Map<String, Object>>) card.get("attributes");
    }

    private static ThemeStateKey key(long id, String name, String label, String persist) {
        ThemeStateKey key = new ThemeStateKey();
        key.setId(id);
        key.setTopicId(TOPIC);
        key.setStateKey(name);
        key.setKeyType(ThemeStateKey.TYPE_COUNTER);
        key.setScope(ThemeStateKey.SCOPE_RUN);
        key.setLabel(label);
        key.setInitialValue(0);
        key.setPersistScope(persist);
        return key;
    }

    private static TopicRouteSession session(long id, int version, int status, String stateJson) {
        TopicRouteSession session = new TopicRouteSession();
        session.setId(id);
        session.setVersion(version);
        session.setStatus(status);
        session.setStateJson(stateJson);
        return session;
    }

    @Test
    void scopeChangesAndUnknownOldScopeStartFromNewFrozenInitial() {
        for (String oldScope : Arrays.asList("run", "topic", "unknown")) {
            for (String newScope : Arrays.asList("run", "topic")) {
                TopicRouteSession previous = carrySession(41L, 6, TopicRouteSession.STATUS_COMPLETED,
                        "{\"variables\":{\"counter.str\":9}}");
                previous.setConfigSnapshotJson("unknown".equals(oldScope) ? "{\"journeyRules\":{}}"
                        : definitionSnapshot(oldScope, 20));
                when(sessions.selectHistoryPage(MEMBER, TOPIC, null, 1)).thenReturn(Collections.singletonList(previous));
                TopicRouteEngine.State next = freshRun();
                service.seedRun(MEMBER, TOPIC, JourneyRules.parse(RULES), next, definitionSnapshot(newScope, 20));
                assertEquals("topic".equals(oldScope) && "topic".equals(newScope) ? 9 : 3,
                        next.getVariables().get("counter.str"), oldScope + " -> " + newScope);
            }
        }
        verifyNoInteractions(keys);
    }

    @Test
    void oldSeedSignatureNeverInfersScopeFromCurrentRegistryOrLedger() {
        TopicRouteEngine.State state = freshRun();
        service.seedRun(MEMBER, TOPIC, JourneyRules.parse(RULES), state);
        assertEquals(3, state.getVariables().get("counter.str"));
        verifyNoInteractions(keys, sessions, characters);
    }

    @Test
    void completedVersionedCardKeepsFrozenDefinitionAndFinalRunValues() {
        TopicRouteSession previous = carrySession(41L, 6, TopicRouteSession.STATUS_COMPLETED,
                "{\"variables\":{\"counter.str\":9,\"counter.focus\":1}}");
        when(sessions.selectHistoryPage(MEMBER, TOPIC, null, 1)).thenReturn(Collections.singletonList(previous));
        Map<String, Object> card = service.card(MEMBER, TOPIC);
        assertEquals("SESSION_SNAPSHOT_V1", card.get("definitionSource"));
        assertEquals(1, rows(card).get(1).get("value"), "已结束局读取不按新局初值重置");
        assertEquals("topic", rows(card).get(0).get("persistScope"));
        assertEquals("bar", rows(card).get(0).get("displayType"));
        verifyNoInteractions(keys);
    }

    @Test
    void unsupportedOrIncompleteDefinitionDisplaysCurrentRegistry() {
        for (String snapshot : Arrays.asList(definitionSnapshot("topic", 20).replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                definitionSnapshot("topic", 20).replace(",\"persistScope\":\"topic\"", ""),
                definitionSnapshot("topic", 20).replace("\"configVersion\":\"3\"", "\"configVersion\":\"4\""))) {
            TopicRouteSession previous = carrySession(41L, 6, TopicRouteSession.STATUS_RUNNING, "{}");
            previous.setConfigSnapshotJson(snapshot);
            when(sessions.selectHistoryPage(MEMBER, TOPIC, null, 1)).thenReturn(Collections.singletonList(previous));
            Map<String, Object> card = service.card(MEMBER, TOPIC);
            assertEquals("LEGACY_LIVE", card.get("definitionSource"));
            assertEquals(2, rows(card).size());
            assertNull(card.get("configVersion"));
        }
        verify(characters, never()).updateIfNewer(any(ThemeCharacter.class));
        verify(characters, never()).insertIfAbsent(any(ThemeCharacter.class));
    }

    private static String definitionSnapshot(String persist, int max) {
        ObjectMapper json = new ObjectMapper();
        ObjectNode root = json.createObjectNode();
        ObjectNode definition = root.putObject(ThemeAttributeDefinition.KEY);
        definition.put("schemaVersion", 1).put("configVersion", "3").put("registryVersion", "1");
        com.fasterxml.jackson.databind.node.ArrayNode rows = definition.putArray("attributes");
        for (String name : Arrays.asList("str", "focus")) {
            boolean strength = "str".equals(name);
            rows.addObject().put("key", "counter." + name).put("keyType", "counter")
                    .put("label", strength ? "力量" : "专注").put("initial", strength ? 3 : 5)
                    .put("min", 0).put("max", strength ? max : 10).put("visible", true)
                    .put("displayType", "bar").put("persistScope", strength ? persist : "run")
                    .putNull("color").putNull("icon").putNull("sortOrder").putNull("rules");
        }
        return root.toString();
    }

    private static TopicRouteSession carrySession(long id, int version, int status, String stateJson) {
        TopicRouteSession result = session(id, version, status, stateJson);
        result.setConfigVersion(3L); result.setConfigSnapshotJson(definitionSnapshot("topic", 100));
        return result;
    }

    private static ThemeCharacter character(String values, long sourceSession, int sourceVersion) {
        ThemeCharacter character = new ThemeCharacter();
        character.setMemberId(MEMBER);
        character.setTopicId(TOPIC);
        character.setValuesJson(values);
        character.setSourceSessionId(sourceSession);
        character.setSourceVersion(sourceVersion);
        return character;
    }
}
