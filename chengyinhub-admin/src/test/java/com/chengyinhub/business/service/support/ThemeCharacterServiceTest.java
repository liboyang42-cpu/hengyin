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
        str.setSortOrder(2);
        ThemeStateKey focus = key(2L, "counter.focus", "专注", null);
        focus.setSortOrder(1);
        focus.setMaxValue(10);
        ThemeStateKey secret = key(3L, "counter.secret", "暗值", null);
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
        assertEquals("TEMPLATE_LIVE", card.get("definitionSource"));
        assertNull(card.get("configVersion"), "发布版本不能冒充模板定义版本");
        List<Map<String, Object>> rows = rows(card);
        assertEquals(2, rows.size(), "只展示可见的 counter;journeyRules 隐藏属性、visible=false、tag 都不上卡");
        assertEquals("counter.focus", rows.get(0).get("stateKey"), "按 sortOrder 升序");
        assertEquals(5, rows.get(0).get("value"), "初值以建局真用的 journeyRules.attributes 为准");
        assertEquals("number", rows.get(0).get("displayType"));
        assertEquals("run", rows.get(0).get("persistScope"));
        assertEquals("counter.str", rows.get(1).get("stateKey"));
        assertEquals(3, rows.get(1).get("value"));
        assertEquals(0, rows.get(1).get("min"));
        assertEquals(100, rows.get(1).get("max"), "范围未配 = 老口径 0..100");
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
                .thenReturn(Collections.singletonList(session(41L, 6, TopicRouteSession.STATUS_COMPLETED,
                        "{\"variables\":{\"counter.str\":9,\"counter.focus\":1}}")));
        when(characters.updateIfNewer(any(ThemeCharacter.class))).thenReturn(0);
        TopicRouteEngine.State state = freshRun();

        service.seedRun(MEMBER, TOPIC, JourneyRules.parse(RULES), state);

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
                .thenReturn(Collections.singletonList(session(41L, 6, TopicRouteSession.STATUS_COMPLETED,
                        "{\"variables\":{\"counter.str\":2}}")));
        when(characters.selectByMemberAndTopic(MEMBER, TOPIC)).thenReturn(character("{\"counter.str\":12}", 41L, 6));
        TopicRouteEngine.State state = freshRun();

        service.seedRun(MEMBER, TOPIC, JourneyRules.parse(RULES), state);

        assertEquals(12, state.getVariables().get("counter.str"), "来源未更新:台账值为准,局状态里的旧值不覆盖");
        verify(characters, never()).updateIfNewer(any(ThemeCharacter.class));
        verify(characters, never()).insertIfAbsent(any(ThemeCharacter.class));
    }

    @Test
    void carriedValueIsClampedToBothRegistryAndRuleRange() {
        when(characters.selectByMemberAndTopic(MEMBER, TOPIC)).thenReturn(character("{\"counter.str\":80}", 41L, 6));
        TopicRouteEngine.State state = freshRun();

        service.seedRun(MEMBER, TOPIC, JourneyRules.parse(RULES), state);

        assertEquals(20, state.getVariables().get("counter.str"), "journeyRules max=20 比注册表默认 100 更紧");
    }

    @Test
    void topicWithoutPersistedKeysNeverTouchesTheLedger() {
        when(keys.selectByTopicId(TOPIC)).thenReturn(Collections.singletonList(key(2L, "counter.focus", "专注", null)));
        TopicRouteEngine.State state = freshRun();

        service.seedRun(MEMBER, TOPIC, JourneyRules.parse(RULES), state);

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
    void runningDefinitionsStayWithTheSessionAfterTheAuthorEditsRulesAndRegistry() {
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

        assertEquals(before, after, "作者修改不能改变运行局的定义、hidden、范围或数值");
        assertEquals("SESSION_SNAPSHOT_PARTIAL", after.get("definitionSource"));
        assertEquals("2", after.get("configVersion"), "使用局配置版本，不用主题发布版本或 state CAS version");
        assertEquals(2, rows(after).size());
        assertEquals("力量", rows(after).get(0).get("label"));
        assertEquals(7, rows(after).get(0).get("value"));
        assertEquals(20, rows(after).get(0).get("max"));
        assertEquals(10, rows(after).get(1).get("value"), "数值按冻结定义夹取，不按作者最新范围");
        assertEquals(10, rows(after).get(1).get("max"));
        assertNull(rows(after).get(0).get("persistScope"), "生产者未冻结的元数据不能冒充已知");
        assertNull(rows(after).get(0).get("displayType"));
        assertTrue(((List<?>) after.get("definitionMissingFields")).contains("stateKeys.visible"));
        verifyNoInteractions(keys);
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
        assertEquals("TEMPLATE_LIVE", card.get("definitionSource"));
        assertNull(card.get("configVersion"));
        assertTrue(rows(card).isEmpty());
        verify(topics, org.mockito.Mockito.times(2)).selectCmsTopicById(TOPIC);
        verify(keys).selectByTopicId(TOPIC);
        verify(characters).selectByMemberAndTopic(MEMBER, TOPIC);
        verify(sessions).selectHistoryPage(MEMBER, TOPIC, null, 1);
        verifyNoMoreInteractions(keys, characters, sessions, topics);
    }

    @Test
    void partialSnapshotNeverPublishesUnknownVisibilityOrRangeAndNeverReadsAuthorRegistry() {
        TopicRouteSession partial = frozenSession(RULES);
        partial.setConfigSnapshotJson("{\"journeyRules\":{\"schemaVersion\":1,\"stateEnabled\":true,\"attributes\":["
                + "{\"key\":\"counter.str\",\"label\":\"未知可见性\",\"initial\":3,\"min\":0,\"max\":20},"
                + "{\"key\":\"counter.focus\",\"label\":\"未知范围\",\"initial\":5,\"visible\":true},"
                + "{\"key\":\"counter.secret\",\"label\":\"隐藏\",\"initial\":1,\"min\":0,\"max\":10,\"visible\":false}]}}");
        when(sessions.selectHistoryPage(eq(MEMBER), eq(TOPIC), isNull(), eq(1)))
                .thenReturn(Collections.singletonList(partial));

        Map<String, Object> card = service.card(MEMBER, TOPIC);

        assertEquals("SESSION_SNAPSHOT_PARTIAL", card.get("definitionSource"));
        assertTrue(rows(card).isEmpty(), "缺字段不能由 normalize 默认值变成公开定义");
        assertTrue(((List<?>) card.get("definitionMissingFields")).contains("journeyRules.attributes.visible"));
        assertTrue(((List<?>) card.get("definitionMissingFields")).contains("journeyRules.attributes.min"));
        verifyCardReadsOnly(false);
    }

    @Test
    void malformedExistingSnapshotDoesNotFallBackToLatestAuthorDefinitions() {
        TopicRouteSession invalid = frozenSession(RULES);
        invalid.setConfigVersion(null);
        invalid.setConfigSnapshotJson("{\"journeyRules\":{\"attributes\":null}}");
        when(sessions.selectHistoryPage(eq(MEMBER), eq(TOPIC), isNull(), eq(1)))
                .thenReturn(Collections.singletonList(invalid));

        Map<String, Object> card = service.card(MEMBER, TOPIC);

        assertEquals("SESSION_SNAPSHOT_PARTIAL", card.get("definitionSource"));
        assertNull(card.get("configVersion"));
        assertTrue(rows(card).isEmpty());
        assertTrue(((List<?>) card.get("definitionMissingFields")).contains("journeyRules.attributes"));
        verifyCardReadsOnly(false);
    }

    @Test
    void frozenEmptyAttributesDoNotPickUpNewLiveDefinitions() {
        TopicRouteSession empty = frozenSession("{\"schemaVersion\":1,\"stateEnabled\":true,\"attributes\":[]}");
        when(sessions.selectHistoryPage(eq(MEMBER), eq(TOPIC), isNull(), eq(1)))
                .thenReturn(Collections.singletonList(empty));

        Map<String, Object> card = service.card(MEMBER, TOPIC);

        assertEquals("SESSION_SNAPSHOT_PARTIAL", card.get("definitionSource"));
        assertTrue(rows(card).isEmpty(), "已冻结空数组与旧局没有属性快照不同");
        verifyCardReadsOnly(false);
    }

    @Test
    void topicAndConfigVersionRemainExactBeyondJavascriptSafeIntegers() {
        CmsTopic topic = new CmsTopic();
        topic.setId(Long.MAX_VALUE);
        topic.setMemberId(OWNER);
        topic.setPublishedConfigVersion(Long.MAX_VALUE);
        when(topics.selectCmsTopicById(Long.MAX_VALUE)).thenReturn(topic);
        TopicRouteSession running = frozenSession(RULES);
        running.setConfigVersion(Long.MAX_VALUE);
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
    void invalidFrozenIntegerRangesNeverReachTheCard() {
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

            assertEquals("SESSION_SNAPSHOT_PARTIAL", card.get("definitionSource"));
            assertTrue(rows(card).isEmpty(), "原始整数溢出或 min>max 时不能生成角色卡属性");
        }
        verifyNoInteractions(keys);
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
