package com.chengyinhub.business.service.support;

import com.chengyinhub.business.util.AdvancedGamePublicProjection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GameplayTemplateToolsTest {
    @Test void r5PlanCompilesCountsWithoutCreatingASecondFacilityOrFeePath() {
        ObjectNode config=blocks(tools());ObjectNode block=(ObjectNode)config.path("101");block.putObject("qa").put("enabled",true).put("mode","TYPE").put("title","title").put("answerText","answer");block.putObject("interactionRules").put("scope","WORLD").put("targetScope","OWNER").put("limit",2);ObjectNode outcome=block.putObject("outcomeRules").put("version",1);outcome.putObject("pass");outcome.putObject("fail");
        GameplayOutcomeRules.validateBlock(block);ObjectNode compiled=GameplayTemplateRules.merge(theme(),config,realNodes());assertTrue(compiled.path("gameplayRuntime").path("plans").size()>0);assertEquals(0,compiled.path("gameplayRuntime").path("facilities").size());assertFalse(compiled.path("gameplayRuntime").path("quests").path(0).has("facilityKey"));
        block.with("interactionRules").put("targetScope","WORLD");assertThrows(IllegalArgumentException.class,()->GameplayTemplateRules.merge(theme(),config,realNodes()));
    }

    @Test void stableTerminalPlanBindsStoryEndingAtAdoptionAndKeepsLegacyCatalog() {
        ObjectNode author=theme(), tools=tools().put("decidesEnding",true);
        ((ObjectNode)tools.path("plans").path(0)).put("planKey","finish");
        ObjectNode story=json.createObjectNode();ArrayNode endings=story.putObject("ending").putArray("endings");
        endings.addObject().put("code","good").put("title","最终章").put("summary","收尾").put("chapterId",900);
        endings.addObject().put("code","fallback").put("title","默认").put("fallback",true).put("chapterId",901);
        ObjectNode binding=author.putObject("templateBindings").putObject("101").put("templateId",501)
                .put("slotDigest",GameplayTemplateRules.slotDigest(json.createArrayNode())).put("planDigest",GameplayTemplateRules.planDigest(tools.path("plans")));
        binding.putObject("values");binding.putObject("items");binding.putObject("planEndings").put("finish","900");
        ObjectNode compiled=GameplayTemplateRules.merge(author,blocks(tools),realNodes(),Collections.singletonMap(101L,501L),story);
        assertEquals("good",plan(compiled).path("storyEndingId").asText());assertEquals(900,plan(compiled).path("endingChapterId").asLong());
        assertEquals("tpl_vote_101_1",plan(compiled).path("endingKey").asText());assertFalse(compiled.has("templateBindings"));
        assertNotNull(ThemeRuntimeConfig.parseDraft(compiled.toString()));
        ((ObjectNode)tools.path("plans").path(0)).put("title","修改后");
        String stale=assertThrows(IllegalArgumentException.class,()->GameplayTemplateRules.merge(author,blocks(tools),realNodes(),Collections.singletonMap(101L,501L),story)).getMessage();
        assertTrue(stale.contains("101") && stale.contains("finish") && stale.contains("重新链接"));
        binding.put("planDigest",GameplayTemplateRules.planDigest(tools.path("plans")));binding.with("planEndings").put("finish","999");
        String invalid=assertThrows(IllegalArgumentException.class,()->GameplayTemplateRules.merge(author,blocks(tools),realNodes(),Collections.singletonMap(101L,501L),story)).getMessage();
        assertTrue(invalid.contains("101") && invalid.contains("finish") && invalid.contains("journeyStory"));
        binding.with("planEndings").remove("finish");assertThrows(IllegalArgumentException.class,()->GameplayTemplateRules.merge(author,blocks(tools),realNodes(),Collections.singletonMap(101L,501L),story));
    }
    @Test void unlinkedTerminalPlansFinishInTheFinalChapterWithTheirOwnOutcomeAndOneFallback() {
        ObjectNode author=theme(), tools=tools().put("decidesEnding",true);
        ArrayNode plans=(ArrayNode)tools.path("plans");((ObjectNode)plans.path(0)).put("planKey","evacuate").put("summary","撤离联络已确认");
        plans.addObject().put("planKey","shutdown").put("title","关闭太阳").put("summary","额外抽热已停止");
        tools.putObject("fallback").put("title","沉默的城市").put("summary","维护窗口结束");
        ObjectNode binding=author.putObject("templateBindings").putObject("101").put("templateId",501).put("slotDigest",GameplayTemplateRules.slotDigest(json.createArrayNode()));
        binding.putObject("values");binding.putObject("items");
        ObjectNode finalChapter=json.createObjectNode().put("id",77).put("name","最后的太阳");
        ObjectNode compiled=GameplayTemplateRules.merge(author,blocks(tools),realNodes(),Collections.singletonMap(101L,501L),json.createObjectNode(),finalChapter);
        JsonNode game=compiled.path("gameplayRuntime");
        assertEquals(2,game.path("plans").size());
        for(JsonNode plan:game.path("plans")){assertTrue(plan.path("terminal").asBoolean());assertEquals(plan.path("endingKey").asText(),plan.path("storyEndingId").asText());assertEquals(77,plan.path("endingChapterId").asLong());}
        JsonNode catalog=game.path("storyEndings");assertEquals(3,catalog.size());
        assertEquals("关闭太阳",catalog.path(1).path("title").asText());assertEquals("额外抽热已停止",catalog.path(1).path("summary").asText());
        assertEquals("沉默的城市",catalog.path(2).path("title").asText());assertTrue(catalog.path(2).path("fallback").asBoolean());assertEquals(77,catalog.path(2).path("chapterId").asLong());
        ThemeRuntimeConfig.assertPlanEndings(compiled);
        // 未写无方案收场时用最终章名称；再次编译不累加目录
        tools.remove("fallback");
        compiled.set("templateBindings",author.get("templateBindings").deepCopy());
        compiled=GameplayTemplateRules.merge(compiled,blocks(tools),realNodes(),Collections.singletonMap(101L,501L),json.createObjectNode(),finalChapter);
        assertEquals(3,compiled.path("gameplayRuntime").path("storyEndings").size());
        assertEquals("最后的太阳",compiled.path("gameplayRuntime").path("storyEndings").path(2).path("title").asText());
        // 收场由章节决定：模板不写 decidesEnding 也一样按最终章收场
        // 旧模板（旧编辑器必填表决方式、未声明决定结局）在最终章仍是普通共同方案
        tools.remove("decidesEnding");
        compiled=GameplayTemplateRules.merge(author,blocks(tools),realNodes(),Collections.singletonMap(101L,501L),json.createObjectNode(),finalChapter);
        for(JsonNode plan:compiled.path("gameplayRuntime").path("plans")){assertFalse(plan.path("terminal").asBoolean());assertFalse(plan.has("storyEndingId"));}
        tools.remove("votePolicy");
        compiled=GameplayTemplateRules.merge(author,blocks(tools),realNodes(),Collections.singletonMap(101L,501L),json.createObjectNode(),finalChapter);
        for(JsonNode plan:compiled.path("gameplayRuntime").path("plans"))assertTrue(plan.path("terminal").asBoolean());
        // 主题没有最终章（无章节设置）时，表决只是普通方案
        compiled=GameplayTemplateRules.merge(author,blocks(tools),realNodes(),Collections.singletonMap(101L,501L),json.createObjectNode());
        for(JsonNode plan:compiled.path("gameplayRuntime").path("plans")){assertFalse(plan.path("terminal").asBoolean());assertFalse(plan.has("storyEndingId"));}
        // 表决方式归主题：模板不写时取主题设置，写了而与主题不同则拒绝
        author.with("gameplayRuntime").put("votePolicy","MAJORITY_ACCEPTANCE");
        compiled=GameplayTemplateRules.merge(author,blocks(tools),realNodes(),Collections.singletonMap(101L,501L),json.createObjectNode(),finalChapter);
        assertEquals("MAJORITY_ACCEPTANCE",compiled.path("gameplayRuntime").path("votePolicy").asText());
        tools.put("votePolicy","MAJORITY");
        String policy=assertThrows(IllegalArgumentException.class,()->GameplayTemplateRules.merge(author,blocks(tools),realNodes(),Collections.singletonMap(101L,501L),json.createObjectNode(),finalChapter)).getMessage();
        assertTrue(policy.contains("表决方式"),policy);
    }
    @Test void planDigestHasCanonicalStableAndLegacyVectors() throws Exception {
        assertEquals("80d492cc83d2133da813e9464b185828805137a67ae6b3efb1f39c8ec953ab29",GameplayTemplateRules.planDigest(json.readTree("[{\"planKey\":\"leave\",\"title\":\"离开\"},{\"planKey\":\"stay\",\"title\":\"留守\"}]")));
        assertEquals("b719abed77dd4b6ddc7a8a14b99a6f1905e46a2152a6652e5d6fef206ef744f7",GameplayTemplateRules.planDigest(json.readTree("[{\"title\":\"甲\"},{\"title\":\"乙\"}]")));
    }
    @Test void savedAndCopiedPlanBindingsFollowRealEndingChapterIds() {
        ObjectNode source=theme();
        ObjectNode binding=source.putObject("templateBindings").putObject("101").put("templateId",501)
                .put("slotDigest",GameplayTemplateRules.slotDigest(json.createArrayNode())).put("planDigest",GameplayTemplateRules.planDigest(json.createArrayNode()));
        binding.putObject("values");binding.putObject("items");binding.putObject("planEndings").put("finish","900");
        Map<Long,Long> nodes=new LinkedHashMap<>();nodes.put(101L,201L);nodes.put(102L,202L);
        Map<Long,Long> chapters=Collections.singletonMap(900L,1900L);
        JsonNode copied=ThemeRuntimeConfig.parseDraft(ThemeRuntimeConfig.remapAll(source.toString(),nodes,chapters,null));
        assertEquals("1900",copied.path("templateBindings").path("201").path("planEndings").path("finish").asText());
        JsonNode saved=ThemeRuntimeConfig.parseDraft(ThemeRuntimeConfig.remapForNodeSave(source.toString(),nodes,chapters,Collections.emptyMap()));
        assertEquals(copied,saved);
        source.with("templateBindings").set("client:new",source.with("templateBindings").remove("101"));
        JsonNode adopted=ThemeRuntimeConfig.parseDraft(ThemeRuntimeConfig.remapForNodeSave(source.toString(),nodes,chapters,Collections.singletonMap("new",301L)));
        assertEquals("1900",adopted.path("templateBindings").path("301").path("planEndings").path("finish").asText());
        assertEquals("900",source.path("templateBindings").path("client:new").path("planEndings").path("finish").asText());
        String error=assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.remapForNodeSave(source.toString(),nodes,Collections.emptyMap(),Collections.singletonMap("new",301L))).getMessage();
        assertTrue(error.contains("finish") && error.contains("900"),error);
    }
    private final ObjectMapper json = new ObjectMapper();
    private ObjectNode theme() {
        ObjectNode theme = json.createObjectNode().put("schemaVersion", 1).put("capacity", 8);
        theme.putArray("items").addObject().put("id", "tool").put("kind", "ITEM").put("name", "工具").put("image", "https://www.chengyinhub.com/favicon.ico").put("slotCost", 1);
        ObjectNode game = theme.putObject("gameplayRuntime").put("runtimeScope", "TEAM").put("worldMode", "WORLD_SHARED");
        ObjectNode act = game.putArray("acts").addObject().put("actKey", "walk"); act.putArray("questKeys").add("gate").add("park"); act.putObject("completionRule").put("type", "ALL");
        com.fasterxml.jackson.databind.node.ArrayNode quests = game.putArray("quests");
        quests.addObject().put("questKey", "gate").put("actKey", "walk").put("nodeId", 101).put("locationKey", "gate").put("settlementSlot", "gate");
        quests.addObject().put("questKey", "park").put("actKey", "walk").put("nodeId", 102).put("locationKey", "park").put("settlementSlot", "park");
        return theme;
    }
    private ObjectNode tools() {
        ObjectNode tools = json.createObjectNode().put("allowTrade", true).put("votePolicy", "MAJORITY");
        tools.putArray("plans").addObject().put("title", "共同修复").put("summary", "讨论后投入所需物资").putArray("materialCost").addObject().put("itemId", "tool").put("quantity", 2);
        return tools;
    }
    private ObjectNode blocks(ObjectNode tools) { ObjectNode blocks = json.createObjectNode(); blocks.putObject("101").put("schemaVersion", 1).set("interactionTools", tools); return blocks; }
    private Map<Long, String> realNodes() { Map<Long, String> actual = new LinkedHashMap<>(); actual.put(101L, "walk"); actual.put(102L, "walk"); return actual; }
    private JsonNode plan(JsonNode compiled) { return compiled.path("gameplayRuntime").path("plans").get(0); }

    @Test void explicitEndingVoteRequiresMappedEndingAndSurvivesNodeCopy() throws Exception {
        ObjectNode author=theme(); author.putArray("endings").addObject().put("id", "chosen").put("title", "选中");
        author.withArray("endings").addObject().put("id", "default").put("title", "默认").put("fallback", true);
        ObjectNode tools=tools().put("decidesEnding", true);
        ((ObjectNode)tools.path("plans").get(0)).put("endingKey", "chosen");
        ObjectNode compiled=GameplayTemplateRules.merge(author, blocks(tools), realNodes());
        assertTrue(plan(compiled).path("terminal").asBoolean()); assertEquals("chosen", plan(compiled).path("endingKey").asText());
        ThemeRuntimeConfig.assertReferences(compiled, realNodes().keySet(), Collections.emptySet());
        Map<Long, Long> nodes=new HashMap<>(); nodes.put(101L, 201L); nodes.put(102L, 202L);
        ObjectNode copy=(ObjectNode)json.readTree(ThemeRuntimeConfig.remapAll(compiled.toString(), nodes, Collections.emptyMap(), Collections.emptyMap()));
        GameplayTemplateRules.remapToolIdentities(copy, nodes);
        assertEquals("chosen", plan(copy).path("endingKey").asText()); assertEquals(201, plan(copy).path("operationNodeId").asLong());
        assertNotNull(ThemeRuntimeConfig.parseDraft(copy.toString()));
    }
    @Test void explicitEndingVoteRejectsMissingKeyEndingFallbackAndDuplicatePlan() {
        ObjectNode tools=tools().put("decidesEnding", true);
        assertDoesNotThrow(() -> GameplayTemplateRules.normalizeTools(tools));
        ((ObjectNode)tools.path("plans").get(0)).put("endingKey", "chosen");
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.merge(theme(), blocks(tools), realNodes()));
        ObjectNode author=theme(); author.putArray("endings").addObject().put("id", "other").put("title", "其他").put("fallback", true);
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.merge(author, blocks(tools), realNodes()));
        ((ObjectNode)author.path("endings").get(0)).put("id", "chosen").put("fallback", false);
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.merge(author, blocks(tools), realNodes()));
        author.withArray("endings").addObject().put("id", "default").put("title", "默认").put("fallback", true);
        tools.withArray("plans").addObject().put("title", "第二方案").put("endingKey", "chosen");
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.merge(author, blocks(tools), realNodes()));
        ObjectNode nonterminal=tools().put("decidesEnding", false); ((ObjectNode)nonterminal.path("plans").get(0)).put("endingKey", "chosen");
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.normalizeTools(nonterminal));
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.normalizeTools(tools().put("decidesEnding", "yes")));
    }

    @Test void authorToolsCompileToExistingWorldCommandsAndNonterminalRealNodePlans() {
        ObjectNode author = theme(), config = blocks(tools());
        ObjectNode compiled = GameplayTemplateRules.merge(author, config, realNodes());
        JsonNode game = compiled.path("gameplayRuntime"), plan = plan(compiled);
        assertTrue(game.path("allowTrade").asBoolean()); assertEquals("MAJORITY", game.path("votePolicy").asText());
        assertEquals("tpl_vote_101_1", plan.path("endingKey").asText()); assertEquals(101, plan.path("operationNodeId").asLong()); assertFalse(plan.path("terminal").asBoolean(true));
        assertEquals(2, plan.path("materialCost").get(0).path("quantity").asInt()); assertFalse(game.path("quests").get(0).has("facilityKey")); assertFalse(game.has("facilities"));
        assertFalse(author.path("gameplayRuntime").has("templateTools")); assertFalse(config.toString().contains("endingKey"));
        assertNotNull(ThemeRuntimeConfig.parseDraft(compiled.toString()));
    }
    @Test void updatesAndDeletingAllToolsRestoreOriginalManualBaselineWithoutStalePlans() {
        ObjectNode author = theme(); author.with("gameplayRuntime").put("allowTrade", false);
        author.with("gameplayRuntime").putArray("plans").addObject().put("endingKey", "manual").put("title", "手工方案").put("operationNodeId", 102).put("terminal", false);
        ObjectNode compiled = GameplayTemplateRules.merge(author, blocks(tools()), realNodes());
        ObjectNode changedTools = tools(); ((ObjectNode) changedTools.path("plans").get(0)).put("title", "修复新方案");
        ObjectNode edited = GameplayTemplateRules.merge(compiled, blocks(changedTools), realNodes());
        assertEquals(2, edited.path("gameplayRuntime").path("plans").size()); assertEquals(edited, GameplayTemplateRules.merge(edited, blocks(changedTools), realNodes()));
        ObjectNode disabled = GameplayTemplateRules.merge(edited, json.createObjectNode(), realNodes());
        assertFalse(disabled.path("gameplayRuntime").path("allowTrade").asBoolean()); assertFalse(disabled.path("gameplayRuntime").has("votePolicy")); assertFalse(disabled.path("gameplayRuntime").has("templateTools"));
        assertEquals(1, disabled.path("gameplayRuntime").path("plans").size()); assertEquals("manual", plan(disabled).path("endingKey").asText());
        assertEquals(2, edited.path("gameplayRuntime").path("plans").size());
    }
    @Test void deletingTradeOnlyTemplatePreservesManualTrueAndManualVotingPolicy() {
        ObjectNode author = theme(); author.with("gameplayRuntime").put("allowTrade", true).put("votePolicy", "UNANIMOUS");
        ObjectNode compiled = GameplayTemplateRules.merge(author, blocks(json.createObjectNode().put("allowTrade", true)), realNodes());
        ObjectNode removed = GameplayTemplateRules.merge(compiled, json.createObjectNode(), realNodes());
        assertTrue(removed.path("gameplayRuntime").path("allowTrade").asBoolean()); assertEquals("UNANIMOUS", removed.path("gameplayRuntime").path("votePolicy").asText());
        ObjectNode noManual = GameplayTemplateRules.merge(theme(), blocks(json.createObjectNode().put("allowTrade", true)), realNodes());
        assertFalse(GameplayTemplateRules.merge(noManual, json.createObjectNode(), realNodes()).path("gameplayRuntime").has("allowTrade"));
    }
    @Test void differentNodeVotingPoliciesAndManualPolicyConflictsFailPublication() {
        ObjectNode config = blocks(tools()); config.putObject("102").set("interactionTools", tools().put("votePolicy", "UNANIMOUS"));
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.merge(theme(), config, realNodes()));
        ObjectNode author = theme(); author.with("gameplayRuntime").put("votePolicy", "UNANIMOUS");
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.merge(author, blocks(tools()), realNodes()));
    }
    @Test void templatesCannotSetTerminalOrIdentifiersAndUnknownMaterialsRemainThemeValidated() {
        ObjectNode forged = tools(); ((ObjectNode) forged.path("plans").get(0)).put("terminal", true);
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.normalizeTools(forged));
        ObjectNode forgedId = tools(); ((ObjectNode) forgedId.path("plans").get(0)).put("endingKey", "author_plan"); assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.normalizeTools(forgedId));
        ObjectNode wrongType = tools().put("allowTrade", "yes"); assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.normalizeTools(wrongType));
        ObjectNode material = tools(); ((ObjectNode) material.path("plans").get(0).path("materialCost").get(0)).put("itemId", "unknown");
        assertNotNull(GameplayTemplateRules.normalizeTools(material)); assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.merge(theme(), blocks(material), realNodes()));
    }
    @Test void votingCannotStackFeesButTradeCanShareAnExistingFacilityNode() {
        ObjectNode vote = blocks(tools()); vote.with("101").putObject("interactionRules").put("hpCost", 1);
        assertThrows(IllegalArgumentException.class, () -> new AdvancedGameConfigValidator().validateAndNormalize(vote.path("101").toString()));
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.merge(theme(), vote, realNodes()));
        ObjectNode trade = blocks(json.createObjectNode().put("allowTrade", true)); trade.with("101").putObject("interactionRules").put("hpCost", 1).put("limit", 2);
        assertDoesNotThrow(() -> new AdvancedGameConfigValidator().validateAndNormalize(trade.path("101").toString()));
        ObjectNode compiled = GameplayTemplateRules.merge(theme(), trade, realNodes());
        assertTrue(compiled.path("gameplayRuntime").path("allowTrade").asBoolean()); assertEquals(1, compiled.path("gameplayRuntime").path("facilities").size());
    }
    @Test void votingRequiresFreeSingleTaskAndCannotOverwriteHandwrittenPlanIdentity() {
        ObjectNode paid = theme(); ((ObjectNode) paid.path("gameplayRuntime").path("quests").get(0)).put("hpCost", 1);
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.merge(paid, blocks(tools()), realNodes()));
        ObjectNode author = theme(); author.with("gameplayRuntime").putArray("plans").addObject().put("endingKey", "tpl_vote_101_1").put("title", "手工方案").put("operationNodeId", 101).put("terminal", false);
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.merge(author, blocks(tools()), realNodes()));
    }
    @Test void metadataMustMatchNonterminalActualPlanBeforeAnythingCanBeDeleted() {
        ObjectNode compiled = GameplayTemplateRules.merge(theme(), blocks(tools()), realNodes());
        ((ObjectNode) plan(compiled)).put("operationNodeId", 102);
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.merge(compiled, json.createObjectNode(), realNodes()));
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parseDraft(compiled.toString()));
        ObjectNode unknown = GameplayTemplateRules.merge(theme(), blocks(tools()), realNodes()); unknown.with("gameplayRuntime").with("templateTools").put("script", "deleteAll()");
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parseDraft(unknown.toString()));
    }
    @Test void copyingRemapsOnlyGeneratedPlanKeysAfterActualNodeReferences() throws Exception {
        ObjectNode source = GameplayTemplateRules.merge(theme(), blocks(tools()), realNodes());
        Map<Long, Long> nodes = new HashMap<>(); nodes.put(101L, 201L); nodes.put(102L, 202L);
        ObjectNode copy = (ObjectNode) json.readTree(ThemeRuntimeConfig.remapAll(source.toString(), nodes, Collections.emptyMap(), Collections.emptyMap()));
        GameplayTemplateRules.remapToolIdentities(copy, nodes);
        assertEquals("tpl_vote_201_1", plan(copy).path("endingKey").asText()); assertEquals(201, plan(copy).path("operationNodeId").asLong());
        assertEquals("tpl_vote_201_1", copy.path("gameplayRuntime").path("templateTools").path("nodes").get(0).path("planKeys").get(0).asText());
        assertEquals("tpl_vote_101_1", plan(source).path("endingKey").asText()); assertNotNull(ThemeRuntimeConfig.parseDraft(copy.toString()));
    }
    @Test void toolsKeepWorldRequirementsAndPublicDetailsDoNotExposeConditionsOrValueRanges() throws Exception {
        ObjectNode player = theme(); player.with("gameplayRuntime").put("runtimeScope", "PLAYER").remove("worldMode");
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.merge(player, blocks(tools()), realNodes()));
        ObjectNode tools = tools(); ((ObjectNode) tools.path("plans").get(0)).putArray("when").addObject().put("var", "counter.secret").put("op", "GTE").put("value", 7);
        ObjectNode block = blocks(tools).with("101"); String stored = new AdvancedGameConfigValidator().validateAndNormalize(block.toString());
        assertTrue(stored.contains("counter.secret")); String publicConfig = AdvancedGamePublicProjection.safeConfigJson(stored);
        assertFalse(publicConfig.contains("interactionTools")); assertFalse(publicConfig.contains("counter.secret"));
    }
}
