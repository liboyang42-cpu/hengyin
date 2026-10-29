package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.route.NodeOutcome;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.support.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class GameplayTemplateSlotRuntimeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private TopicRouteRuntimeServiceImpl runtime;
    private final Map<Long, CmsTopic> topics = new LinkedHashMap<>();
    private final Map<Long, CmsTopicNode> nodes = new LinkedHashMap<>();
    private final Map<Long, TopicRouteSession> sessions = new HashMap<>();
    private CmsMemberTemplate template;
    private final PlayerNodeProgressMapper progress=mock(PlayerNodeProgressMapper.class);

    public static ObjectNode templateConfig() {
        ObjectNode config = JSON.createObjectNode().put("schemaVersion", 1);
        ObjectNode rule = config.putObject("interactionRules").put("limit", 2);
        rule.putArray("resourceSlots").addObject().put("key", "energy").put("kind", "VALUE").put("label", "值域").put("required", true);
        rule.withArray("resourceSlots").addObject().put("key", "supply").put("kind", "ITEM").put("label", "物资").put("required", true);
        rule.putArray("statCosts").addObject().put("statKey", "energy").put("amount", 2);
        rule.putArray("statChanges").addObject().put("statKey", "energy").put("delta", 1);
        rule.putArray("resourceCosts").addObject().put("itemId", "supply").put("quantity", 1);
        return config;
    }
    public static String journeyRules() {
        return "{\"schemaVersion\":1,\"stateEnabled\":true,\"hp\":{\"enabled\":true,\"init\":6,\"max\":10},\"attributes\":[{\"key\":\"counter.x\",\"label\":\"数值\",\"initial\":5,\"min\":0,\"max\":10}]}";
    }
    public static ObjectNode definition(long nodeId, String value, String item) {
        ObjectNode theme = JSON.createObjectNode().put("schemaVersion", 1).put("capacity", 8);
        for (String id : Arrays.asList("a", "b")) theme.withArray("items").addObject().put("id", id).put("kind", "ITEM").put("name", id).put("image", "media-asset:1");
        ObjectNode game = theme.putObject("gameplayRuntime").put("runtimeScope", "PLAYER");
        game.putArray("initialGrants").addObject().put("itemId", item).put("quantity", 2);
        ObjectNode binding = theme.putObject("templateBindings").putObject(String.valueOf(nodeId)).put("templateId", 501L)
                .put("slotDigest", GameplayTemplateRules.slotDigest(templateConfig().path("interactionRules").path("resourceSlots")));
        binding.putObject("values").put("energy", value); binding.putObject("items").put("supply", item);
        return theme;
    }

    @BeforeEach void setup() {
        TopicRouteSessionMapper routes = mock(TopicRouteSessionMapper.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        CmsMemberTemplateMapper templates = mock(CmsMemberTemplateMapper.class);
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        runtime = new TopicRouteRuntimeServiceImpl(routes, topicMapper, nodeMapper, templates, progress, null,
                registrations, null, mock(IShopDayProofService.class), () -> 1L, () -> 10, Clock.systemUTC(), RouteReachabilityProvider.UNKNOWN);
        template = new CmsMemberTemplate(); template.setId(501L); template.setValidationMethod(4); template.setAdvancedConfigJson(templateConfig().toString());
        when(templates.selectCmsMemberTemplateByIds(any())).thenAnswer(call -> Collections.singletonList(template));
        when(topicMapper.selectCmsTopicById(anyLong())).thenAnswer(call -> topics.get(call.getArgument(0)));
        when(nodeMapper.selectCmsTopicNodeList(any())).thenAnswer(call -> {
            List<CmsTopicNode> result = new ArrayList<>(); CmsTopicNode query = call.getArgument(0);
            for (CmsTopicNode node : nodes.values()) if (node.getTopicId().equals(query.getTopicId())) result.add(node);
            return result;
        });
        when(nodeMapper.selectCmsTopicNodeById(anyLong())).thenAnswer(call -> nodes.get(call.getArgument(0)));
        CurrentRegistrationStubs.currentFromProbe(registrations);
        when(routes.selectByOwner(eq(0L), anyLong(), eq(7L), eq("default"))).thenAnswer(call -> sessions.get(call.getArgument(1)));
        when(routes.selectCurrentByOwner(eq(0L), anyLong(), eq(7L))).thenAnswer(call -> sessions.get(call.getArgument(1)));
        when(routes.selectAccessibleById(anyLong(), eq(7L))).thenAnswer(call -> sessions.values().stream().filter(s -> s.getId().equals(call.getArgument(0))).findFirst().orElse(null));
        when(routes.insertSession(any())).thenAnswer(call -> { TopicRouteSession session = call.getArgument(0); session.setId(session.getTopicId() + 100); sessions.put(session.getTopicId(), session); return 1; });
        when(routes.updateStateCas(any())).thenReturn(1); when(routes.insertAction(any())).thenReturn(1); when(routes.updateActionResponse(any(), any())).thenReturn(1);
        topic(10L, 11L, "sys.hp", "a"); topic(20L, 21L, "counter.x", "b");
    }
    private CmsTopic topic(long id, long nodeId, String value, String item) {
        CmsTopic topic = new CmsTopic(); topic.setId(id); topic.setRouteMode("LINEAR"); topic.setPublishedConfigVersion(1L);
        topic.setJourneyRulesJson(journeyRules()); topic.setThemeRuntimeConfigJson(definition(nodeId, value, item).toString()); topics.put(id, topic);
        CmsTopicNode node = new CmsTopicNode(); node.setId(nodeId); node.setTopicId(id); node.setStatus(1L); node.setTemplateId(501L); node.setMerchantMemberId(30L); nodes.put(nodeId, node);
        return topic;
    }
    private JsonNode publish(CmsTopic topic) throws Exception {
        topic.setThemeRuntimeConfigJson(runtime.compileTemplateRules(topic));
        JsonNode frozen = JSON.readTree(runtime.freezePublication(topic, null)); topic.setPublishedRouteGraphJson(frozen.toString());
        return frozen;
    }
    private void run(CmsTopic topic, long nodeId, int hp, int value, String item) throws Exception {
        runtime.state(7L, topic.getId(), 0L);
        TopicRouteSession session = sessions.get(topic.getId());
        runtime.recordPlayerArrival(7L, topic.getId(), 0L, nodeId, "scan-"+nodeId+"-"+session.getVersion(),session.getVersion());
        String quest = "node_" + nodeId;
        runtime.gameplayAction(7L, session.getId(), session.getVersion(), "start", "QUEST_START", quest, null);
        runtime.gameplayAction(7L, session.getId(), session.getVersion(), "resolve", "QUEST_RESOLVE", quest, null);
        JsonNode state = JSON.readTree(session.getStateJson());
        assertEquals(hp, state.path("variables").path("sys.hp").asInt()); assertEquals(value, state.path("variables").path("counter.x").asInt());
        assertEquals(1, state.path("themeRuntime").path("inventory").path(item).asInt());
        assertEquals(0, state.path("themeRuntime").path("inventory").path("a".equals(item) ? "b" : "a").asInt());
    }
    @Test void oneTemplatePublishesFreezesAndSettlesDifferentResourcesInTwoThemes() throws Exception {
        String original = template.getAdvancedConfigJson();
        for (CmsTopic topic : topics.values()) {
            JsonNode frozen = publish(topic); long nodeId = topic.getId() + 1;
            assertFalse(frozen.toString().contains("resourceSlots")); assertFalse(frozen.toString().contains("templateBindings"));
            assertEquals(frozen.path("themeConfig"), JSON.readTree(runtime.freezePublishedJourney(topic)).path("themeConfig"));
            assertTrue(JSON.readTree(topic.getThemeRuntimeConfigJson()).has("templateBindings"));
            assertFalse(frozen.path("nodeConfigs").path(String.valueOf(nodeId)).path("interactionRules").has("resourceSlots"));
        }
        run(topics.get(10L), 11L, 5, 5, "a"); run(topics.get(20L), 21L, 6, 4, "b");
        assertEquals(original, template.getAdvancedConfigJson());
    }
    @Test void originalEffectsOnlyBindWithoutIntroducingGenericFeesOrQuestBridge() throws Exception {
        ObjectNode config=JSON.createObjectNode().put("schemaVersion",1);
        config.putObject("interactionRules").putArray("resourceSlots").addObject()
                .put("key","energy").put("kind","VALUE").put("label","值域").put("required",true);
        config.putArray("effects").addObject().put("var","energy").put("op","INC").put("value",2);
        template.setAdvancedConfigJson(new AdvancedGameConfigValidator().validateAndNormalize(config.toString()));
        for(CmsTopic topic:topics.values()) {
            long nodeId=topic.getId()+1;
            ObjectNode theme=JSON.createObjectNode().put("schemaVersion",1).put("capacity",8);
            ObjectNode binding=theme.putObject("templateBindings").putObject(String.valueOf(nodeId)).put("templateId",501L)
                    .put("slotDigest",GameplayTemplateRules.slotDigest(config.path("interactionRules").path("resourceSlots")));
            binding.putObject("values").put("energy",topic.getId()==10L?"sys.hp":"counter.x");binding.putObject("items");
            topic.setThemeRuntimeConfigJson(theme.toString());JsonNode frozen=publish(topic);
            assertFalse(frozen.path("themeConfig").has("gameplayRuntime"));assertFalse(frozen.toString().contains("resourceSlots"));
            runtime.state(7L,topic.getId(),0L);
            PlayerNodeProgress receipt=new PlayerNodeProgress();receipt.setNodeId(nodeId);receipt.setStatus(1);
            when(progress.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(receipt));
            runtime.advance(7L,topic.getId(),0L,sessions.get(topic.getId()).getVersion(),"SCAN",new NodeOutcome(nodeId,"COMPLETED","player-node-progress:"+nodeId));
            JsonNode state=JSON.readTree(sessions.get(topic.getId()).getStateJson());
            assertEquals(topic.getId()==10L?8:7,state.path("variables").path(topic.getId()==10L?"sys.hp":"counter.x").asInt());
            when(progress.selectPlayerNodeProgressList(any())).thenReturn(Collections.emptyList());
        }
    }
    @Test void originalRootRecoveryBindsPublishesFreezesAndRunsAcrossHpAndCounterThemes() throws Exception {
        ObjectNode config=templateConfig();
        config.putArray("effects").addObject().put("var","energy").put("op","INC").put("value",2);
        template.setAdvancedConfigJson(new AdvancedGameConfigValidator().validateAndNormalize(config.toString()));
        for(CmsTopic topic:topics.values()) {
            JsonNode frozen=publish(topic);String key=String.valueOf(topic.getId()+1);
            String target=topic.getId()==10L?"sys.hp":"counter.x";
            assertEquals(target,frozen.path("nodeJourney").path(key).path("effects").path(0).path("var").asText());
            assertEquals(frozen.path("nodeJourney"),JSON.readTree(runtime.freezePublishedJourney(topic)).path("nodeJourney"));
        }
        run(topics.get(10L),11L,7,5,"a");run(topics.get(20L),21L,6,6,"b");
    }
    @Test void originalFloorRecoveryUsesBoundLimitsAndRejectsAboveMaxBeforePublishing() throws Exception {
        ObjectNode config=templateConfig();config.with("interactionRules").remove("statChanges");
        config.putArray("effects").addObject().put("var","energy").put("op","FLOOR").put("value",8);
        template.setAdvancedConfigJson(new AdvancedGameConfigValidator().validateAndNormalize(config.toString()));
        for(CmsTopic topic:topics.values())publish(topic);
        run(topics.get(10L),11L,8,5,"a");run(topics.get(20L),21L,6,8,"b");
        ((ObjectNode)config.path("effects").path(0)).put("value",11);
        template.setAdvancedConfigJson(new AdvancedGameConfigValidator().validateAndNormalize(config.toString()));
        String invalid=assertThrows(IllegalArgumentException.class,()->runtime.compileTemplateRules(topics.get(10L))).getMessage();
        assertTrue(invalid.contains("11") && invalid.contains("energy") && invalid.contains("FLOOR"));
    }
    @Test void originalFullRecoveryIsFreeByDefaultButSharedPolicyMayConsumeOnce() throws Exception {
        ObjectNode config=templateConfig();config.with("interactionRules").remove("statChanges");
        config.putArray("effects").addObject().put("var","energy").put("op","FLOOR").put("value",8);
        template.setAdvancedConfigJson(new AdvancedGameConfigValidator().validateAndNormalize(config.toString()));
        for(CmsTopic topic:topics.values()) {
            boolean consume=topic.getId()==20L;
            ObjectNode definition=(ObjectNode)JSON.readTree(topic.getThemeRuntimeConfigJson());
            definition.with("gameplayRuntime").putObject("survival").put("zeroHpPolicy","BLOCK_HP_COST").put("actionLimitPolicy","BLOCK_OVERFLOW").put("consumeOnNoEffect",consume);
            topic.setThemeRuntimeConfigJson(definition.toString());publish(topic);
            long nodeId=topic.getId()+1;runtime.state(7L,topic.getId(),0L);TopicRouteSession session=sessions.get(topic.getId());
            runtime.recordPlayerArrival(7L,topic.getId(),0L,nodeId,"scan-"+nodeId+"-"+session.getVersion(),session.getVersion());
            runtime.gameplayAction(7L,session.getId(),session.getVersion(),"start","QUEST_START","node_"+nodeId,null);
            ObjectNode full=(ObjectNode)JSON.readTree(session.getStateJson());full.with("variables").put("sys.hp",10).put("counter.x",10);session.setStateJson(full.toString());
            runtime.gameplayAction(7L,session.getId(),session.getVersion(),"resolve","QUEST_RESOLVE","node_"+nodeId,null);
            JsonNode settled=JSON.readTree(session.getStateJson());String item=consume?"b":"a";
            assertEquals(consume?1:2,settled.path("themeRuntime").path("inventory").path(item).asInt());
            assertEquals(consume?1:0,settled.path("themeRuntime").path("cooperation").path("facilities").path("tpl_node_"+nodeId).path("count").asInt());
        }
    }
    private ObjectNode voteDeclaration() {
        ObjectNode config = JSON.createObjectNode().put("schemaVersion", 1);
        config.putObject("interactionRules").putArray("resourceSlots").addObject()
                .put("key", "supply").put("kind", "ITEM").put("label", "投入物资").put("required", true);
        config.putObject("interactionTools").put("votePolicy", "MAJORITY").putArray("plans").addObject()
                .put("planKey", "continue").put("title", "继续前进").putArray("materialCost").addObject()
                .put("itemId", "supply").put("quantity", 2);
        return config;
    }
    private void saveIndependentVote(ObjectNode declaration) {
        com.chengyinhub.business.service.ICmsMemberTemplateService store = mock(com.chengyinhub.business.service.ICmsMemberTemplateService.class);
        TemplatePublishServiceImpl publisher = new TemplatePublishServiceImpl();
        ReflectionTestUtils.setField(publisher, "mediaPublicationGuard", com.chengyinhub.business.service.support.MediaPublicationTestFixtures.template(7L, 1L));
        org.springframework.test.util.ReflectionTestUtils.setField(publisher, "memberTemplateService", store);
        org.springframework.test.util.ReflectionTestUtils.setField(publisher, "capabilityGuard", mock(com.chengyinhub.business.service.ICapabilityGuard.class));
        when(store.insertCmsMemberTemplate(any())).thenAnswer(call -> {
            template = call.getArgument(0); template.setId(501L); return 1;
        });
        when(store.selectCmsMemberTemplateById(501L)).thenAnswer(call -> template);
        com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest request = new com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest();
        request.setTitle("独立投票模板"); request.setValidationMethod(4); request.setAdvancedConfigJson(declaration.toString());
        assertEquals(501L, publisher.saveOrUpdateDraft(request, 7L, "creator"));
        assertEquals(declaration, readConfig(store.selectCmsMemberTemplateById(501L).getAdvancedConfigJson()));
    }
    private JsonNode readConfig(String raw) {
        try { return JSON.readTree(raw); }
        catch (java.io.IOException invalid) { throw new AssertionError(invalid); }
    }
    private ObjectNode voteTheme(CmsTopic topic) throws Exception {
        ObjectNode theme = (ObjectNode) JSON.readTree(topic.getThemeRuntimeConfigJson());
        theme.with("gameplayRuntime").put("runtimeScope", "TEAM").put("worldMode", "WORLD_SHARED");
        ObjectNode binding = theme.with("templateBindings").with(String.valueOf(topic.getId() + 1));
        binding.put("slotDigest", GameplayTemplateRules.slotDigest(voteDeclaration().path("interactionRules").path("resourceSlots")));
        binding.putObject("values");
        return theme;
    }
    @Test void independentVoteSaveReadbackAdoptBindPublishFreezeKeepsDeclarationAndResolvesMaterials() throws Exception {
        ObjectNode declaration = voteDeclaration(); saveIndependentVote(declaration);
        CmsTopic topic = topics.get(10L); ObjectNode theme = voteTheme(topic); topic.setThemeRuntimeConfigJson(theme.toString());
        JsonNode frozen = publish(topic);
        JsonNode plan = frozen.path("themeConfig").path("gameplayRuntime").path("plans").path(0);
        assertEquals("a", plan.path("materialCost").path(0).path("itemId").asText());
        assertEquals(2, plan.path("materialCost").path(0).path("quantity").asInt());
        assertEquals("a", JSON.readTree(topic.getThemeRuntimeConfigJson()).path("templateBindings").path("11").path("items").path("supply").asText());
        assertFalse(frozen.toString().contains("templateBindings"));
        assertEquals(frozen.path("themeConfig"), JSON.readTree(runtime.freezePublishedJourney(topic)).path("themeConfig"));
        assertEquals(declaration, JSON.readTree(template.getAdvancedConfigJson()));
    }
    @Test void independentVoteAdoptionAndPublicationRejectActualRuntimeConflictWithReason() throws Exception {
        saveIndependentVote(voteDeclaration()); CmsTopic topic = topics.get(10L);
        for (String scope : Arrays.asList("PLAYER", "TEAM")) {
            ObjectNode theme = voteTheme(topic); theme.with("gameplayRuntime").put("runtimeScope", scope);
            if ("PLAYER".equals(scope)) theme.with("gameplayRuntime").remove("worldMode");
            else theme.with("gameplayRuntime").put("worldMode", "TEAM_INDEPENDENT");
            topic.setThemeRuntimeConfigJson(theme.toString());
            for (boolean freeze : Arrays.asList(false, true)) {
                String reason = assertThrows(IllegalArgumentException.class, () -> {
                    if (freeze) runtime.freezePublication(topic, null); else runtime.compileTemplateRules(topic);
                }).getMessage();
                assertTrue(reason.contains("11") && reason.contains("TEAM/WORLD_SHARED"), reason);
            }
        }
        assertEquals(voteDeclaration(), JSON.readTree(template.getAdvancedConfigJson()));
    }
    @Test void legacyLiteralVoteSavesAndFreezesWithoutConvertingToResourceDeclarations() throws Exception {
        ObjectNode legacy = JSON.createObjectNode().put("schemaVersion", 1);
        legacy.putObject("interactionTools").put("votePolicy", "MAJORITY").putArray("plans").addObject()
                .put("title", "旧投票").putArray("materialCost").addObject().put("itemId", "a").put("quantity", 1);
        saveIndependentVote(legacy); CmsTopic topic = topics.get(10L);
        ObjectNode theme = (ObjectNode) JSON.readTree(topic.getThemeRuntimeConfigJson()); theme.remove("templateBindings");
        theme.with("gameplayRuntime").put("runtimeScope", "TEAM").put("worldMode", "WORLD_SHARED"); topic.setThemeRuntimeConfigJson(theme.toString());
        assertEquals("a", publish(topic).path("themeConfig").path("gameplayRuntime").path("plans").path(0).path("materialCost").path(0).path("itemId").asText());
        assertEquals(legacy, JSON.readTree(template.getAdvancedConfigJson()));
    }
    @Test void independentVotePublicationRejectsMissingMaterialBindingWithNodeAndResourceReason() throws Exception {
        saveIndependentVote(voteDeclaration()); CmsTopic topic = topics.get(10L); ObjectNode theme = voteTheme(topic);
        theme.with("templateBindings").with("11").with("items").remove("supply"); topic.setThemeRuntimeConfigJson(theme.toString());
        String reason = assertThrows(IllegalArgumentException.class, () -> runtime.freezePublication(topic, null)).getMessage();
        assertTrue(reason.contains("11") && reason.contains("supply"), reason);
    }
    @Test void storyLinkedVotePublishesAndReentryKeepsFrozenOutcomeWithoutLiveBindings() throws Exception {
        CmsTopic topic=topics.get(10L);CmsTopicChapter endingChapter=new CmsTopicChapter();endingChapter.setId(900L);endingChapter.setTopicId(10L);endingChapter.setName("最终章");
        CmsTopicChapterMapper chapters=mock(CmsTopicChapterMapper.class);when(chapters.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(endingChapter));
        org.springframework.test.util.ReflectionTestUtils.setField(runtime,"cmsTopicChapterMapper",chapters);
        topic.setJourneyStoryJson("{\"schemaVersion\":1,\"ending\":{\"endings\":[{\"code\":\"finish\",\"title\":\"最终章\",\"chapterId\":900,\"fallback\":true}]}}");
        ObjectNode config=JSON.createObjectNode().put("schemaVersion",1);ObjectNode tools=config.putObject("interactionTools").put("votePolicy","MAJORITY").put("decidesEnding",true);
        tools.putArray("plans").addObject().put("planKey","finish").put("title","选择收尾");template.setAdvancedConfigJson(config.toString());
        ObjectNode definition=(ObjectNode)JSON.readTree(topic.getThemeRuntimeConfigJson());definition.with("gameplayRuntime").put("runtimeScope","TEAM").put("worldMode","WORLD_SHARED");
        ObjectNode binding=(ObjectNode)definition.path("templateBindings").path("11");binding.putObject("values");binding.putObject("items");
        binding.put("slotDigest",GameplayTemplateRules.slotDigest(JSON.createArrayNode())).put("planDigest",GameplayTemplateRules.planDigest(tools.path("plans")));binding.putObject("planEndings").put("finish","900");
        topic.setThemeRuntimeConfigJson(definition.toString());JsonNode frozen=publish(topic);
        assertEquals("finish",frozen.path("themeConfig").path("gameplayRuntime").path("plans").path(0).path("storyEndingId").asText());
        assertEquals(frozen.path("themeConfig"),JSON.readTree(runtime.freezePublishedJourney(topic)).path("themeConfig"));
        assertFalse(frozen.toString().contains("templateBindings"));
    }
    @Test void staleMissingAndForgedBindingsFailPublishWithNodeAndSlot() {
        CmsTopic topic = topics.get(10L);
        for (String fault : Arrays.asList("stale", "missing", "identity")) {
            ObjectNode definition = definition(11L, "sys.hp", "a"); ObjectNode binding = definition.with("templateBindings").with("11");
            if ("stale".equals(fault)) binding.put("slotDigest", String.join("", Collections.nCopies(64, "0")));
            if ("missing".equals(fault)) binding.with("values").remove("energy");
            if ("identity".equals(fault)) binding.put("templateId", 999L);
            topic.setThemeRuntimeConfigJson(definition.toString());
            String message = assertThrows(IllegalArgumentException.class, () -> runtime.freezePublication(topic, null)).getMessage();
            assertTrue(message.contains("11") && message.contains("energy"), message);
        }
    }
    @Test void disabledBindingIsCheckedBeforeCompilationErasesIt() {
        CmsTopic topic = topics.get(10L); topic.setJourneyRulesJson("{\"schemaVersion\":1,\"stateEnabled\":true,\"hp\":{\"enabled\":false}}");
        String message = assertThrows(IllegalArgumentException.class, () -> runtime.compileTemplateRules(topic)).getMessage();
        assertTrue(message.contains("11") && message.contains("energy") && message.contains("未启用"), message);
    }
    @Test void literalTemplateStillPublishesAndFreezesWithoutBindings() throws Exception {
        template.setAdvancedConfigJson("{\"schemaVersion\":1,\"interactionRules\":{\"hpCost\":2,\"hpDelta\":1,\"limit\":2,\"resourceCosts\":[{\"itemId\":\"a\",\"quantity\":1}]}}");
        CmsTopic topic = topics.get(10L); ObjectNode definition = definition(11L, "sys.hp", "a"); definition.remove("templateBindings"); topic.setThemeRuntimeConfigJson(definition.toString());
        JsonNode first = publish(topic); assertEquals(first.path("themeConfig"), publish(topic).path("themeConfig"));
        run(topic, 11L, 5, 5, "a");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"scan", "qaText", "qaPick", "sort", "match", "classify"})
    void everySupportedProofPublishesWithBoundCostsAndSettlesPersonally(String key) throws Exception {
        ObjectNode config = GameplaySupportMatrixTest.config(key); config.set("interactionRules", templateConfig().path("interactionRules"));
        template.setValidationMethod("scan".equals(key) ? 4 : key.startsWith("qa") ? "qaText".equals(key) ? 1 : 3 : 0);
        template.setAdvancedConfigJson(new AdvancedGameConfigValidator().validateAndNormalize(config.toString()));
        CmsTopic topic = topics.get(10L); JsonNode frozen = publish(topic);
        runtime.state(7L, 10L, 0L); TopicRouteSession session = sessions.get(10L);
        runtime.recordPlayerArrival(7L, 10L, 0L, 11L, "scan",session.getVersion());
        runtime.gameplayAction(7L, session.getId(), session.getVersion(), "start", "QUEST_START", "node_11", null);
        if (!"scan".equals(key)) {
            JsonNode answers = GameplaySupportMatrixTest.answers(key);
            assertTrue(runtime.verifyGuidedRouteAnswer(frozen, 11L, answers));
            assertThrows(IllegalArgumentException.class, () -> runtime.verifyGuidedRouteAnswer(frozen, 11L, JSON.createObjectNode().put("passed", true)));
            assertThrows(RuntimeException.class, () -> runtime.gameplayAction(7L, session.getId(), session.getVersion(), "unproved", "QUEST_RESOLVE", "node_11", null));
            runtime.recordNodeResult(7L, 10L, 0L, 11L, runtime.gameplayInstance(7L, 10L, 0L, 11L), "server-proof", true, false, 0, Collections.emptyList());
        }
        runtime.gameplayAction(7L, session.getId(), session.getVersion(), "settle", "QUEST_RESOLVE", "node_11", null);
        JsonNode state = JSON.readTree(session.getStateJson());
        assertEquals(5, state.path("variables").path("sys.hp").asInt());
        assertEquals(1, state.path("themeRuntime").path("inventory").path("a").asInt());
        assertEquals(TopicRouteSession.STATUS_COMPLETED, session.getStatus());
    }
    @Test void sceneOverridesAreResolvedAndRepublishUsesCurrentTemplateWithoutChangingOldSnapshot() throws Exception {
        CmsTopic topic = topics.get(10L); ObjectNode author = definition(11L, "sys.hp", "a").put("currentScene", "one");
        ObjectNode scene = author.putArray("scenes").addObject().put("id", "one").put("name", "场景");
        ObjectNode block = templateConfig(); ((ObjectNode) block.path("interactionRules").path("statCosts").path(0)).put("amount", 3);
        scene.putObject("nodeConfigs").set("11", block);
        topic.setThemeRuntimeConfigJson(author.toString()); JsonNode frozen = publish(topic);
        assertFalse(frozen.toString().contains("resourceSlots"),frozen.toString()); assertFalse(frozen.toString().contains("templateBindings"));
        assertEquals(3, frozen.path("nodeConfigs").path("11").path("interactionRules").path("hpCost").asInt());
        assertEquals(3, frozen.path("themeConfig").path("gameplayRuntime").path("facilities").path(0).path("hpCost").asInt());
        assertEquals(frozen.path("themeConfig"), JSON.readTree(runtime.freezePublishedJourney(topic)).path("themeConfig"));
        run(topic, 11L, 4, 5, "a");
        topic.setThemeRuntimeConfigJson(definition(11L, "sys.hp", "a").toString()); JsonNode newer = publish(topic);
        assertEquals(2, newer.path("themeConfig").path("gameplayRuntime").path("facilities").path(0).path("hpCost").asInt());
        assertEquals(3, JSON.readTree(sessions.get(10L).getConfigSnapshotJson()).path("themeConfig").path("gameplayRuntime").path("facilities").path(0).path("hpCost").asInt());
    }
    @Test void copiedAuthorBindingUsesCopiedNodeAndItsTrustedTemplate() throws Exception {
        CmsTopic copy = topics.get(20L);
        copy.setThemeRuntimeConfigJson(ThemeRuntimeConfig.remapNodes(topics.get(10L).getThemeRuntimeConfigJson(), Collections.singletonMap(11L, 21L)));
        JsonNode frozen = publish(copy);
        assertEquals(21, frozen.path("themeConfig").path("gameplayRuntime").path("facilities").path(0).path("operationNodeId").asInt());
        assertFalse(frozen.path("nodeConfigs").has("11")); run(copy, 21L, 5, 5, "a");
    }
    @Test void inactiveSceneCannotHideAnUnsupportedSettlementBridge() throws Exception {
        CmsTopic topic = topics.get(10L); ObjectNode author = definition(11L, "sys.hp", "a").put("currentScene", "one");
        author.putArray("scenes").addObject().put("id", "one").put("name", "可运行场景");
        ObjectNode unsupported = JSON.createObjectNode().put("schemaVersion", 1);
        unsupported.putObject("estimate").put("enabled", true).put("title", "猜数字").put("answer", 10).put("min", 0).put("max", 20).put("tolerance", 1);
        author.withArray("scenes").addObject().put("id", "two").put("name", "另一场景").putObject("nodeConfigs").set("11", unsupported);
        topic.setThemeRuntimeConfigJson(author.toString());
        String message = assertThrows(IllegalArgumentException.class, () -> runtime.freezePublication(topic, null)).getMessage();
        assertTrue(message.contains("11") && message.contains("estimate") && message.contains("genericSettlement"), message);
    }
    @Test void unsupportedLegacyGameplayWithoutGenericToolsStillPublishesUnchanged() throws Exception {
        CmsTopic topic = topics.get(10L); String author = "{\"schemaVersion\":1,\"capacity\":8}"; topic.setThemeRuntimeConfigJson(author);
        ObjectNode old = JSON.createObjectNode().put("schemaVersion", 1); old.putObject("estimate").put("enabled", true);
        template.setAdvancedConfigJson(old.toString()); template.setValidationMethod(8);
        assertEquals(author, runtime.compileTemplateRules(topic));
        JsonNode frozen = JSON.readTree(runtime.freezePublication(topic, null));
        assertEquals(old, frozen.path("nodeConfigs").path("11")); assertFalse(frozen.path("themeConfig").has("gameplayRuntime"));
    }
    @Test void extraBindingCannotReferenceAnotherThemesNode() {
        CmsTopic topic = topics.get(10L); ObjectNode author = definition(11L, "sys.hp", "a");
        author.with("templateBindings").set("21", author.path("templateBindings").path("11").deepCopy()); topic.setThemeRuntimeConfigJson(author.toString());
        String message = assertThrows(IllegalArgumentException.class, () -> runtime.compileTemplateRules(topic)).getMessage();
        assertTrue(message.contains("21") && message.contains("不属于本主题"), message);
    }
    @Test void olderPublishedSnapshotWithoutNodeConfigsKeepsItsLiteralTheme() throws Exception {
        template.setAdvancedConfigJson("{\"schemaVersion\":1,\"interactionRules\":{\"hpCost\":2}}");
        CmsTopic topic = topics.get(10L); ObjectNode author = definition(11L, "sys.hp", "a"); author.remove("templateBindings");
        topic.setThemeRuntimeConfigJson(author.toString()); ObjectNode frozen = (ObjectNode) publish(topic); frozen.remove("nodeConfigs"); topic.setPublishedRouteGraphJson(frozen.toString());
        assertEquals(frozen.path("themeConfig"), JSON.readTree(runtime.freezePublishedJourney(topic)).path("themeConfig"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"remove", "kind", "operation"})
    void changedSlotDeclarationRevalidatesEveryReferenceButCannotChangeFrozenRun(String change) throws Exception {
        CmsTopic topic = topics.get(10L);
        ObjectNode author = definition(11L, "sys.hp", "a");
        CmsTopicNode second = new CmsTopicNode(); second.setId(12L); second.setTopicId(10L);
        second.setStatus(1L); second.setTemplateId(501L); second.setMerchantMemberId(30L); nodes.put(12L, second);
        author.with("templateBindings").set("12", author.path("templateBindings").path("11").deepCopy());
        topic.setThemeRuntimeConfigJson(author.toString());
        JsonNode frozen = publish(topic); runtime.state(7L, 10L, 0L);
        ObjectNode changed = templateConfig();
        com.fasterxml.jackson.databind.node.ArrayNode slots = (com.fasterxml.jackson.databind.node.ArrayNode) changed.path("interactionRules").path("resourceSlots");
        if ("remove".equals(change)) { slots.remove(0); changed.with("interactionRules").remove(Arrays.asList("statCosts", "statChanges")); }
        if ("kind".equals(change)) ((ObjectNode) slots.get(0)).put("kind", "ITEM");
        if ("operation".equals(change)) {
            slots.addObject().put("key", "backup").put("kind", "ITEM").put("label", "备用").put("required", true);
            changed.with("interactionRules").withArray("resourceCosts").addObject().put("itemId", "backup").put("quantity", 1);
        }
        template.setAdvancedConfigJson(changed.toString());
        for (long id : new long[]{11, 12}) {
            CmsTopicNode other = nodes.remove(id == 11 ? 12L : 11L);
            ObjectNode single = author.deepCopy(); single.with("templateBindings").remove(String.valueOf(other.getId()));
            topic.setThemeRuntimeConfigJson(single.toString());
            String error = assertThrows(IllegalArgumentException.class, () -> runtime.compileTemplateRules(topic)).getMessage();
            assertTrue(error.contains(String.valueOf(id)) && (error.contains("energy") || error.contains("supply") || error.contains("backup")), error);
            nodes.put(other.getId(), other);
        }
        assertEquals(frozen, JSON.readTree(runtime.freezePublishedJourney(topic)));
        run(topic, 11L, 5, 5, "a");
        assertEquals(frozen, JSON.readTree(sessions.get(10L).getConfigSnapshotJson()));
    }

    @Test void oneTemplateAtTwoNodesKeepsBindingsCostsAndCountersIndependent() throws Exception {
        CmsTopic topic = topics.get(10L); ObjectNode author = definition(11L, "sys.hp", "a");
        CmsTopicNode second = new CmsTopicNode(); second.setId(12L); second.setTopicId(10L);
        second.setStatus(1L); second.setTemplateId(501L); second.setMerchantMemberId(30L); nodes.put(12L, second);
        author.with("templateBindings").set("12", definition(12L, "counter.x", "b").path("templateBindings").path("12"));
        author.with("gameplayRuntime").withArray("initialGrants").addObject().put("itemId", "b").put("quantity", 2);
        topic.setThemeRuntimeConfigJson(author.toString()); publish(topic); runtime.state(7L, 10L, 0L);
        TopicRouteSession session = sessions.get(10L);
        for (long id : new long[]{11, 12}) {
            runtime.recordPlayerArrival(7L, 10L, 0L, id, "scan-"+id+"-"+session.getVersion(),session.getVersion());
            runtime.gameplayAction(7L, session.getId(), session.getVersion(), "start" + id, "QUEST_START", "node_" + id, null);
            runtime.gameplayAction(7L, session.getId(), session.getVersion(), "resolve" + id, "QUEST_RESOLVE", "node_" + id, null);
        }
        JsonNode state = JSON.readTree(session.getStateJson());
        assertEquals(5, state.path("variables").path("sys.hp").asInt());
        assertEquals(4, state.path("variables").path("counter.x").asInt());
        for (String item : Arrays.asList("a", "b")) assertEquals(1, state.path("themeRuntime").path("inventory").path(item).asInt());
        JsonNode counts = state.path("themeRuntime").path("cooperation").path("facilities");
        assertEquals(2, counts.size()); for (JsonNode count : counts) assertEquals(1, count.path("count").asInt());
    }
}
