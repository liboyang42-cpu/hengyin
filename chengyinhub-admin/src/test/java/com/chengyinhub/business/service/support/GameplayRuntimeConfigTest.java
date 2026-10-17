package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GameplayRuntimeConfigTest {
    private final ObjectMapper json=new ObjectMapper();
    private ObjectNode config() {
        ObjectNode c=json.createObjectNode().put("schemaVersion",1).put("capacity",14).put("hpName","保温储备");
        c.putArray("items").addObject().put("id","fuel").put("kind","ITEM").put("name","燃料").put("image","https://www.chengyinhub.com/favicon.ico").put("slotCost",2);
        ObjectNode g=c.putObject("gameplayRuntime").put("runtimeScope","TEAM").put("worldMode","WORLD_SHARED");
        g.putObject("survival").put("zeroHpPolicy","BLOCK_PAID").put("actionLimitPolicy","BLOCK_OVERFLOW").put("chapterRecovery",false);
        ObjectNode act=g.putArray("acts").addObject().put("actKey","supplies").put("hostUnlock",true);act.putArray("questKeys").add("north").add("east");act.putObject("completionRule").put("type","ANY_N").put("quantity",1);
        ObjectNode q=g.putArray("quests").addObject().put("questKey","north").put("actKey","supplies").put("nodeId",1).put("locationKey","gate").put("settlementSlot","north").put("hpCost",1).put("actionCost",1).put("exclusiveGroup","choice");
        q.putArray("choices").addObject().put("choiceKey","supplies").putArray("grants").addObject().put("itemId","fuel").put("quantity",2);
        g.withArray("quests").addObject().put("questKey","east").put("actKey","supplies").put("nodeId",2).put("locationKey","gate").put("settlementSlot","east").put("hpCost",0).put("actionCost",1).put("exclusiveGroup","choice");
        return c;
    }
    private ObjectNode game(ObjectNode c){return (ObjectNode)c.path("gameplayRuntime");}
    private ObjectNode quest(ObjectNode c,int i){return (ObjectNode)c.path("gameplayRuntime").path("quests").get(i);}
    private void invalid(ObjectNode c){assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.parseDraft(c.toString()));}
    private ObjectNode option(ObjectNode owner) {
        owner.remove(Arrays.asList("hpCost", "luckCost", "actionCost", "statCosts", "resourceCosts"));
        owner.putArray("costOptions").addObject().put("hpCost", 1);
        return owner.withArray("costOptions").addObject().put("actionCost", 2);
    }

    @Test void modeLivesInOneOptionalSegmentAndLegacyDefaultsRemain() {
        assertEquals("PLAYER",GameplayRuntimeConfig.getRuntimeScope(null));assertNull(GameplayRuntimeConfig.getWorldMode(null));
        ObjectNode legacy=ThemeRuntimeConfig.parse("{\"schemaVersion\":1}");assertFalse(legacy.has("gameplayRuntime"));assertEquals("PLAYER",GameplayRuntimeConfig.getRuntimeScope(legacy));
        ObjectNode c=config();assertEquals("TEAM",GameplayRuntimeConfig.getRuntimeScope(ThemeRuntimeConfig.parseDraft(c.toString())));assertEquals("WORLD_SHARED",GameplayRuntimeConfig.getWorldMode(c));
        ObjectNode segment=GameplayRuntimeConfig.parse(c.toString());assertEquals("TEAM",segment.path("runtimeScope").asText());
        assertNotNull(ThemeRuntimeConfig.parse(c.toString())); // 支持组合的运行发布闸由CmsTopicService负责。
        assertEquals(ThemeRuntimeConfig.normalize(c.toString()),GameplayRuntimeConfig.normalize(c.toString()));
    }
    @Test void emptyActMembersAreAllowedOnlyInDraftAndStillValidateTheRule() {
        ObjectNode c=config(); game(c).putArray("quests");
        ObjectNode act=(ObjectNode)game(c).path("acts").get(0); act.putArray("questKeys");
        assertEquals("supplies",ThemeRuntimeConfig.parseDraft(c.toString()).path("gameplayRuntime").path("acts").get(0).path("actKey").asText());
        assertTrue(assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.parse(c.toString())).getMessage().contains("不能为空"));
        act.remove("questKeys"); assertNotNull(ThemeRuntimeConfig.parseDraft(c.toString()));
        act.with("completionRule").put("quantity",0); invalid(c);
        act.with("completionRule").put("quantity",501); invalid(c);
        act.with("completionRule").put("type","ALL").remove("quantity"); assertNotNull(ThemeRuntimeConfig.parseDraft(c.toString()));
        act.with("completionRule").put("quantity",1); invalid(c);
    }
    @Test void playerAndIndependentTeamsRejectWorldCombinationsAndUnknownScripts() {
        ObjectNode c=config();game(c).put("runtimeScope","PLAYER");invalid(c);
        c=config();game(c).put("worldMode","SOLO");invalid(c);
        c=config();c.put("runtimeScope","TEAM");invalid(c);
        c=config();game(c).put("script","transferOtherTeam()");invalid(c);
        c=config();game(c).put("scope","TEAM");invalid(c);
        c=config();game(c).put("worldMode","TEAM_INDEPENDENT");ObjectNode f=game(c).putArray("facilities").addObject().put("facilityKey","heat").put("actKey","supplies").put("scope","WORLD").put("targetScope","PARTICIPANTS").put("settlementSlot","heat").put("limit",1);invalid(c);
    }
    @Test void malformedCostsAndUnknownOrDuplicateItemsCannotBecomeRuntimeData() {
        ObjectNode c=config();quest(c,0).putArray("resourceCosts").addObject().put("itemId","absent").put("quantity",1);invalid(c);
        c=config();quest(c,0).put("hpCost",-1);invalid(c);
        c=config();quest(c,0).put("actionCost",1.5);invalid(c);
        c=config();quest(c,0).putArray("resourceCosts").addObject().put("itemId","fuel").put("quantity",Long.MAX_VALUE);invalid(c);
        c=config();quest(c,0).putArray("statCosts").addObject().put("statKey","sys.hp").put("amount",1);invalid(c);
        c=config();quest(c,0).putArray("grants").addObject().put("itemId","fuel").put("quantity",1);invalid(c);
    }
    @Test void optionalCostsRequireTwoToFourPositiveGroupsWithoutFixedFees() throws Exception {
        ObjectNode c=config(); ObjectNode first=quest(c,0); ObjectNode second=option(first);
        second.put("label", "物品").putArray("resourceCosts").addObject().put("itemId", c.path("items").get(0).path("id").asText()).put("quantity", 1);
        assertEquals(2, ThemeRuntimeConfig.parseDraft(c.toString()).path("gameplayRuntime").path("quests").get(0).path("costOptions").size());
        Map<Long, Long> nodes=new HashMap<>(); nodes.put(1L, 11L); nodes.put(2L, 12L);
        assertEquals(first.path("costOptions"), json.readTree(ThemeRuntimeConfig.remapNodes(c.toString(), nodes)).path("gameplayRuntime").path("quests").get(0).path("costOptions"));
        first.put("hpCost", 0);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parseDraft(c.toString())).getMessage().contains("任选费用与固定费用不能同时配置"));
        first.remove("hpCost"); first.withArray("costOptions").remove(1); invalid(c);
        first.withArray("costOptions").addObject().put("luckCost", 1);
        first.withArray("costOptions").addObject().put("hpCost", 1);
        first.withArray("costOptions").addObject().put("hpCost", 1);
        first.withArray("costOptions").addObject().put("hpCost", 1); invalid(c);
    }
    @Test void optionalCostsRejectMalformedOrUnpayableGroupsAndValidateFacilities() {
        ObjectNode c=config(); ObjectNode first=quest(c,0); ObjectNode second=option(first);
        second.put("hpCost", 0).put("actionCost", 0); invalid(c); second.put("actionCost", 2);
        second.put("hpCost", 1).put("script", "bad"); invalid(c); second.remove("script");
        second.put("hpCost", 1.5); invalid(c); second.remove("hpCost");
        second.put("label", "abcdefghijklmnopqrstu"); invalid(c); second.remove("label");
        second.putArray("statCosts").addObject().put("statKey", "sys.hp").put("amount", 1); invalid(c); second.remove("statCosts");
        second.putArray("resourceCosts").addObject().put("itemId", "missing").put("quantity", 1); invalid(c);
        ((ObjectNode)second.path("resourceCosts").get(0)).put("itemId", c.path("items").get(0).path("id").asText()); assertNotNull(ThemeRuntimeConfig.parseDraft(c.toString()));
        ObjectNode facility=game(c).putArray("facilities").addObject().put("facilityKey", "station").put("actKey", "supplies").put("scope", "OWNER").put("targetScope", "OWNER").put("settlementSlot", "station").put("limit", 1);
        option(facility); assertNotNull(ThemeRuntimeConfig.parseDraft(c.toString()));
        facility.putArray("resourceCosts"); invalid(c);
    }
    @Test void boundQuestAndFacilityAllowOptionalCostsOnOnlyOneSide() {
        ObjectNode config = config();
        ObjectNode quest = quest(config, 0).put("facilityKey", "station");
        ObjectNode facility = game(config).putArray("facilities").addObject().put("facilityKey", "station")
                .put("actKey", "supplies").put("scope", "OWNER").put("targetScope", "OWNER")
                .put("settlementSlot", "station").put("limit", 1);
        option(facility);
        assertNotNull(ThemeRuntimeConfig.parse(config.toString()));
        facility.remove("costOptions");
        option(quest);
        assertNotNull(ThemeRuntimeConfig.parse(config.toString()));
        option(facility);
        assertEquals("任务与绑定设施不能同时配置任选费用",
                assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parse(config.toString())).getMessage());
        quest.remove("facilityKey");
        assertNotNull(ThemeRuntimeConfig.parse(config.toString()));
    }
    @Test void challengeShapeReferencesAndPhysicalFallbackAreEnforced() throws Exception {
        ObjectNode c=config(), survival=game(c).with("survival"); survival.put("zeroHpPolicy", "CHALLENGE"); invalid(c);
        ObjectNode challenge=survival.putObject("zeroChallenge").put("challengeNodeId", 1).put("restoreTo", 4).put("limit", 3);
        challenge.putArray("alternativeNodeIds").add(2);
        assertNotNull(ThemeRuntimeConfig.parseDraft(c.toString()));
        ThemeRuntimeConfig.assertReferences(c, new HashSet<>(Arrays.asList(1L, 2L)), Collections.emptySet());
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.assertReferences(c, Collections.singleton(1L), Collections.emptySet()));
        Map<Long, Long> nodes=new HashMap<>(); nodes.put(1L, 11L); nodes.put(2L, 12L);
        JsonNode copy=json.readTree(ThemeRuntimeConfig.remapNodes(c.toString(), nodes)).path("gameplayRuntime").path("survival").path("zeroChallenge");
        assertEquals(11, copy.path("challengeNodeId").asLong()); assertEquals(12, copy.path("alternativeNodeIds").get(0).asLong());
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.remapNodes(c.toString(), Collections.singletonMap(1L, 11L)));
        ObjectNode configs=json.createObjectNode(); configs.putObject("1").putObject("ballShake").put("enabled", true);
        assertThrows(IllegalArgumentException.class, () -> GameplayRuntimeConfig.assertChallengeAlternative(survival, configs));
        configs.putObject("2").putObject("qa").put("enabled", true); GameplayRuntimeConfig.assertChallengeAlternative(survival, configs);
        challenge.withArray("alternativeNodeIds").add(2); invalid(c); challenge.withArray("alternativeNodeIds").remove(1);
        challenge.put("alternativeNodeIds", "2"); invalid(c); challenge.putArray("alternativeNodeIds").add(2).add(3).add(4).add(5); invalid(c);
        challenge.putArray("alternativeNodeIds").add(2);
        challenge.put("restoreTo", 0); invalid(c); challenge.put("restoreTo", 4);
        challenge.put("limit", 100); invalid(c); challenge.put("limit", 3);
        challenge.put("challengeNodeId", 0); invalid(c); challenge.put("challengeNodeId", 1);
        survival.put("zeroHpPolicy", "BLOCK_PAID"); invalid(c);
    }
    @Test void challengeRestoreCannotExceedConfiguredHpMaximum() {
        ObjectNode c=config(), survival=game(c).with("survival"); survival.put("zeroHpPolicy", "CHALLENGE");
        survival.putObject("zeroChallenge").put("challengeNodeId", 1).put("restoreTo", 9).put("limit", 1);
        com.chengyinhub.business.domain.JourneyRules rules=com.chengyinhub.business.domain.JourneyRules.parse("{\"schemaVersion\":1,\"stateEnabled\":true,\"hp\":{\"max\":8}}");
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.assertJourneyReferences(c, rules));
        ((ObjectNode)survival.path("zeroChallenge")).put("restoreTo", 8); ThemeRuntimeConfig.assertJourneyReferences(c, rules);
    }
    @Test void everyPlanReferencesAThemeEndingAndFallbackAtPublishGate() {
        ObjectNode c=config(); game(c).putArray("plans").addObject().put("endingKey", "outcome").put("title", "方案").put("operationNodeId", 2);
        Set<Long> nodes=new HashSet<>(Arrays.asList(1L, 2L));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.assertReferences(c, nodes, Collections.emptySet())).getMessage().contains("方案 outcome"));
        c.putArray("endings").addObject().put("id", "other").put("title", "其他").put("fallback", true);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.assertReferences(c, nodes, Collections.emptySet())).getMessage().contains("方案 outcome"));
        ((ObjectNode)c.path("endings").get(0)).put("id", "outcome").put("fallback", false);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.assertReferences(c, nodes, Collections.emptySet())).getMessage().contains("方案 outcome"));
        c.withArray("endings").addObject().put("id", "default").put("title", "默认").put("fallback", true);
        ThemeRuntimeConfig.assertReferences(ThemeRuntimeConfig.parseDraft(c.toString()), nodes, Collections.emptySet());
        c.withArray("endings").addObject().put("id", "another").put("title", "另一个").put("fallback", true);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.assertReferences(c, nodes, Collections.emptySet())).getMessage().contains("方案 outcome"));
        ((ObjectNode) c.path("gameplayRuntime").path("plans").get(0)).put("terminal", false);
        c.remove("endings"); ThemeRuntimeConfig.assertReferences(c, nodes, Collections.emptySet());
    }
    @Test void questDependenciesAndActChoicesAreConsistentAndAcyclic() {
        ObjectNode c=config();quest(c,0).putArray("prerequisites").add("east");quest(c,1).putArray("prerequisites").add("north");invalid(c);
        c=config();quest(c,0).putArray("prerequisites").add("missing");invalid(c);
        c=config();quest(c,0).put("actKey","missing");invalid(c);
        c=config();((ObjectNode)game(c).path("acts").get(0).path("completionRule")).put("quantity",3);invalid(c);
        c=config();quest(c,1).put("settlementSlot","north");invalid(c);
        c=config();((ObjectNode)game(c).path("acts").get(0)).putArray("questKeys").add("north");invalid(c);
        c=config();((ObjectNode)game(c).path("acts").get(0).path("completionRule")).put("type","ALL").remove("quantity");assertNotNull(ThemeRuntimeConfig.parseDraft(c.toString()));
        c=config();((ObjectNode)game(c).path("acts").get(0).path("completionRule")).put("quantity",2);invalid(c);
    }
    @Test void extraOnlyIsBooleanAndExcludedFromAnyNCapacity() throws Exception {
        ObjectNode config = config();
        quest(config, 0).remove("exclusiveGroup"); quest(config, 1).remove("exclusiveGroup");
        ObjectNode rule = (ObjectNode) game(config).path("acts").get(0).path("completionRule");
        rule.put("quantity", 2);
        quest(config, 1).put("extraSlot", "shared").put("extraOnly", false);
        assertNotNull(ThemeRuntimeConfig.parseDraft(config.toString()));
        quest(config, 1).put("extraOnly", true); invalid(config);
        rule.put("quantity", 1); assertNotNull(ThemeRuntimeConfig.parseDraft(config.toString()));
        quest(config, 0).put("extraSlot", "shared").put("extraOnly", true); invalid(config);
        for (String value : Arrays.asList("null", "1", "\"true\"", "[]", "{}")) {
            quest(config, 0).put("extraOnly", false);
            quest(config, 1).set("extraOnly", json.readTree(value)); invalid(config);
        }
    }
    @Test void allAllowsExclusiveGroupsAndIgnoresExtraOnlySlots() {
        ObjectNode config = config();
        ObjectNode act = (ObjectNode) game(config).path("acts").get(0);
        act.putObject("completionRule").put("type", "ALL");
        act.withArray("questKeys").add("extra_first").add("extra_second");
        for (int index = 0; index < 2; index++) {
            String key = index == 0 ? "extra_first" : "extra_second";
            game(config).withArray("quests").addObject().put("questKey", key).put("actKey", "supplies")
                    .put("nodeId", index + 3).put("locationKey", "gate").put("settlementSlot", key)
                    .put("extraSlot", "shared").put("extraOnly", true);
        }
        assertNotNull(ThemeRuntimeConfig.parseDraft(config.toString()));
        act.putObject("completionRule").put("type", "ANY_N").put("quantity", 2); invalid(config);
        ((ObjectNode) act.path("completionRule")).put("quantity", 1);
        assertNotNull(ThemeRuntimeConfig.parseDraft(config.toString()));
    }
    @Test void legacyBareExtraSlotsKeepTheirAllAndAnyNValidation() {
        ObjectNode config = config();
        for (int index = 0; index < 2; index++) {
            quest(config, index).remove("exclusiveGroup"); quest(config, index).put("extraSlot", "shared");
        }
        ObjectNode act = (ObjectNode) game(config).path("acts").get(0);
        act.putObject("completionRule").put("type", "ALL"); invalid(config);
        act.putObject("completionRule").put("type", "ANY_N").put("quantity", 2);
        assertNotNull(ThemeRuntimeConfig.parseDraft(config.toString()));
        for (int index = 0; index < 2; index++) quest(config, index).put("extraOnly", false);
        assertNotNull(ThemeRuntimeConfig.parseDraft(config.toString()));
        act.putObject("completionRule").put("type", "ALL"); invalid(config);
    }
    @Test void connectedFacilitiesAndPlanMaterialsPublishWithControlledEffects() {
        ObjectNode c=config();ObjectNode f=game(c).putArray("facilities").addObject().put("facilityKey","heat").put("actKey","supplies").put("scope","WORLD").put("targetScope","PARTICIPANTS").put("settlementSlot","heat").put("limit",1).put("hpDelta",3);
        f.putArray("resourceCosts").addObject().put("itemId","fuel").put("quantity",1);
        ObjectNode plan=game(c).putArray("plans").addObject().put("endingKey","leave").put("title","撤离").put("operationNodeId",2);plan.putArray("materialCost").addObject().put("itemId","fuel").put("quantity",5);
        assertNotNull(ThemeRuntimeConfig.parseDraft(c.toString()));assertNotNull(ThemeRuntimeConfig.parse(c.toString()));
        f.putArray("effects").addObject().put("var","sys.hp").put("op","INC").put("value",3);invalid(c);
    }
    @Test void copyRemapsTaskAndOperationReferencesWithoutMovingOldConfig() throws Exception {
        ObjectNode c=config();game(c).putArray("plans").addObject().put("endingKey","leave").put("title","离开").put("operationNodeId",2);
        Map<Long,Long> nodes=new HashMap<>();nodes.put(1L,11L);nodes.put(2L,22L);
        JsonNode copy=json.readTree(ThemeRuntimeConfig.remapAll(c.toString(),nodes,Collections.emptyMap(),Collections.emptyMap()));
        assertEquals(11,copy.path("gameplayRuntime").path("quests").get(0).path("nodeId").asInt());assertEquals(22,copy.path("gameplayRuntime").path("plans").get(0).path("operationNodeId").asInt());assertEquals(1,quest(c,0).path("nodeId").asInt());
        assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.remapNodes(c.toString(),Collections.singletonMap(1L,11L)));
        assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.assertReferences(c,Collections.singleton(1L),Collections.emptySet()));
    }
    @Test void frozenContractAndDifferentContentPreserveCallerReferences() {
        ObjectNode winter=config();ObjectNode copy=ThemeRuntimeConfig.freeze(winter,0,1);game(winter).put("worldMode","TEAM_INDEPENDENT");assertEquals("WORLD_SHARED",GameplayRuntimeConfig.getWorldMode(copy));
        ObjectNode other=config();other.put("capacity",9).put("hpName","体力");game(other).put("runtimeScope","PLAYER").remove("worldMode");
        ((ObjectNode)other.path("items").get(0)).put("id","tool").put("name","工具").put("slotCost",3);
        ((ObjectNode)quest(other,0).path("choices").get(0).path("grants").get(0)).put("itemId","tool").put("quantity",1);
        assertNotNull(ThemeRuntimeConfig.parseDraft(other.toString()));assertEquals("PLAYER",GameplayRuntimeConfig.getRuntimeScope(other));assertEquals("体力",other.path("hpName").asText());
    }
    @Test void survivalAndSingleLocationMultiTaskStayExplicit() {
        ObjectNode c=config();assertEquals(quest(c,0).path("locationKey"),quest(c,1).path("locationKey"));assertNotEquals(quest(c,0).path("settlementSlot"),quest(c,1).path("settlementSlot"));
        ((ObjectNode)game(c).path("survival")).put("chapterRecovery",true);invalid(c);
        c=config();((ObjectNode)game(c).path("survival")).put("actionLimitPolicy","AUTO_FINISH");invalid(c);
    }
    @Test void survivalRequiresActualActAndItemReferences() {
        ObjectNode c=config();ObjectNode survival=game(c).with("survival");survival.putArray("rations").addObject().put("rationKey","meal").put("actKey","absent").put("itemId","fuel").put("quantity",1).put("fallbackHpCost",2);invalid(c);
        ((ObjectNode)survival.path("rations").get(0)).put("actKey","supplies").put("itemId","absent");invalid(c);
        ((ObjectNode)survival.path("rations").get(0)).put("itemId","fuel");assertNotNull(ThemeRuntimeConfig.parse(c.toString()));
        survival.putArray("recoveries").addObject().put("recoveryKey","rest").put("kind","REST").put("limit",2).put("hpDelta",2).putArray("actKeys").add("absent");invalid(c);
    }
    @Test void publicFactsAreExplicitCluesAndFallbackNodesCannotCrossTopics() {
        ObjectNode c=config();ObjectNode f=game(c).putArray("facilities").addObject().put("facilityKey","heat").put("actKey","supplies").put("scope","WORLD").put("targetScope","WORLD").put("settlementSlot","heat").put("limit",1);
        f.putArray("worldEffects").addObject().put("var","counter.secret").put("op","SET").put("value",1);invalid(c);
        ((ObjectNode)f.path("worldEffects").get(0)).put("var","clue.public");assertNotNull(ThemeRuntimeConfig.parse(c.toString()));
        quest(c,0).putArray("approvedFallbackNodeIds").add(99);assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.assertReferences(c,new HashSet<>(Arrays.asList(1L,2L)),Collections.singleton("clue.public")));
    }

}
