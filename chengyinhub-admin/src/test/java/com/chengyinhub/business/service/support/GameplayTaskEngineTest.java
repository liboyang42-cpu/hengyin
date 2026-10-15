package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.service.support.TopicRouteEngine.State;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class GameplayTaskEngineTest {
    private final ObjectMapper json = new ObjectMapper();
    private final GameplayTaskEngine tasks = new GameplayTaskEngine();
    private final ThemeRuntimeEngine inventory = new ThemeRuntimeEngine();
    private ObjectNode theme;
    private JourneyRules rules;
    private int sequence;

    @BeforeEach void setup() {
        rules = new JourneyRules();
        rules.setCheckEnabled(true);
        rules.getHp().setMax(12);
        theme = json.createObjectNode().put("schemaVersion", 1).put("capacity", 14).put("actionLimit", 8);
        theme.putArray("items").addObject().put("id", "fuel").put("name", "燃料").put("kind", "ITEM").put("slotCost", 2);
        theme.withArray("items").addObject().put("id", "tool").put("name", "工具").put("kind", "ITEM").put("slotCost", 1);
        ObjectNode game = theme.putObject("gameplayRuntime").put("runtimeScope", "PLAYER");
        game.putObject("survival").put("zeroHpPolicy", "BLOCK_PAID");
        ObjectNode act = game.putArray("acts").addObject().put("actKey", "supplies").put("hostUnlock", false);
        act.putArray("questKeys").add("north").add("east").add("west");
        act.putObject("completionRule").put("type", "ALL");
        ArrayNode quests = game.putArray("quests");
        String[] keys = {"north", "east", "west"};
        for (int i = 0; i < keys.length; i++) quests.addObject().put("questKey", keys[i]).put("actKey", "supplies")
                .put("nodeId", i + 1).put("locationKey", "gate").put("settlementSlot", keys[i]).put("hpCost", 1).put("actionCost", 1);
    }

    private ObjectNode game() { return (ObjectNode) theme.path("gameplayRuntime"); }
    private ObjectNode quest(int index) { return (ObjectNode) game().path("quests").get(index); }
    private ObjectNode act() { return (ObjectNode) game().path("acts").get(0); }
    private ArrayNode amounts(String id, int quantity) { ArrayNode rows = json.createArrayNode(); rows.addObject().put("itemId", id).put("quantity", quantity); return rows; }
    private State state() {
        State state = new State(); state.setStatus("ACTIVE");
        JourneyInitialState.initialize(rules, state);
        state.getVariables().put("sys.hp", 12); state.getVariables().put("sys.luck", 3);
        tasks.initialize(theme, rules, state, 1000);
        return state;
    }
    private ObjectNode call(State state, String quest, String action, String choice, String owner) {
        return tasks.execute(theme, rules, state, quest, action, choice, "event_" + (++sequence), owner, 2000 + sequence);
    }
    private ObjectNode call(State state, String quest, String action) { return call(state, quest, action, null, "MEMBER:1"); }
    private void verify(State state, int node) { state.getThemeRuntime().with("verifiedNodes").put(String.valueOf(node), 100 + node); }
    private void resolve(State state, int index) {
        String key = quest(index).path("questKey").asText(); call(state, key, "QUEST_START"); verify(state, index + 1); call(state, key, "QUEST_RESOLVE");
    }
    private JsonNode snapshot(State state) { return json.valueToTree(state); }
    private int held(State state, String id) { return state.getThemeRuntime().path("inventory").path(id).asInt(); }
    private ObjectNode chapter(int hpCost) {
        ObjectNode chapter = theme.putArray("chapters").addObject().put("index", 0).put("hpCost", hpCost).put("luckCost", 0);
        chapter.putObject("binding").putArray("nodeIds").add(1).add(2).add(3); return chapter;
    }
    private void links() {
        rules.setStateEnabled(true);
        rules.getAttributes().addObject().put("key", "counter.temperature").put("label", "储备").put("initial", 10).put("min", 0).put("max", 10).put("visible", false);
        ObjectNode link = rules.getStatLinks().addObject().put("id", "drain").put("sourceKey", "counter.temperature").put("targetKey", "sys.hp")
                .put("trigger", "PER_POINT").put("multiplier", 2).put("frequency", "EVENT");
        link.putArray("allowedReasons").add("TASK_COST").add("CHAPTER_COST");
    }
    @Test void pendingOutcomeChargesOnlyAtConfirmationAndKeepsUnpaidEffectsPending() {
        quest(0).remove(java.util.Arrays.asList("hpCost","actionCost"));
        State state=state();state.getVariables().put("sys.hp",6);
        ObjectNode frozen=json.createObjectNode();frozen.set("themeConfig",theme);
        ObjectNode after=frozen.putObject("nodeConfigs").putObject("1").putObject("outcomeRules").put("version",1).putObject("pass");
        after.putArray("costs").addObject().put("statKey","sys.hp").put("amount",3);
        after.putArray("effects").addObject().put("var","sys.hp").put("op","INC").put("value",2);
        call(state,"north","QUEST_START");
        new GameplayOutcomeEngine().remember(frozen,state,"advanced:1:1",json.createObjectNode().put("nodeId",1).put("outcome","PASS"));
        ObjectNode pending=state.getThemeRuntime().with("gameplay").with("pendingTaskEffects").with("north");
        pending.put("outcomeAttemptId","advanced:1:1");pending.putArray("effects");pending.putObject("sources");verify(state,1);
        assertEquals(6,state.getVariables().get("sys.hp"));state.getVariables().put("sys.hp",1);
        JsonNode blocked=call(state,"north","QUEST_RESOLVE");assertEquals("BLOCKED_COST",blocked.path("status").asText());assertEquals(1,state.getVariables().get("sys.hp"));assertEquals("north",state.getThemeRuntime().path("gameplay").path("activeQuest").asText());assertTrue(state.getThemeRuntime().path("gameplay").path("pendingTaskEffects").has("north"));assertFalse(state.getThemeRuntime().path("gameplay").path("slots").has("north"));
        state.getVariables().put("sys.hp",6);call(state,"north","QUEST_RESOLVE");assertEquals(5,state.getVariables().get("sys.hp"));
        assertFalse(state.getThemeRuntime().path("gameplay").path("pendingTaskEffects").has("north"));
        assertTrue(state.getThemeRuntime().path("lastOutcome").path("settled").asBoolean());
        call(state,"north","QUEST_RESOLVE");assertEquals(5,state.getVariables().get("sys.hp"));
    }
    @Test void saturatedPureRecoveryIncludesLegacyPendingEffectsWithoutCharging() {
        quest(0).remove(java.util.Arrays.asList("hpCost","actionCost"));
        State state=state();ObjectNode frozen=json.createObjectNode();frozen.set("themeConfig",theme);
        ObjectNode after=frozen.putObject("nodeConfigs").putObject("1").putObject("outcomeRules").put("version",1).putObject("pass");
        after.putArray("costs").addObject().put("statKey","sys.hp").put("amount",3);
        after.putArray("effects").addObject().put("var","sys.hp").put("op","INC").put("value",2);
        call(state,"north","QUEST_START");new GameplayOutcomeEngine().remember(frozen,state,"advanced:1:1",json.createObjectNode().put("nodeId",1).put("outcome","PASS"));
        ObjectNode pending=state.getThemeRuntime().with("gameplay").with("pendingTaskEffects").with("north").put("outcomeAttemptId","advanced:1:1");pending.putArray("effects").addObject().put("var","sys.hp").put("op","INC").put("value",1);pending.putObject("sources");verify(state,1);
        call(state,"north","QUEST_RESOLVE");assertEquals(12,state.getVariables().get("sys.hp"));assertFalse(state.getThemeRuntime().path("lastOutcome").path("consumed").asBoolean());
    }
    @Test void costOptionIsChosenAtStartAndCannotChangeBeforeOrAfterSettlement() {
        ObjectNode q=quest(0);q.remove("hpCost");q.remove("actionCost");
        ArrayNode options=q.putArray("costOptions");options.addObject().put("label","物资").set("resourceCosts",amounts("tool",1));options.addObject().put("label","数值").put("hpCost",2);
        State state=state();
        assertThrows(IllegalArgumentException.class,()->call(state,"north","QUEST_START"));
        assertTrue(assertThrows(IllegalStateException.class,()->tasks.execute(theme,rules,state,"north","QUEST_START",null,"start0","MEMBER:1",1,null,null,0)).getMessage().contains("缺少1"));
        tasks.execute(theme,rules,state,"north","QUEST_START",null,"start1","MEMBER:1",1,null,null,1);
        assertEquals(1,state.getThemeRuntime().path("gameplay").path("activeQuestCostOption").asInt());verify(state,1);
        assertThrows(IllegalStateException.class,()->tasks.execute(theme,rules,state,"north","QUEST_RESOLVE",null,"change","MEMBER:1",2,null,null,0));
        ObjectNode receipt=call(state,"north","QUEST_RESOLVE");assertEquals(1,receipt.path("costOption").asInt());assertEquals(10,state.getVariables().get("sys.hp"));
        assertEquals(receipt,call(state,"north","QUEST_RESOLVE"));
        assertThrows(IllegalStateException.class,()->tasks.execute(theme,rules,state,"north","QUEST_RESOLVE",null,"changeLater","MEMBER:1",3,null,null,0));
        assertThrows(IllegalArgumentException.class,()->tasks.execute(theme,rules,state,"east","QUEST_START",null,"extra","MEMBER:1",3,null,null,0));
    }



    @Test void costOptionShortfallKeepsExceptionTypeAndReportsExactMissingQuantity() {
        ObjectNode q=quest(0);q.remove("hpCost");q.remove("actionCost");
        ArrayNode options=q.putArray("costOptions");options.addObject().set("resourceCosts",amounts("tool",3));options.addObject().put("hpCost",2);
        State state=state();JsonNode before=snapshot(state);
        IllegalStateException missing=assertThrows(IllegalStateException.class,()->tasks.execute(theme,rules,state,"north","QUEST_START",null,"shortfall","MEMBER:1",1,null,null,0));
        assertEquals("材料不足：tool，缺少3",missing.getMessage());assertEquals(before,snapshot(state));
    }

    @Test void finalTaskAtZeroWaitsForChallengeAndChallengeNodesDoNotBlockChapter() {
        game().with("survival").put("zeroHpPolicy","CHALLENGE");
        game().with("survival").putObject("zeroChallenge").put("challengeNodeId",101).put("restoreTo",4).put("limit",1);
        act().putObject("completionRule").put("type","ANY_N").put("quantity",1);quest(0).put("hpCost",12);
        ObjectNode chapter=chapter(0);chapter.with("binding").withArray("nodeIds").add(101);
        State state=state();call(state,"north","QUEST_START");verify(state,1);call(state,"north","QUEST_RESOLVE");
        assertEquals(0,state.getVariables().get("sys.hp"));assertEquals("ACTIVE",state.getStatus());assertFalse(state.getThemeRuntime().has("result"));
        assertTrue(state.getThemeRuntime().path("chapters").path("0").path("ended").asBoolean());assertFalse(state.getCompletedNodeIds().contains(101L));
        ObjectNode snapshot=json.createObjectNode();snapshot.set("themeConfig",theme);GameplaySurvivalEngine survival=new GameplaySurvivalEngine();survival.syncChallenge(snapshot,rules,state,5000);
        assertTrue(state.getThemeRuntime().path("awaitingChallenge").asBoolean());
        survival.submitChallenge(snapshot,rules,state,1L,survival.challengeView(state).path("instanceId").asText(),101L,"objective-pass",true,null,false);
        survival.syncChallenge(snapshot,rules,state,6000);assertEquals("COMPLETED",state.getStatus());assertTrue(state.getThemeRuntime().has("result"));
        assertEquals(4,state.getVariables().get("sys.hp"));assertEquals(1,state.getThemeRuntime().path("zeroChallenges").path("used").asInt());
    }
    @Test void playerAndIndependentTeamsSharePrecheckAndSettleOnlyAfterTrustedSuccess() {
        for (String scope : new String[]{"PLAYER", "TEAM"}) {
            game().put("runtimeScope", scope);
            if ("TEAM".equals(scope)) game().put("worldMode", "TEAM_INDEPENDENT");
            String owner = "TEAM".equals(scope) ? "TEAM:1" : "MEMBER:1";
            State state = state();
            call(state, "north", "QUEST_START", null, owner);
            assertEquals(12, state.getVariables().get("sys.hp")); assertEquals(0, state.getVariables().get("sys.actions"));
            JsonNode before = snapshot(state);
            assertThrows(IllegalStateException.class, () -> call(state, "north", "QUEST_RESOLVE", null, owner));
            assertEquals(before, snapshot(state));
            verify(state, 1); ObjectNode result = call(state, "north", "QUEST_RESOLVE", null, owner);
            assertEquals(11, state.getVariables().get("sys.hp")); assertEquals(1, state.getVariables().get("sys.actions"));
            before = snapshot(state);
            assertEquals(result, call(state, "north", "QUEST_RESOLVE", null, owner));
            assertEquals(before, snapshot(state));
        }
    }

    @Test void cancellationReleasesActiveTaskWithoutChargingOrCompleting() {
        State state = state(); JsonNode variables = json.valueToTree(state.getVariables());
        call(state, "north", "QUEST_START");
        assertThrows(IllegalStateException.class, () -> call(state, "east", "QUEST_START"));
        call(state, "north", "QUEST_CANCEL");
        assertEquals(variables, json.valueToTree(state.getVariables())); assertTrue(state.getCompletedNodeIds().isEmpty());
        assertFalse(state.getThemeRuntime().path("gameplay").has("activeQuest"));
        assertFalse(state.getThemeRuntime().path("gameplay").path("slots").has("north"));
        assertDoesNotThrow(() -> call(state, "east", "QUEST_START"));
    }

    @Test void insufficientMaterialOrHpRejectsStartWithoutWritingPreviewSettlement() {
        quest(0).set("resourceCosts", amounts("tool", 1));
        State state = state(); JsonNode before = snapshot(state);
        assertThrows(IllegalStateException.class, () -> call(state, "north", "QUEST_START")); assertEquals(before, snapshot(state));
        inventory.grantAll(theme, state, rules, "tools", amounts("tool", 1));
        quest(0).put("hpCost", 3); state.getVariables().put("sys.hp", 2); before = snapshot(state);
        assertThrows(IllegalStateException.class, () -> call(state, "north", "QUEST_START")); assertEquals(before, snapshot(state));
    }

    @Test void falseNullAndUnrelatedTrustedMarkersCannotResolveTask() {
        State state = state(); call(state, "north", "QUEST_START"); verify(state, 2);
        ObjectNode markers = state.getThemeRuntime().with("verifiedNodes");
        for (JsonNode invalid : new JsonNode[]{json.getNodeFactory().booleanNode(false), json.getNodeFactory().nullNode()}) {
            markers.set("1", invalid); JsonNode before = snapshot(state);
            assertThrows(IllegalStateException.class, () -> call(state, "north", "QUEST_RESOLVE")); assertEquals(before, snapshot(state));
        }
    }

    @Test void selectedPackageIsWholeAndCannotChangeAfterSettlement() {
        ObjectNode choice = quest(0).putArray("choices").addObject().put("choiceKey", "pack");
        choice.set("grants", amounts("fuel", 2)); choice.withArray("grants").addObject().put("itemId", "tool").put("quantity", 1);
        State state = state(); call(state, "north", "QUEST_START"); verify(state, 1); JsonNode before = snapshot(state);
        assertThrows(IllegalArgumentException.class, () -> call(state, "north", "QUEST_RESOLVE")); assertEquals(before, snapshot(state));
        ObjectNode result = call(state, "north", "QUEST_RESOLVE", "pack", "MEMBER:1");
        assertEquals(2, held(state, "fuel")); assertEquals(1, held(state, "tool")); assertEquals(5, inventory.used(theme, state));
        before = snapshot(state); assertEquals(result, call(state, "north", "QUEST_RESOLVE", "pack", "MEMBER:1"));
        assertThrows(IllegalStateException.class, () -> call(state, "north", "QUEST_RESOLVE", "other", "MEMBER:1")); assertEquals(before, snapshot(state));
    }

    @Test void overweightPackageRollsBackCostActionsAndAllRewardRows() {
        theme.put("capacity", 3); quest(0).set("grants", amounts("fuel", 2)); quest(0).withArray("grants").addObject().put("itemId", "tool").put("quantity", 1);
        State state = state(); call(state, "north", "QUEST_START"); verify(state, 1); JsonNode before = snapshot(state);
        assertThrows(IllegalStateException.class, () -> call(state, "north", "QUEST_RESOLVE")); assertEquals(before, snapshot(state));
        assertEquals(0, held(state, "fuel")); assertEquals(0, held(state, "tool")); assertFalse(state.getThemeRuntime().path("gameplay").path("slots").has("north"));
    }

    @Test void prerequisitesExclusiveChoicesAndExtraSlotsAreDistinctPersistentGuards() {
        act().putObject("completionRule").put("type", "ANY_N").put("quantity", 2);
        quest(1).putArray("prerequisites").add("north"); quest(0).put("exclusiveGroup", "route"); quest(2).put("exclusiveGroup", "route");
        State state = state(); assertThrows(IllegalStateException.class, () -> call(state, "east", "QUEST_START"));
        resolve(state, 0); assertThrows(IllegalStateException.class, () -> call(state, "west", "QUEST_START")); assertDoesNotThrow(() -> call(state, "east", "QUEST_START"));
        quest(0).remove("exclusiveGroup"); quest(2).remove("exclusiveGroup"); quest(1).remove("prerequisites");
        act().putObject("completionRule").put("type", "ALL");
        quest(0).put("extraSlot", "flex"); quest(2).put("extraSlot", "flex");
        State other = state(); resolve(other, 0); assertThrows(IllegalStateException.class, () -> call(other, "west", "QUEST_START"));
    }

    @Test void anyTwoOfThreeEndsMatchingChapterAndChargesChapterOnlyOnce() {
        act().putObject("completionRule").put("type", "ANY_N").put("quantity", 2);
        ObjectNode chapter = chapter(2);
        chapter.putArray("grants").addObject().put("itemId", "fuel").put("quantity", 1).put("timing", "START");
        chapter.withArray("grants").addObject().put("itemId", "tool").put("quantity", 1).put("timing", "END");
        State state = state(); assertEquals(1, held(state, "fuel"));
        resolve(state, 0); assertEquals(11, state.getVariables().get("sys.hp")); assertFalse(state.getThemeRuntime().path("chapters").path("0").path("ended").asBoolean());
        resolve(state, 1); assertEquals(8, state.getVariables().get("sys.hp")); assertEquals(2, state.getVariables().get("sys.actions"));
        assertEquals(1, held(state, "tool")); assertEquals("COMPLETED", state.getStatus()); assertFalse(state.getCompletedNodeIds().contains(3L));
        JsonNode before = snapshot(state); call(state, "east", "QUEST_RESOLVE"); assertEquals(before, snapshot(state));
    }

    @Test void actUnlockAndIndependentChapterBindingCannotPrematurelyCharge() {
        act().put("hostUnlock", true); chapter(2);
        State state = state(); assertThrows(IllegalStateException.class, () -> call(state, "north", "QUEST_START"));
        state.getThemeRuntime().with("gameplay").put("awaitingUnlock", false); resolve(state, 0);
        assertEquals(11, state.getVariables().get("sys.hp")); assertFalse(state.getThemeRuntime().path("chapters").path("0").path("ended").asBoolean());
    }

    @Test void actRationRunsOnceAndUsesMaterialBeforeHpFallback() {
        act().putObject("completionRule").put("type", "ANY_N").put("quantity", 1);
        game().putArray("initialGrants").addObject().put("itemId", "tool").put("quantity", 1);
        game().with("survival").putArray("rations").addObject().put("rationKey", "supply_end").put("actKey", "supplies").put("itemId", "tool").put("quantity", 1).put("fallbackHpCost", 2);
        State state = state(); resolve(state, 0); assertEquals(0, held(state, "tool")); assertEquals(11, state.getVariables().get("sys.hp"));
        JsonNode before = snapshot(state); call(state, "north", "QUEST_RESOLVE"); assertEquals(before, snapshot(state));
        game().remove("initialGrants"); State hungry = state(); resolve(hungry, 0); assertEquals(9, hungry.getVariables().get("sys.hp"));
    }

    @Test void linkedAttributeUsesFinalNetDeclineRatherThanIntermediateCost() {
        links(); quest(0).put("hpCost", 0);
        quest(0).putArray("statCosts").addObject().put("statKey", "counter.temperature").put("amount", 4);
        theme.withArray("items").addObject().put("id", "coat").put("kind", "ITEM").put("wear", true).put("mode", "ONCE").put("statKey", "counter.temperature").put("delta", 2).put("slotCost", 1);
        quest(0).set("grants", amounts("coat", 1));
        State state = state(); resolve(state, 0);
        assertEquals(8, state.getVariables().get("counter.temperature")); assertEquals(8, state.getVariables().get("sys.hp"));
        assertEquals(1, state.getThemeRuntime().path("statLinks").path("audit").size());
        assertEquals(4, state.getThemeRuntime().path("statLinks").path("audit").get(0).path("actualDamage").asInt());
        JsonNode before = snapshot(state); call(state, "north", "QUEST_RESOLVE"); assertEquals(before, snapshot(state));
    }

    @Test void taskAndChapterAttributeCostsProduceOneLinkedNetEvent() {
        links(); act().putObject("completionRule").put("type", "ANY_N").put("quantity", 1); quest(0).put("hpCost", 0);
        quest(0).putArray("statCosts").addObject().put("statKey", "counter.temperature").put("amount", 1);
        chapter(0).putArray("statCosts").addObject().put("statKey", "counter.temperature").put("amount", 1);
        State state = state(); resolve(state, 0);
        assertEquals(8, state.getVariables().get("counter.temperature")); assertEquals(8, state.getVariables().get("sys.hp"));
        JsonNode audit = state.getThemeRuntime().path("statLinks").path("audit"); assertEquals(1, audit.size()); assertEquals("MULTIPLE", audit.get(0).path("reason").asText());
    }

    @Test void rationRemovingHeldAttributeModifierJoinsTaskNetLinkOnlyOnce() {
        links(); ((ObjectNode) rules.getAttributes().get(0)).put("initial", 8);
        act().putObject("completionRule").put("type", "ANY_N").put("quantity", 1); quest(0).put("hpCost", 0);
        quest(0).putArray("statCosts").addObject().put("statKey", "counter.temperature").put("amount", 1);
        theme.withArray("items").addObject().put("id", "coat").put("kind", "ITEM").put("wear", true).put("mode", "HELD").put("statKey", "counter.temperature").put("delta", 2).put("slotCost", 1);
        game().set("initialGrants", amounts("coat", 1));
        game().with("survival").putArray("rations").addObject().put("rationKey", "supplies_end").put("actKey", "supplies").put("itemId", "coat").put("quantity", 1).put("fallbackHpCost", 0);
        State state = state(); assertEquals(10, state.getVariables().get("counter.temperature")); resolve(state, 0);
        assertEquals(7, state.getVariables().get("counter.temperature")); assertEquals(6, state.getVariables().get("sys.hp"));
        assertEquals(1, state.getThemeRuntime().path("statLinks").path("audit").size());
    }

    @Test void hZeroMayUseConfiguredNoHpCostTaskButLegacyBlockPaidStillBlocksIt() {
        game().with("survival").put("zeroHpPolicy", "BLOCK_HP_COST"); quest(0).put("hpCost", 0);
        State state = state(); state.getVariables().put("sys.hp", 0); resolve(state, 0);
        assertEquals(0, state.getVariables().get("sys.hp")); assertEquals(1, state.getVariables().get("sys.actions")); assertEquals("ACTIVE", state.getStatus());
        game().with("survival").put("zeroHpPolicy", "BLOCK_PAID"); State legacy = state(); legacy.getVariables().put("sys.hp", 0);
        JsonNode before = snapshot(legacy); assertThrows(IllegalStateException.class, () -> call(legacy, "north", "QUEST_START")); assertEquals(before, snapshot(legacy));
    }

    @Test void eighthActionCanCompleteAndNinthRejectsWithoutImplicitEnding() {
        State state = state(); state.getVariables().put("sys.actions", 7); resolve(state, 0);
        assertEquals(8, state.getVariables().get("sys.actions")); assertEquals("ACTIVE", state.getStatus()); assertFalse(state.getThemeRuntime().has("result"));
        JsonNode before = snapshot(state); assertThrows(IllegalStateException.class, () -> call(state, "east", "QUEST_START")); assertEquals(before, snapshot(state));
        quest(1).put("hpCost", 0).put("actionCost", 0); assertDoesNotThrow(() -> resolve(state, 1)); assertEquals(8, state.getVariables().get("sys.actions"));
    }

    @Test void frozenInitialPackageIsIndependentFromDraftAndCannotBeRegrantedOnReload() {
        game().putArray("initialGrants").addObject().put("itemId", "fuel").put("quantity", 2);
        ObjectNode frozen = ThemeRuntimeConfig.freeze(theme, 0, 1000);
        ((ObjectNode) game().path("initialGrants").get(0)).put("quantity", 6); theme = frozen;
        State state = state(); assertEquals(2, held(state, "fuel"));
        inventory.consumeAll(theme, state, rules, "other", amounts("fuel", 1));
        tasks.initialize(theme, rules, state, 9999); assertEquals(1, held(state, "fuel")); assertEquals(2, state.getThemeRuntime().path("acquired").path("fuel").asInt());
    }

    @Test void worldTaskCompletionDoesNotCreatePrivateOrGlobalEnding() {
        game().put("runtimeScope", "TEAM").put("worldMode", "WORLD_SHARED");
        act().putObject("completionRule").put("type", "ANY_N").put("quantity", 1);
        State state = state(); call(state, "north", "QUEST_START", null, "TEAM:1"); verify(state, 1); call(state, "north", "QUEST_RESOLVE", null, "TEAM:1");
        assertFalse(state.getThemeRuntime().has("result")); assertFalse(state.getThemeRuntime().path("cooperation").has("result"));
    }

    @Test void anyNThirdStationUsesExtraSlotWithoutRepeatingChapterOrAdvancingNextAct() {
        act().putObject("completionRule").put("type", "ANY_N").put("quantity", 2);
        for (JsonNode quest : game().path("quests")) ((ObjectNode) quest).put("extraSlot", "flex");
        ObjectNode nextAct = game().withArray("acts").addObject().put("actKey", "finale").put("hostUnlock", false);
        nextAct.putArray("questKeys").add("final"); nextAct.putObject("completionRule").put("type", "ALL");
        game().withArray("quests").addObject().put("questKey", "final").put("actKey", "finale").put("nodeId", 4).put("settlementSlot", "final").put("locationKey", "gate");
        chapter(2); State state = state(); resolve(state, 0); resolve(state, 1);
        assertFalse(state.getThemeRuntime().path("gameplay").path("extraSlots").has("flex"));
        assertEquals(1, state.getThemeRuntime().path("gameplay").path("actIndex").asInt());
        resolve(state, 2); assertEquals(7, state.getVariables().get("sys.hp")); assertEquals(3, state.getVariables().get("sys.actions"));
        assertEquals("west", state.getThemeRuntime().path("gameplay").path("extraSlots").path("flex").asText());
        assertEquals(1, state.getThemeRuntime().path("gameplay").path("actIndex").asInt()); assertEquals("ACTIVE", state.getStatus());
        assertFalse(state.getCompletedNodeIds().contains(4L)); assertEquals(4L, state.getCurrentNodeId());
    }

    @Test void overCapacityPreviewIsReadOnlyAndChosenDiscardNeverDeletesExistingInventory() {
        theme.put("capacity", 4); game().set("initialGrants", amounts("fuel", 1));
        quest(0).set("grants", amounts("fuel", 2)); quest(0).withArray("grants").addObject().put("itemId", "tool").put("quantity", 1);
        State state = state(); call(state, "north", "QUEST_START"); JsonNode before = snapshot(state);
        ObjectNode tooHeavy = tasks.preview(theme, rules, state, "north", null, null, "MEMBER:1");
        assertEquals(7, tooHeavy.path("finalUsed").asInt()); assertTrue(tooHeavy.path("overflow").asBoolean()); assertEquals(before, snapshot(state));
        ArrayNode discard = amounts("fuel", 1); discard.addObject().put("itemId", "tool").put("quantity", 1);
        ObjectNode preview = tasks.preview(theme, rules, state, "north", null, discard, "MEMBER:1");
        assertEquals(4, preview.path("finalUsed").asInt()); assertFalse(preview.path("overflow").asBoolean()); assertEquals(before, snapshot(state));
        verify(state, 1);
        ObjectNode result = tasks.execute(theme, rules, state, "north", "QUEST_RESOLVE", null, "claim", "MEMBER:1", 5000, discard, preview.path("previewVersion").asText());
        assertEquals(2, held(state, "fuel")); assertEquals(0, held(state, "tool")); assertEquals(discard, result.path("discardRewards"));
        assertEquals(2, state.getThemeRuntime().path("acquired").path("fuel").asInt());
        before = snapshot(state); assertEquals(result, tasks.execute(theme, rules, state, "north", "QUEST_RESOLVE", null, "retry", "MEMBER:1", 5001, discard, preview.path("previewVersion").asText())); assertEquals(before, snapshot(state));
    }

    @Test void discardRejectsOldInventoryOverclaimsDuplicatesAndMissingOrStaleOwnerBoundPreview() {
        quest(0).set("grants", amounts("fuel", 2)); State state = state(); call(state, "north", "QUEST_START"); verify(state, 1);
        ArrayNode discard = amounts("fuel", 1); JsonNode before = snapshot(state);
        assertThrows(IllegalStateException.class, () -> tasks.execute(theme, rules, state, "north", "QUEST_RESOLVE", null, "missing", "MEMBER:1", 5000, discard, null));
        assertThrows(IllegalArgumentException.class, () -> tasks.preview(theme, rules, state, "north", null, amounts("tool", 1), "MEMBER:1"));
        assertThrows(IllegalArgumentException.class, () -> tasks.preview(theme, rules, state, "north", null, amounts("fuel", 3), "MEMBER:1"));
        ArrayNode duplicate = amounts("fuel", 1); duplicate.add(duplicate.get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> tasks.preview(theme, rules, state, "north", null, duplicate, "MEMBER:1"));
        String wrongOwner = tasks.preview(theme, rules, state, "north", null, discard, "MEMBER:2").path("previewVersion").asText();
        assertThrows(IllegalStateException.class, () -> tasks.execute(theme, rules, state, "north", "QUEST_RESOLVE", null, "other_owner", "MEMBER:1", 5000, discard, wrongOwner)); assertEquals(before, snapshot(state));
        String stale = tasks.preview(theme, rules, state, "north", null, discard, "MEMBER:1").path("previewVersion").asText();
        inventory.grantAll(theme, state, rules, "other", amounts("tool", 1)); before = snapshot(state);
        assertThrows(IllegalStateException.class, () -> tasks.execute(theme, rules, state, "north", "QUEST_RESOLVE", null, "stale", "MEMBER:1", 5000, discard, stale)); assertEquals(before, snapshot(state));
    }

    @Test void externalNetSettlementDelaysLinksAndTerminalSnapshot() {
        links(); act().putObject("completionRule").put("type", "ANY_N").put("quantity", 1); quest(0).put("hpCost", 0);
        quest(0).putArray("statCosts").addObject().put("statKey", "counter.temperature").put("amount", 1);
        State state = state(); call(state, "north", "QUEST_START"); verify(state, 1); State before = state.copy();
        Map<String, Set<JourneyStatLinks.Reason>> reasons = new LinkedHashMap<>();
        tasks.execute(theme, rules, state, "north", "QUEST_RESOLVE", null, "combined", "MEMBER:1", 5000, reasons, false);
        assertEquals(12, state.getVariables().get("sys.hp")); assertEquals(9, state.getVariables().get("counter.temperature"));
        assertFalse(state.getThemeRuntime().has("result")); assertEquals("COMPLETED", state.getStatus());
        JourneyStatLinks.apply(rules, before, state, "MEMBER:1", "combined", reasons); inventory.terminal(theme, state, rules, "COMPLETED", 5000);
        assertEquals(10, state.getThemeRuntime().path("result").path("hp").asInt()); assertEquals(1, state.getThemeRuntime().path("statLinks").path("audit").size());
    }

    @Test void journalCapacityRejectsNewWritesAndLateOverflowWithoutPartialSettlement() {
        State state = state(); for (int i = 0; i < 4095; i++) state.getThemeRuntime().withArray("journal").addObject();
        call(state, "north", "QUEST_START"); assertEquals(4096, state.getThemeRuntime().path("journal").size()); JsonNode before = snapshot(state);
        assertThrows(IllegalStateException.class, () -> call(state, "north", "QUEST_CANCEL")); assertEquals(before, snapshot(state));
        State other = state(); call(other, "north", "QUEST_START"); verify(other, 1); quest(0).set("grants", amounts("tool", 1));
        while (other.getThemeRuntime().path("journal").size() < 4095) other.getThemeRuntime().withArray("journal").addObject();
        before = snapshot(other); assertThrows(IllegalStateException.class, () -> call(other, "north", "QUEST_RESOLVE")); assertEquals(before, snapshot(other)); assertEquals(0, held(other, "tool"));
    }

    @Test void jointFacilityPreviewCountsCostsBeforeAnyRewardsAndDoesNotDoubleChargeSettledFacility() {
        quest(0).put("facilityKey", "exchange"); quest(0).set("grants", amounts("tool", 2)); theme.put("capacity", 4);
        game().set("initialGrants", amounts("fuel", 2));
        ObjectNode facility = game().putArray("facilities").addObject().put("facilityKey", "exchange").put("actKey", "supplies").put("targetScope", "OWNER").put("scope", "WORLD").put("settlementSlot", "exchange").put("limit", 1);
        facility.set("resourceCosts", amounts("fuel", 1));
        State state = state(); JsonNode before = snapshot(state); ObjectNode preview = tasks.preview(theme, rules, state, "north", null, null, "MEMBER:1");
        assertEquals(4, preview.path("finalUsed").asInt()); assertFalse(preview.path("overflow").asBoolean()); assertEquals(before, snapshot(state));
        assertEquals(9,tasks.preview(theme,rules,state,"north",null,null,"MEMBER:9").path("facilityResult").path("paidByTeamId").asInt());
        inventory.consumeAll(theme, state, rules, "facility_done", amounts("fuel", 1)); state.getThemeRuntime().with("gameplay").put("facilitySettled", "north");
        assertEquals(4, tasks.preview(theme, rules, state, "north", null, null, "MEMBER:1").path("finalUsed").asInt());
    }

    @Test void trustedTaskFactsTriggerConfiguredEventsOnceWithinSameNetSettlement() {
        links(); ((ObjectNode) rules.getStatLinks().get(0)).withArray("allowedReasons").add("TEMPLATE_EFFECT");
        quest(0).put("hpCost", 0).putArray("statCosts").addObject().put("statKey", "counter.temperature").put("amount", 4);
        ObjectNode event = theme.putArray("events").addObject().put("id", "verified_north").put("content", "已核验事实");
        event.putArray("when").addObject().put("op", "EQ").put("var", "sys.passed.1").put("value", 1);
        event.putArray("effects").addObject().put("var", "counter.temperature").put("op", "INC").put("value", 2);
        State state = state(); call(state, "north", "QUEST_START");
        assertEquals(0, state.getThemeRuntime().path("events").size()); verify(state, 1); call(state, "north", "QUEST_RESOLVE");
        assertEquals(1, state.getVariables().get("sys.passed.1")); assertEquals(8, state.getVariables().get("counter.temperature")); assertEquals(8, state.getVariables().get("sys.hp"));
        assertEquals(1, state.getThemeRuntime().path("events").size()); resolve(state, 1);
        assertEquals(1, state.getThemeRuntime().path("events").size()); assertEquals(8, state.getVariables().get("counter.temperature"));
    }

    @Test void lastChapterRewardMustSettleBeforeCompletionAndReplayIsIdempotent() {
        theme.put("capacity",2);game().set("initialGrants",amounts("fuel",1));
        act().putObject("completionRule").put("type","ANY_N").put("quantity",1);
        chapter(0).putArray("grants").addObject().put("timing","END").put("itemId","tool").put("quantity",1);
        for(String scope:new String[]{"PLAYER","TEAM"}) {
            game().put("runtimeScope",scope);if("TEAM".equals(scope))game().put("worldMode","TEAM_INDEPENDENT");
            State state=state();call(state,"north","QUEST_START");verify(state,1);
            JsonNode before=snapshot(state);ObjectNode preview=tasks.preview(theme,rules,state,"north",null,null,"MEMBER:1",null,3000);
            assertEquals(1,preview.path("grants").path("tool").asInt());assertEquals(3,preview.path("finalUsed").asInt());assertTrue(preview.path("overflow").asBoolean());assertEquals(before,snapshot(state));
            assertThrows(IllegalStateException.class,()->call(state,"north","QUEST_RESOLVE"));assertEquals(before,snapshot(state));
            ArrayNode dropped=amounts("tool",1);preview=tasks.preview(theme,rules,state,"north",null,dropped,"MEMBER:1",null,3000);
            assertFalse(preview.path("overflow").asBoolean());String token=preview.path("previewVersion").asText();
            ObjectNode receipt=tasks.execute(theme,rules,state,"north","QUEST_RESOLVE",null,"finish","MEMBER:1",3000,dropped,token);
            assertEquals("COMPLETED",state.getStatus());assertTrue(state.getThemeRuntime().path("chapters").path("0").path("ended").asBoolean());assertTrue(state.getThemeRuntime().path("pending").isEmpty());
            assertEquals(0,held(state,"tool"));before=snapshot(state);
            assertEquals(receipt,tasks.execute(theme,rules,state,"north","QUEST_RESOLVE",null,"replay","MEMBER:1",3000,dropped,token));assertEquals(before,snapshot(state));
        }
    }
    @Test void previewIncludesTaskEndStartAndEventRewardsAndSelectionInvalidatesIt() {
        theme.put("capacity",20);quest(0).set("grants",amounts("tool",1));act().putObject("completionRule").put("type","ANY_N").put("quantity",1);
        chapter(0).putArray("grants").addObject().put("timing","END").put("itemId","tool").put("quantity",2);
        ObjectNode next=game().withArray("acts").addObject().put("actKey","next");next.putArray("questKeys").add("last");next.putObject("completionRule").put("type","ALL");
        game().withArray("quests").addObject().put("questKey","last").put("actKey","next").put("nodeId",4).put("settlementSlot","last");
        ObjectNode ch=theme.withArray("chapters").addObject().put("index",1);ch.putObject("binding").putArray("nodeIds").add(4);ch.putArray("grants").addObject().put("timing","START").put("itemId","tool").put("quantity",3);
        ObjectNode event=theme.putArray("events").addObject().put("id","passed").put("content","通过后发放");event.putArray("when").addObject().put("op","EQ").put("var","sys.passed.1").put("value",1);event.set("grants",amounts("tool",4));
        State state=state();call(state,"north","QUEST_START");verify(state,1);ObjectNode preview=tasks.preview(theme,rules,state,"north",null,null,"MEMBER:1",null,3000);
        assertEquals(10,preview.path("grants").path("tool").asInt());assertEquals(4,preview.path("rewardSources").size());
        ObjectNode discard=tasks.preview(theme,rules,state,"north",null,amounts("tool",1),"MEMBER:1",null,3000);assertNotEquals(preview.path("previewVersion"),discard.path("previewVersion"));
        inventory.grantAll(theme,state,rules,"other",amounts("fuel",1));assertNotEquals(preview.path("previewVersion"),tasks.preview(theme,rules,state,"north",null,null,"MEMBER:1",null,3000).path("previewVersion"));
        call(state,"north","QUEST_RESOLVE");assertEquals(10,held(state,"tool"));assertEquals(1,state.getThemeRuntime().path("gameplay").path("actIndex").asInt());
    }

    private void pending(State state, int amount) {
        ObjectNode record=state.getThemeRuntime().with("gameplay").with("pendingTaskEffects").putObject("north");
        record.putObject("sources").put("instance:trusted-choice",true);
        record.putArray("effects").addObject().put("var","counter.temperature").put("op","INC").put("value",amount);
    }

    @Test void pendingTrustedEffectsRestoreAfterCostsAndLinkOnlyNetDecline() {
        links();((ObjectNode)rules.getStatLinks().get(0)).withArray("allowedReasons").add("TEMPLATE_EFFECT");
        quest(0).put("hpCost",0).putArray("statCosts").addObject().put("statKey","counter.temperature").put("amount",4);
        State state=state();call(state,"north","QUEST_START");pending(state,2);verify(state,1);
        JsonNode before=snapshot(state);tasks.preview(theme,rules,state,"north",null,null,"MEMBER:1");assertEquals(before,snapshot(state));
        call(state,"north","QUEST_START");assertTrue(state.getThemeRuntime().path("gameplay").path("pendingTaskEffects").has("north"));
        ObjectNode receipt=call(state,"north","QUEST_RESOLVE");assertEquals(8,state.getVariables().get("counter.temperature"));assertEquals(8,state.getVariables().get("sys.hp"));
        assertFalse(state.getThemeRuntime().path("gameplay").path("pendingTaskEffects").has("north"));
        before=snapshot(state);assertEquals(receipt,call(state,"north","QUEST_RESOLVE"));assertEquals(before,snapshot(state));
    }

    @Test void pendingRestorationCannotFinanceCostsAndCancellationInvalidatesOldProof() {
        links();quest(0).put("hpCost",0).putArray("statCosts").addObject().put("statKey","counter.temperature").put("amount",4);
        State state=state();call(state,"north","QUEST_START");pending(state,8);verify(state,1);state.getVariables().put("counter.temperature",3);
        JsonNode before=snapshot(state);assertThrows(IllegalStateException.class,()->call(state,"north","QUEST_RESOLVE"));assertEquals(before,snapshot(state));
        call(state,"north","QUEST_CANCEL");assertFalse(state.getThemeRuntime().path("gameplay").path("pendingTaskEffects").has("north"));
        assertFalse(state.getThemeRuntime().path("verifiedNodes").has("1"));state.getVariables().put("counter.temperature",10);
        call(state,"north","QUEST_START");assertThrows(IllegalStateException.class,()->call(state,"north","QUEST_RESOLVE"));verify(state,1);call(state,"north","QUEST_RESOLVE");
        assertEquals(6,state.getVariables().get("counter.temperature"));assertEquals(4,state.getVariables().get("sys.hp"));
    }
}
