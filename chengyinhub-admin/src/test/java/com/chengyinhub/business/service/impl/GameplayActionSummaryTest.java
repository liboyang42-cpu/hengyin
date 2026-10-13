package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.service.support.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GameplayActionSummaryTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void participantFieldsAreFacilityOnlyAndSharedSerializersHaveTheSameShape() throws Exception {
        ObjectNode theme=(ObjectNode)json.readTree("{\"capacity\":8,\"gameplayRuntime\":{\"facilities\":[{\"facilityKey\":\"gather\",\"scope\":\"WORLD\",\"targetScope\":\"PARTICIPANTS\",\"settlementSlot\":\"gather\",\"limit\":1}],\"plans\":[{\"endingKey\":\"plan\",\"materialCost\":[]}]}}");
        JourneyRules rules=new JourneyRules();TopicRouteEngine.State owner=new TopicRouteEngine.State(),world=new TopicRouteEngine.State();
        JsonNode personal=TopicRouteRuntimeServiceImpl.gameplayActionSummary(theme,rules,owner,null,"FACILITY_USE","gather");
        JsonNode team=TopicRouteRuntimeServiceImpl.gameplayActionSummary(theme,rules,owner,world,"FACILITY_USE","gather");
        assertEquals(personal,team);assertTrue(personal.path("participantCatalog").isArray());assertTrue(personal.path("participantCatalog").isEmpty());assertTrue(personal.path("payerRequired").asBoolean());
        for(String target:java.util.Arrays.asList("OWNER","WORLD")) {
            ((ObjectNode)theme.path("gameplayRuntime").path("facilities").get(0)).put("targetScope",target);
            for(TopicRouteEngine.State countWorld:java.util.Arrays.asList(null,world)) {
                JsonNode summary=TopicRouteRuntimeServiceImpl.gameplayActionSummary(theme,rules,owner,countWorld,"FACILITY_USE","gather");
                assertFalse(summary.has("participantCatalog"));assertFalse(summary.has("payerRequired"));
            }
        }
        JsonNode plan=TopicRouteRuntimeServiceImpl.gameplayActionSummary(theme,rules,owner,world,"PLAN_EXECUTE","plan");
        assertEquals("PARTICIPANTS",plan.path("targetScope").asText());assertFalse(plan.has("participantCatalog"));assertFalse(plan.has("payerRequired"));
    }
    @Test void fullOwnerRecoveryDoesNotRequireItsConfiguredPayment() throws Exception {
        ObjectNode theme=(ObjectNode)json.readTree("{\"capacity\":8,\"items\":[{\"id\":\"food\",\"kind\":\"ITEM\"}],\"gameplayRuntime\":{\"facilities\":[{\"facilityKey\":\"rest\",\"scope\":\"OWNER\",\"targetScope\":\"OWNER\",\"settlementSlot\":\"rest\",\"limit\":2,\"hpDelta\":2,\"resourceCosts\":[{\"itemId\":\"food\",\"quantity\":1}]}]}}");
        JourneyRules rules=JourneyRules.parse("{\"schemaVersion\":1,\"stateEnabled\":true,\"hp\":{\"enabled\":true,\"init\":10,\"max\":10}}");
        TopicRouteEngine.State state=new TopicRouteEngine.State();JourneyInitialState.initialize(rules,state);state.getVariables().put("sys.hp",10);String before=json.writeValueAsString(state);
        JsonNode full=TopicRouteRuntimeServiceImpl.gameplayActionSummary(theme,rules,state,null,"FACILITY_USE","rest");
        assertTrue(full.path("enabled").asBoolean());assertEquals(0,full.path("count").asInt());assertEquals(before,json.writeValueAsString(state));
        state.getVariables().put("sys.hp",8);
        assertEquals("INSUFFICIENT_RESOURCES",TopicRouteRuntimeServiceImpl.gameplayActionSummary(theme,rules,state,null,"FACILITY_USE","rest").path("disabledReason").asText());
        theme.with("gameplayRuntime").putObject("survival").put("consumeOnNoEffect",true);state.getVariables().put("sys.hp",10);
        assertFalse(TopicRouteRuntimeServiceImpl.gameplayActionSummary(theme,rules,state,null,"FACILITY_USE","rest").path("enabled").asBoolean());
    }
    @Test void challengeReadbackCountsAndPassFlagComeOnlyFromServerReceipts() {
        ObjectNode theme=json.createObjectNode();theme.putObject("gameplayRuntime").putObject("survival").putObject("zeroChallenge").put("restoreTo",4);
        TopicRouteEngine.State state=new TopicRouteEngine.State();state.getThemeRuntime().put("awaitingChallenge",true);
        ObjectNode challenge=state.getThemeRuntime().putObject("challenge").put("limit",3).put("used",1).put("resolved",true).put("passed",true);
        state.getThemeRuntime().with("zeroChallenges").put("used",1);
        JsonNode before=TopicRouteRuntimeServiceImpl.gameplayChallengeSummary(theme,state);
        assertFalse(before.path("trustedPass").asBoolean());assertEquals(1,before.path("count").asInt());assertEquals(2,before.path("remainingCount").asInt());assertEquals("OWNER",before.path("countScope").asText());
        challenge.putObject("submissions").putObject("server-receipt").putObject("receipt").put("success",true);
        assertTrue(TopicRouteRuntimeServiceImpl.gameplayChallengeSummary(theme,state).path("trustedPass").asBoolean());
        state.getThemeRuntime().with("zeroChallenges").put("used",3);
        JsonNode limited=TopicRouteRuntimeServiceImpl.gameplayChallengeSummary(theme,state);assertFalse(limited.path("enabled").asBoolean());assertEquals("CHALLENGE_LIMIT",limited.path("disabledReason").asText());
        assertFalse(limited.toString().contains("submissions"));assertFalse(limited.toString().contains("server-receipt"));
    }
    @Test void shortagesIncludeItemsStatsCustomMinimumAndActionBudgetWithoutMutation() throws Exception {
        ObjectNode theme=json.createObjectNode().put("capacity",8).put("actionLimit",8);theme.putObject("gameplayRuntime");theme.putArray("items").addObject().put("id","food").put("kind","ITEM");
        JourneyRules rules=JourneyRules.parse("{\"schemaVersion\":1,\"stateEnabled\":true,\"hp\":{\"enabled\":true,\"init\":1,\"max\":10},\"luck\":{\"enabled\":true,\"init\":0,\"max\":5},\"attributes\":[{\"key\":\"counter.x\",\"label\":\"数值\",\"initial\":-2,\"min\":-3,\"max\":10}]}");
        TopicRouteEngine.State state=new TopicRouteEngine.State();JourneyInitialState.initialize(rules,state);state.getVariables().put("sys.hp",1);state.getVariables().put("sys.luck",0);state.getVariables().put("sys.actions",7);state.getThemeRuntime().with("inventory").put("food",1);
        ObjectNode fees=json.createObjectNode().put("hpCost",3).put("luckCost",1).put("actionCost",3);fees.putArray("statCosts").addObject().put("statKey","counter.x").put("amount",3);fees.putArray("resourceCosts").addObject().put("itemId","food").put("quantity",3);
        String before=json.writeValueAsString(state);JsonNode missing=TopicRouteRuntimeServiceImpl.gameplayCostShortfall(theme,rules,state,fees);
        assertEquals(json.readTree("[{\"type\":\"ITEM\",\"key\":\"food\",\"missing\":2},{\"type\":\"STAT\",\"key\":\"sys.hp\",\"missing\":2},{\"type\":\"STAT\",\"key\":\"sys.luck\",\"missing\":1},{\"type\":\"STAT\",\"key\":\"counter.x\",\"missing\":2},{\"type\":\"STAT\",\"key\":\"sys.actions\",\"missing\":2}]").toString(),missing.toString());
        assertEquals(before,json.writeValueAsString(state));
        ((ObjectNode)rules.getAttributes().path(0)).put("visible",false);assertFalse(TopicRouteRuntimeServiceImpl.gameplayCostShortfall(theme,rules,state,fees).toString().contains("counter.x"));
    }
    @Test void payingAConsumedDiscountItemRemovesItsReductionAndWorldLocksIncreaseItemGap() throws Exception {
        ObjectNode theme=json.createObjectNode().put("capacity",8);theme.putObject("gameplayRuntime");theme.putArray("items").addObject().put("id","shield").put("kind","ITEM").put("mode","REDUCE").put("stat","HP").put("delta",2);
        JourneyRules rules=JourneyRules.parse("{\"schemaVersion\":1,\"stateEnabled\":true,\"hp\":{\"enabled\":true,\"init\":1,\"max\":10}}");
        TopicRouteEngine.State state=new TopicRouteEngine.State();state.getVariables().put("sys.hp",1);state.getThemeRuntime().with("inventory").put("shield",1);
        ObjectNode costs=json.createObjectNode().put("hpCost",3);costs.putArray("resourceCosts").addObject().put("itemId","shield").put("quantity",1);
        assertEquals(json.readTree("[{\"type\":\"STAT\",\"key\":\"sys.hp\",\"missing\":2}]").toString(),TopicRouteRuntimeServiceImpl.gameplayCostShortfall(theme,rules,state,costs).toString());
        TopicRouteEngine.State world=new TopicRouteEngine.State();world.getThemeRuntime().with("cooperation").with("locks").with("7").putArray("amounts").addObject().put("itemId","shield").put("quantity",1);
        JsonNode missing=new GameplayCooperationEngine().resourceShortfall(world,7L,state,costs.path("resourceCosts"));assertEquals("shield",missing.path(0).path("key").asText());assertEquals(1,missing.path(0).path("missing").asInt());
    }
}
