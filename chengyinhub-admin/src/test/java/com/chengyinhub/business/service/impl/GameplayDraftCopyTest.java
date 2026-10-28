package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.support.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GameplayDraftCopyTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void firstSaveRegeneratesRealQuestsWithoutChangingActOrSurvivalIdentities() throws Exception {
        // 原生表单夹具经过与副本一致的节点清理；没有模板工具也必须重新生成任务。
        ObjectNode copy = copiedForm();
        CmsTopic topic = topic(copy);
        TopicRouteRuntimeServiceImpl service = service();
        JsonNode compiled = json.readTree(service.compileTemplateRules(topic));
        JsonNode game = compiled.path("gameplayRuntime");
        assertEquals(Arrays.asList("act_one", "second"), game.path("acts").findValuesAsText("actKey"));
        assertTrue(game.path("acts").get(0).path("hostUnlock").asBoolean());
        assertEquals(copy.path("gameplayRuntime").path("acts").get(0).path("completionRule"), game.path("acts").get(0).path("completionRule"));
        assertFalse(game.path("acts").get(1).path("hostUnlock").asBoolean());
        assertEquals("ALL", game.path("acts").get(1).path("completionRule").path("type").asText());
        assertEquals("node_301", game.path("quests").get(0).path("questKey").asText());
        assertEquals(301, game.path("quests").get(0).path("nodeId").asInt());
        assertEquals("act_one", game.path("quests").get(1).path("actKey").asText());
        assertEquals("second", game.path("quests").get(2).path("actKey").asText());
        assertEquals(copy.path("gameplayRuntime").path("survival"), game.path("survival"));
        assertEquals(copy.path("gameplayRuntime").path("facilities"), game.path("facilities"));
        for (JsonNode quest : game.path("quests")) {
            assertFalse(quest.has("approvedFallbackNodeIds")); assertFalse(quest.has("facilityKey")); assertFalse(quest.has("guideKey"));
        }
        ThemeRuntimeConfig.parse(compiled.toString());
        topic.setThemeRuntimeConfigJson(compiled.toString());
        assertNotNull(freezePublication(service, topic));
        assertEquals(compiled, json.readTree(service.compileTemplateRules(topic)), "再次保存不重复生成或改写幕");
    }

    @Test void unmatchedEmptyActStaysInDraftButCannotFreeze() throws Exception {
        ObjectNode copy = copiedForm();
        copy.with("gameplayRuntime").withArray("acts").addObject().put("actKey", "unmatched").putArray("questKeys");
        ((ObjectNode)copy.path("gameplayRuntime").path("acts").get(2)).putObject("completionRule").put("type", "ALL");
        CmsTopic topic = topic(copy); TopicRouteRuntimeServiceImpl service = service();
        JsonNode compiled = json.readTree(service.compileTemplateRules(topic));
        assertEquals("unmatched", compiled.path("gameplayRuntime").path("acts").get(2).path("actKey").asText());
        ThemeRuntimeConfig.parseDraft(compiled.toString());
        topic.setThemeRuntimeConfigJson(compiled.toString());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> freezePublication(service, topic)).getMessage().contains("不能为空"));
    }

    @Test void nativeThreeThemeCopiesSaveWithRealNodeMappingsAndRequireChallengeReselectionForPublish() throws Exception {
        Path fixture = Paths.get("../chengyinhub-xcx/tests/unit/fixtures/gameplay-theme-copy.json");
        if (!Files.exists(fixture)) fixture = Paths.get("chengyinhub-xcx/tests/unit/fixtures/gameplay-theme-copy.json");
        Path originals = Paths.get("../chengyinhub-xcx/tests/unit/fixtures/gameplay-reskin-roundtrip.json");
        if (!Files.exists(originals)) originals = Paths.get("chengyinhub-xcx/tests/unit/fixtures/gameplay-reskin-roundtrip.json");
        JsonNode originalThemes = json.readTree(Files.readAllBytes(originals)).path("themes");
        int skinIndex = 0;
        for (JsonNode form : json.readTree(Files.readAllBytes(fixture))) {
            Map<String, Long> clients = new LinkedHashMap<>(); List<CmsTopicNode> nodes = new ArrayList<>();
            List<CmsTopicChapter> chapters = new ArrayList<>(); List<CmsMemberTemplate> templates = new ArrayList<>();
            for (JsonNode row : form.path("chapters")) {
                CmsTopicChapter chapter = new CmsTopicChapter(); chapter.setId(10001L + chapters.size()); chapter.setTopicId(77L); chapter.setSortId((long)chapters.size()); chapter.setName(row.path("name").asText()); chapters.add(chapter);
                for (JsonNode source : row.path("nodes")) {
                    CmsTopicNode node = new CmsTopicNode(); node.setId(20001L + nodes.size()); node.setSortId((long)nodes.size()); node.setTopicId(77L); node.setChapterId(chapter.getId());
                    node.setTemplateId(source.path("templateId").asLong()); node.setName(source.path("name").asText()); node.setStatus(1L); node.setMerchantMemberId(30L); nodes.add(node);
                    clients.put(source.path("clientNodeKey").asText(), node.getId());
                }
            }
            for (JsonNode row : form.path("templates")) {
                CmsMemberTemplate template = new CmsMemberTemplate(); template.setId(row.path("id").asLong()); template.setValidationMethod(row.path("validationMethod").asInt()); template.setAdvancedConfigJson(row.path("advancedConfigJson").asText()); templates.add(template);
            }
            CmsTopic topic = new CmsTopic(); topic.setId(77L); topic.setRouteMode("LINEAR"); topic.setJourneyRulesJson(form.path("journeyRules").toString());
            topic.setThemeRuntimeConfigJson(ThemeRuntimeConfig.remapForNodeSave(form.path("runtime").toString(), Collections.emptyMap(), Collections.emptyMap(), clients));
            TopicRouteRuntimeServiceImpl service = service(nodes, chapters, templates);
            ThemeStateKeyMapper stateMapper = mock(ThemeStateKeyMapper.class); List<ThemeStateKey> stateKeys = new ArrayList<>();
            // 与 GameplayReskinFixture 的现场线索注册相同；这些状态不属于本次结构复制。
            for (String key : Arrays.asList("rescue", "load_ok", "end01", "end02", "end03", "end04", "ev01", "ev02", "ev03", "ev04", "ev05")) { ThemeStateKey state = new ThemeStateKey(); state.setTopicId(77L); state.setStateKey("clue." + key); state.setKeyType("flag"); state.setScope("run"); state.setInitialValue(0); stateKeys.add(state); }
            when(stateMapper.selectByTopicId(77L)).thenReturn(stateKeys); ReflectionTestUtils.setField(service, "themeStateKeyMapper", stateMapper);
            JsonNode compiled = json.readTree(service.compileTemplateRules(topic));
            assertEquals(7, compiled.path("gameplayRuntime").path("acts").size(), form.path("name").asText());
            for (int index = 0; index < 7; index++) {
                JsonNode original = form.path("runtime").path("gameplayRuntime").path("acts").get(index), act = compiled.path("gameplayRuntime").path("acts").get(index);
                assertEquals(original.path("actKey"), act.path("actKey")); assertEquals(original.path("hostUnlock"), act.path("hostUnlock")); assertEquals(original.path("completionRule"), act.path("completionRule"));
            }
            assertEquals(form.path("runtime").path("gameplayRuntime").path("survival"), compiled.path("gameplayRuntime").path("survival"));
            long operationId = clients.get("copy_" + (nodes.size() - 1));
            assertTrue(compiled.path("scenes").get(0).path("nodeConfigs").has(String.valueOf(operationId)));
            assertEquals(form.path("runtime").path("scenes").get(0).path("nodeConfigs").elements().next().path("interactionTools").path("plans").size(), compiled.path("gameplayRuntime").path("plans").size());
            assertEquals(operationId, compiled.path("gameplayRuntime").path("plans").get(0).path("operationNodeId").asLong());
            ThemeRuntimeConfig.parseDraft(compiled.toString()); topic.setThemeRuntimeConfigJson(compiled.toString());
            assertEquals(compiled, json.readTree(service.compileTemplateRules(topic)), "首次保存后再次保存不漂移");
            String error = assertThrows(IllegalArgumentException.class, () -> freezePublication(service, topic)).getMessage();
            assertTrue(error.contains("完成证明"), error);
            assertEquals(json.valueToTree(stateKeys), json.valueToTree(stateMapper.selectByTopicIdForUpdate(77L)));
            ObjectNode ready = ((ObjectNode)compiled).deepCopy();
            ObjectNode challenge = (ObjectNode)json.readTree(originalThemes.get(skinIndex++).path("detail").path("themeRuntimeConfig").asText()).path("gameplayRuntime").path("survival").path("zeroChallenge").deepCopy();
            Set<Long> challengeIds = new HashSet<>(Arrays.asList(clients.get("copy_1"), clients.get("copy_2")));
            challenge.put("challengeNodeId", clients.get("copy_1")); challenge.putArray("alternativeNodeIds").add(clients.get("copy_2"));
            ObjectNode game = ready.with("gameplayRuntime"); game.with("survival").put("zeroHpPolicy", "CHALLENGE").set("zeroChallenge", challenge);
            Set<String> challengeKeys = new HashSet<>();
            Iterator<JsonNode> quests = game.withArray("quests").iterator();
            while (quests.hasNext()) { JsonNode quest = quests.next(); if (challengeIds.contains(quest.path("nodeId").asLong())) { challengeKeys.add(quest.path("questKey").asText()); quests.remove(); } }
            for (JsonNode act : game.path("acts")) { Iterator<JsonNode> members = act.path("questKeys").iterator(); while (members.hasNext()) if (challengeKeys.contains(members.next().asText())) members.remove(); }
            topic.setThemeRuntimeConfigJson(ready.toString());
            assertNotNull(freezePublication(service, topic), "重选本主题真实挑战及客观题备选后可发布");
            assertEquals(json.valueToTree(stateKeys), json.valueToTree(stateMapper.selectByTopicId(77L)));
            assertEquals(json.valueToTree(stateKeys), json.valueToTree(stateMapper.selectByTopicIdForUpdate(77L)));
        }
    }

    @Test void clientSceneKeysAreOnlyAcceptedForCreatedNodesAndNeverByPublishParser() throws Exception {
        ObjectNode config = json.createObjectNode().put("schemaVersion", 1).put("currentScene", "copy");
        ObjectNode scene = config.putArray("scenes").addObject().put("id", "copy").put("name", "副本场景");
        scene.putObject("nodeConfigs").putObject("client:station_1").put("schemaVersion", 1).putObject("scan").put("enabled", true).put("kind", "TEXT").put("reply", "确认现场");
        assertNotNull(ThemeRuntimeConfig.parseForNodeSave(config.toString()));
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parseDraft(config.toString()));
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parse(config.toString()));
        assertFalse(json.readTree(ThemeRuntimeConfig.withoutClientBindings(config.toString())).path("scenes").get(0).path("nodeConfigs").has("client:station_1"));
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.remapForNodeSave(config.toString(), Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap()));
        String remapped = ThemeRuntimeConfig.remapForNodeSave(config.toString(), Collections.emptyMap(), Collections.emptyMap(), Collections.singletonMap("station_1", 301L));
        assertTrue(ThemeRuntimeConfig.parse(remapped).path("scenes").get(0).path("nodeConfigs").has("301"));
        ((ObjectNode)scene.path("nodeConfigs")).set("client:another", scene.path("nodeConfigs").path("client:station_1").deepCopy());
        Map<String, Long> duplicate = new HashMap<>(); duplicate.put("station_1", 301L); duplicate.put("another", 301L);
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.remapForNodeSave(config.toString(), Collections.emptyMap(), Collections.emptyMap(), duplicate));
        ((ObjectNode)scene.path("nodeConfigs")).putObject("client: bad key ").put("schemaVersion", 1).putObject("scan").put("enabled", true);
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parseForNodeSave(config.toString()));
    }

    private ObjectNode copiedForm() throws Exception {
        Path fixture = Paths.get("../chengyinhub-xcx/tests/unit/fixtures/author-survival-config.json");
        if (!Files.exists(fixture)) fixture = Paths.get("chengyinhub-xcx/tests/unit/fixtures/author-survival-config.json");
        ObjectNode copy = (ObjectNode)json.readTree(Files.readAllBytes(fixture));
        ObjectNode game = copy.with("gameplayRuntime"); game.putArray("quests");
        ObjectNode first = (ObjectNode)game.withArray("acts").get(0);
        first.putArray("questKeys"); first.put("hostUnlock", true); first.putObject("completionRule").put("type", "ANY_N").put("quantity", 2);
        ObjectNode second = game.withArray("acts").addObject().put("actKey", "second").put("hostUnlock", false);
        second.putArray("questKeys"); second.putObject("completionRule").put("type", "ALL");
        ObjectNode facility = (ObjectNode)game.withArray("facilities").get(0);
        facility.remove("operationNodeId"); ArrayNode when = facility.putArray("when");
        when.addObject().put("op", "ITEM_HELD").put("itemId", "supply").put("quantity", 1);
        return copy;
    }

    private CmsTopic topic(ObjectNode config) throws Exception {
        CmsTopic topic = new CmsTopic(); topic.setId(77L); topic.setRouteMode("LINEAR"); topic.setThemeRuntimeConfigJson(config.toString());
        Path fixture = Paths.get("../chengyinhub-xcx/tests/unit/fixtures/gameplay-structure-form.json");
        if (!Files.exists(fixture)) fixture = Paths.get("chengyinhub-xcx/tests/unit/fixtures/gameplay-structure-form.json");
        topic.setJourneyRulesJson(json.readTree(Files.readAllBytes(fixture)).path("journeyRules").toString()); return topic;
    }

    private TopicRouteRuntimeServiceImpl service() {
        List<CmsTopicChapter> chapters = new ArrayList<>(); List<CmsTopicNode> nodes = new ArrayList<>();
        for (long id : new long[]{200, 201, 202}) { CmsTopicChapter c = new CmsTopicChapter(); c.setId(id); c.setSortId(id); c.setTopicId(77L); chapters.add(c); }
        for (long id : new long[]{301, 302, 303}) {
            CmsTopicNode n = new CmsTopicNode(); n.setId(id); n.setSortId(id); n.setChapterId(id == 303 ? 202L : 201L); n.setTopicId(77L); n.setStatus(1L); n.setTemplateId(701L); n.setMerchantMemberId(30L); nodes.add(n);
        }
        CmsMemberTemplate scan = new CmsMemberTemplate(); scan.setId(701L); scan.setValidationMethod(4); scan.setAdvancedConfigJson("{\"scan\":{\"enabled\":true}}");
        return service(nodes, chapters, Collections.singletonList(scan));
    }
    private TopicRouteRuntimeServiceImpl service(List<CmsTopicNode> nodes, List<CmsTopicChapter> chapters, List<CmsMemberTemplate> sourceTemplates) {
        CmsTopicNodeMapper mapper = mock(CmsTopicNodeMapper.class); when(mapper.selectCmsTopicNodeList(any())).thenReturn(nodes);
        when(mapper.selectByTopicForUpdate(77L)).thenReturn(nodes);
        CmsMemberTemplateMapper templates = mock(CmsMemberTemplateMapper.class); when(templates.selectCmsMemberTemplateByIds(any())).thenReturn(sourceTemplates);
        when(templates.selectCmsMemberTemplateByIdsForUpdate(any())).thenAnswer(call -> {
            List<Long> ids=call.getArgument(0);
            return sourceTemplates.stream().filter(row -> ids.contains(row.getId())).toList();
        });
        CmsTopicChapterMapper chapterMapper = mock(CmsTopicChapterMapper.class); when(chapterMapper.selectCmsTopicChapterList(any())).thenReturn(chapters);
        when(chapterMapper.selectByTopicForUpdate(77L)).thenReturn(chapters);
        TopicRouteRuntimeServiceImpl service = new TopicRouteRuntimeServiceImpl(mock(TopicRouteSessionMapper.class), mock(CmsTopicMapper.class), mapper,
                templates, mock(PlayerNodeProgressMapper.class), null, null, null, mock(IShopDayProofService.class), () -> 1L, () -> 10, Clock.systemUTC(), RouteReachabilityProvider.UNKNOWN);
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper); return service;
    }

    /** Real Spring JDBC transaction lifecycle; JDBC/Mapper doubles remain explicit unit boundaries. */
    private String freezePublication(TopicRouteRuntimeServiceImpl service, CmsTopic topic) throws Exception {
        CmsTopicMapper topics=(CmsTopicMapper)ReflectionTestUtils.getField(service,"topicMapper");
        when(topics.selectCmsTopicByIdForUpdate(topic.getId())).thenReturn(topic);
        ThemeStateKeyMapper keys=(ThemeStateKeyMapper)ReflectionTestUtils.getField(service,"themeStateKeyMapper");
        if(keys==null) {
            keys=mock(ThemeStateKeyMapper.class);
            when(keys.selectByTopicId(topic.getId())).thenReturn(ThemeStateKeyDeclarationSnapshot.includingRuleAttributes(
                    topic.getId(),Collections.emptyList(),com.chengyinhub.business.domain.JourneyRules.parse(topic.getJourneyRulesJson())));
            ReflectionTestUtils.setField(service,"themeStateKeyMapper",keys);
        }
        ThemeStateKeyMapper originalKeys=keys;
        doReturn(topic.getId()).when(keys).lockTopicForStateKeyWrite(topic.getId());
        // Restubbing must not execute the previous bridge and bind this answer to its nested plain read.
        doAnswer(call -> originalKeys.selectByTopicId(call.getArgument(0)))
                .when(keys).selectByTopicIdForUpdate(topic.getId());
        javax.sql.DataSource data=mock(javax.sql.DataSource.class);
        java.sql.Connection connection=mock(java.sql.Connection.class);
        when(data.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);
        var transaction=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(data));
        try { return transaction.execute(status -> service.freezePublication(topic,null)); }
        finally { verify(connection).close(); }
    }
}
