package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.service.support.TopicRouteEngine.State;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GameplaySurvivalEngineTest {
    private final ObjectMapper json=new ObjectMapper();
    private final ThemeRuntimeEngine inventory=new ThemeRuntimeEngine();
    private final GameplaySurvivalEngine survival=new GameplaySurvivalEngine();
    private ObjectNode theme;
    private JourneyRules rules;
    private State state;
    @BeforeEach void setup()throws Exception {
        rules=new JourneyRules();rules.setCheckEnabled(true);rules.getHp().setMax(12);
        theme=(ObjectNode)json.readTree("{\"capacity\":10,\"items\":[{\"id\":\"food\",\"kind\":\"ITEM\",\"stat\":\"HP\",\"delta\":3}],\"gameplayRuntime\":{\"survival\":{\"recoveries\":[{\"recoveryKey\":\"rest\",\"kind\":\"REST\",\"limit\":2,\"hpDelta\":2},{\"recoveryKey\":\"rescue\",\"kind\":\"RESCUE\",\"limit\":1,\"hpFloor\":4}],\"rations\":[{\"rationKey\":\"first\",\"actKey\":\"supplies\",\"itemId\":\"food\",\"quantity\":1,\"fallbackHpCost\":2}]}}}");
        state=new State();inventory.initialize(theme,state);JourneyInitialState.initialize(rules,state);state.getVariables().put("sys.hp",12);
    }
    private void food() {ObjectNode c=json.createObjectNode();c.putArray("rows").addObject().put("itemId","food").put("quantity",1);inventory.grantAll(theme,state,rules,"food",c.path("rows"));}
    @Test void fullHpItemDefaultsToNoConsumptionAndAuthorMayEnableWaste() {
        food();assertThrows(IllegalStateException.class,()->inventory.use(theme,state,rules,"food"));assertEquals(1,state.getThemeRuntime().path("inventory").path("food").asInt());
        ((ObjectNode)theme.path("gameplayRuntime").path("survival")).put("consumeOnNoEffect",true);inventory.use(theme,state,rules,"food");assertEquals(0,state.getThemeRuntime().path("inventory").path("food").asInt());assertEquals(12,state.getVariables().get("sys.hp"));
    }
    @Test void noEffectRestAndRescueDoNotSpendCountersUnlessAuthorEnablesIt() {
        assertFalse(survival.recover(theme,rules,state,"rest","first").path("consumed").asBoolean());assertFalse(state.getThemeRuntime().has("recoveries"));
        assertFalse(survival.recover(theme,rules,state,"rescue","second").path("consumed").asBoolean());assertFalse(state.getThemeRuntime().path("assisted").asBoolean());
        ((ObjectNode)theme.path("gameplayRuntime").path("survival")).put("consumeOnNoEffect",true);
        assertTrue(survival.recover(theme,rules,state,"rest","third").path("consumed").asBoolean());assertEquals(1,state.getThemeRuntime().path("recoveries").path("rest").asInt());
    }
    @Test void partialRestClampsAndRescueRaisesOnlyToFloorWithFiniteUses() {
        state.getVariables().put("sys.hp",11);assertEquals(1,survival.recover(theme,rules,state,"rest","one").path("hpDelta").asInt());
        state.getVariables().put("sys.hp",0);assertEquals(4,survival.recover(theme,rules,state,"rescue","two").path("hpDelta").asInt());assertTrue(state.getThemeRuntime().path("assisted").asBoolean());
        assertThrows(IllegalStateException.class,()->survival.recover(theme,rules,state,"rescue","three"));
    }
    @Test void rationUsesExistingInventoryOnceOtherwiseChargesFloorClampedHp() {
        food();survival.ration(theme,rules,state,"supplies","PLAYER:1","end1");assertEquals(0,state.getThemeRuntime().path("inventory").path("food").asInt());assertEquals(12,state.getVariables().get("sys.hp"));
        survival.ration(theme,rules,state,"supplies","PLAYER:1","end2");assertEquals(12,state.getVariables().get("sys.hp"));
        State other=new State();inventory.initialize(theme,other);JourneyInitialState.initialize(rules,other);other.getVariables().put("sys.hp",1);
        survival.ration(theme,rules,other,"supplies","PLAYER:2","end3");assertEquals(0,other.getVariables().get("sys.hp"));assertEquals(0,other.getVariables().get("sys.actions"));
    }
    private ObjectNode challengeSnapshot() {
        ObjectNode snapshot=json.createObjectNode();snapshot.set("themeConfig",theme);
        ObjectNode policy=theme.with("gameplayRuntime").with("survival");policy.put("zeroHpPolicy","CHALLENGE");
        policy.putObject("zeroChallenge").put("challengeNodeId",101).put("restoreTo",4).put("limit",2).putArray("alternativeNodeIds").add(102);
        snapshot.putObject("nodeConfigs").putObject("101").putObject("ballShake").put("enabled",true).put("goal",10);
        state.getVariables().put("sys.hp",0);survival.syncChallenge(snapshot,rules,state);return snapshot;
    }
    @Test void challengeSumsTwoMembersAndRestoresOnlyOnceWithSubmissionDedupe() {
        ObjectNode snapshot=challengeSnapshot();String instance=survival.challengeView(state).path("instanceId").asText();
        ObjectNode first=survival.submitChallenge(snapshot,rules,state,7L,instance,101L,"same-id",false,6L,false);
        assertEquals(6,first.path("progress").asInt());assertFalse(first.path("success").asBoolean());
        assertEquals(first,survival.submitChallenge(snapshot,rules,state,7L,instance,101L,"same-id",false,6L,false));
        assertThrows(IllegalStateException.class,()->survival.submitChallenge(snapshot,rules,state,7L,instance,101L,"same-id",false,7L,false));
        ObjectNode second=survival.submitChallenge(snapshot,rules,state,8L,instance,101L,"same-id",false,4L,false);
        assertTrue(second.path("success").asBoolean());assertEquals(4,state.getVariables().get("sys.hp"));
        assertEquals(second,survival.submitChallenge(snapshot,rules,state,8L,instance,101L,"same-id",false,4L,false));
        assertEquals(1,state.getThemeRuntime().path("zeroChallenges").path("used").asInt());
        assertFalse(state.getThemeRuntime().path("awaitingChallenge").asBoolean());
        assertTrue(state.getCompletedNodeIds().isEmpty());
    }
    @Test void challengeFailureOpensFreeRetryAndAlternativeSuccessHonorsLimit() {
        ObjectNode snapshot=challengeSnapshot();String first=survival.challengeView(state).path("instanceId").asText();
        survival.submitChallenge(snapshot,rules,state,7L,first,102L,"wrong",false,null,true);
        String second=survival.challengeView(state).path("instanceId").asText();assertNotEquals(first,second);
        assertEquals(0,state.getThemeRuntime().path("zeroChallenges").path("used").asInt());assertEquals(0,state.getVariables().get("sys.actions"));
        survival.submitChallenge(snapshot,rules,state,8L,second,102L,"correct",true,null,false);
        assertEquals(4,state.getVariables().get("sys.hp"));
        state.getVariables().put("sys.hp",0);survival.syncChallenge(snapshot,rules,state);
        String third=survival.challengeView(state).path("instanceId").asText();
        survival.submitChallenge(snapshot,rules,state,7L,third,102L,"again",true,null,false);
        state.getVariables().put("sys.hp",0);survival.syncChallenge(snapshot,rules,state);
        assertEquals("CHALLENGE_LIMIT",survival.challengeView(state).path("disabledReason").asText());
        assertThrows(IllegalStateException.class,()->survival.submitChallenge(snapshot,rules,state,7L,survival.challengeView(state).path("instanceId").asText(),102L,"limit",true,null,false));
    }

}
