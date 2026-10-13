package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.service.support.TopicRouteEngine.State;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GameplayCooperationEngineTest {
    private final ObjectMapper json=new ObjectMapper();
    private final GameplayCooperationEngine engine=new GameplayCooperationEngine();
    private final ThemeRuntimeEngine inventory=new ThemeRuntimeEngine();
    private final Map<Long,State> teams=new LinkedHashMap<>();
    private final Map<Long,Long> leaders=new LinkedHashMap<>();
    private State world;
    private ObjectNode theme;
    private JourneyRules rules;
    private int seq;

    @Test void terminalVoteSelectsFrozenStoryEndingOnceAndEndsEligibleOwners() {
        ObjectNode plan=(ObjectNode)theme.path("gameplayRuntime").path("plans").path(0);
        plan.put("terminal",true).put("storyEndingId","story_finish").put("endingChapterId",901);
        theme.with("gameplayRuntime").putArray("storyEndings").addObject().put("code","story_finish").put("title","最终章结果").put("summary","结局正文").put("chapterId",901).put("fallback",true);
        propose("p1","leave");intent(1,"p1","tool");intent(2,"p1","tool");select("p1");
        for(long id:teams.keySet())call(id,"PLAN_CONFIRM",selected("p1"));
        teams.get(1L).getThemeRuntime().with("verifiedNodes").put("99",100);
        ObjectNode command=selected("p1");ObjectNode result=engine.execute(theme,rules,world,teams,leaders,1L,10L,"PLAN_EXECUTE",command,"story_execute",2000);
        assertEquals("story_finish",result.path("storyEndingId").asText());assertEquals(901,result.path("endingChapterId").asLong());assertEquals("最终章结果",result.path("title").asText());
        for(State state:teams.values()){assertEquals("COMPLETED",state.getStatus());assertEquals("story_finish",state.getThemeRuntime().path("result").path("endingId").asText());}
        assertEquals(result,engine.execute(theme,rules,world,teams,leaders,1L,10L,"PLAN_EXECUTE",command,"story_execute",2000));
        assertEquals(1,held(1,"tool"));assertEquals(1,held(2,"tool"));
    }

    @Test void trustedRootAndOptionEffectsReachEveryEligibleRecipientExactlyOnceAlongsideToolEffects() {
        rules.getHp().setMax(20);
        ObjectNode facility=(ObjectNode)theme.path("gameplayRuntime").path("facilities").path(0);facility.remove("hpDelta");facility.put("operationNodeId",101);
        facility.putArray("effects").addObject().put("var","sys.hp").put("op","INC").put("value",2);
        teams.get(1L).getThemeRuntime().with("verifiedNodes").put("101",100);
        ObjectNode proof=teams.get(1L).getThemeRuntime().with("operationFacts").putObject("FACILITY:shelter").put("sourceNodeId",101);
        ArrayNode effects=proof.putArray("effects");effects.addObject().put("var","sys.hp").put("op","INC").put("value",1);effects.addObject().put("var","sys.hp").put("op","INC").put("value",1);
        call(1,"FACILITY_USE",p().put("facilityKey","shelter"));
        for(State state:teams.values())assertEquals(12,state.getVariables().get("sys.hp").intValue());
        assertEquals(1,held(1,"tool"));assertEquals(2,held(2,"tool"));assertFalse(proof.has("effects") && teams.get(1L).getThemeRuntime().path("operationFacts").path("FACILITY:shelter").has("effects"));
        call(2,"FACILITY_USE",p().put("facilityKey","shelter"));
        for(State state:teams.values())assertEquals(12,state.getVariables().get("sys.hp").intValue());
    }
    @Test void originalRecoveryEffectsUseSharedDefaultAndOptInNoEffectPolicy() {
        ObjectNode facility=(ObjectNode)theme.path("gameplayRuntime").path("facilities").path(0);facility.remove("hpDelta");facility.put("limit",2);
        facility.putArray("effects").addObject().put("var","sys.hp").put("op","FLOOR").put("value",12);
        for(State state:teams.values())state.getVariables().put("sys.hp",12);
        ObjectNode free=call(1,"FACILITY_USE",p().put("facilityKey","shelter"));
        assertTrue(free.path("noEffect").asBoolean());assertFalse(free.path("consumed").asBoolean());assertEquals(0,free.path("count").asInt());assertEquals(2,held(1,"tool"));
        theme.with("gameplayRuntime").with("survival").put("consumeOnNoEffect",true);
        ObjectNode paid=call(1,"FACILITY_USE",p().put("facilityKey","shelter"));
        assertTrue(paid.path("consumed").asBoolean());assertEquals(1,paid.path("count").asInt());assertEquals(1,held(1,"tool"));
    }
    @Test void originalRecoveryCostsAndCountAreOneUseIfOneRecipientGains() {
        ObjectNode facility=(ObjectNode)theme.path("gameplayRuntime").path("facilities").path(0);facility.remove("hpDelta");
        facility.putArray("effects").addObject().put("var","sys.hp").put("op","FLOOR").put("value",12);
        teams.get(1L).getVariables().put("sys.hp",12);
        ObjectNode result=call(1,"FACILITY_USE",p().put("facilityKey","shelter"));
        assertFalse(result.path("noEffect").asBoolean());assertEquals(1,result.path("count").asInt());assertEquals(1,held(1,"tool"));assertEquals(12,teams.get(2L).getVariables().get("sys.hp").intValue());
    }
    @Test void cancelledOrOtherTeamsTrustedEffectsCannotBeConsumedByNewExecution() {
        ObjectNode facility=(ObjectNode)theme.path("gameplayRuntime").path("facilities").path(0);facility.remove("hpDelta");facility.put("operationNodeId",101);
        teams.get(1L).getThemeRuntime().with("operationFacts").putObject("FACILITY:shelter").putArray("effects").addObject().put("var","sys.hp").put("op","INC").put("value",2);
        teams.get(2L).getThemeRuntime().with("verifiedNodes").put("101",200);
        call(2,"FACILITY_USE",p().put("facilityKey","shelter"));
        for(State state:teams.values())assertEquals(8,state.getVariables().get("sys.hp").intValue());
        assertTrue(teams.get(1L).getThemeRuntime().path("operationFacts").path("FACILITY:shelter").has("effects"));
    }

    @Test void r5FacilityConfirmationChargesOnePayerAndAppliesOutcomeToEachWorldRecipientOnce() {
        ObjectNode f=(ObjectNode)theme.path("gameplayRuntime").path("facilities").get(0);f.remove(Arrays.asList("hpDelta","resourceCosts"));f.put("operationNodeId",101).put("limit",2);
        ObjectNode frozen=p();frozen.set("themeConfig",theme);
        ObjectNode after=frozen.putObject("nodeConfigs").putObject("101").putObject("outcomeRules").put("version",1).putObject("pass");
        after.putArray("costs").addObject().put("statKey","sys.hp").put("amount",3);
        after.putArray("effects").addObject().put("var","sys.hp").put("op","INC").put("value",1);
        new GameplayOutcomeEngine().remember(frozen,teams.get(1L),"advanced:10:1",p().put("nodeId",101).put("outcome","PASS"));
        teams.get(1L).getThemeRuntime().with("verifiedNodes").put("101",100);
        teams.get(1L).getThemeRuntime().with("operationFacts").putObject("FACILITY:shelter").put("outcomeAttemptId","advanced:10:1");
        call(1,"FACILITY_USE",p().put("facilityKey","shelter"));
        assertEquals(6,teams.get(1L).getVariables().get("sys.hp"));assertEquals(9,teams.get(2L).getVariables().get("sys.hp"));
        call(1,"FACILITY_USE",p().put("facilityKey","shelter"));
        assertEquals(6,teams.get(1L).getVariables().get("sys.hp"));assertEquals(9,teams.get(2L).getVariables().get("sys.hp"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"OWNER","WORLD","MISSING","BROKE"})
    void r5PlanRequiresEveryTeamsPassAndChargesAllOwnersAtomically(String mode) {
        ObjectNode frozen=p();frozen.set("themeConfig",theme);ObjectNode block=frozen.putObject("nodeConfigs").putObject("99");
        block.putObject("interactionRules").put("scope","WORLD".equals(mode)?"WORLD":"OWNER").put("targetScope","OWNER").put("limit",1);
        ObjectNode after=block.putObject("outcomeRules").put("version",1).putObject("pass");after.putArray("costs").addObject().put("statKey","sys.hp").put("amount",3);after.putArray("effects").addObject().put("var","sys.hp").put("op","INC").put("value",1);
        propose("r5","leave");intent(1,"r5","tool");intent(2,"r5","tool");select("r5");for(long team:teams.keySet())call(team,"PLAN_CONFIRM",selected("r5"));
        for(long team:teams.keySet()) {teams.get(team).getThemeRuntime().with("verifiedNodes").put("99",100);if(team==2 && "MISSING".equals(mode))continue;String attempt="advanced:"+team+":1";new GameplayOutcomeEngine().remember(frozen,teams.get(team),attempt,p().put("nodeId",99).put("outcome","PASS"));teams.get(team).getThemeRuntime().with("operationFacts").putObject("PLAN:leave").put("outcomeAttemptId",attempt).put("round",world.getThemeRuntime().path("cooperation").path("round").asInt()).put("sourceNodeId",99);}
        if("BROKE".equals(mode))teams.get(2L).getVariables().put("sys.hp",0);
        if(Arrays.asList("BROKE","MISSING").contains(mode)){ObjectNode before=teams.get(1L).getThemeRuntime().deepCopy();assertThrows(IllegalStateException.class,()->engine.execute(theme,rules,world,teams,leaders,1L,10L,"PLAN_EXECUTE",selected("r5"),"r5execute",2000,frozen));assertEquals(before,teams.get(1L).getThemeRuntime());assertEquals(8,teams.get(1L).getVariables().get("sys.hp"));assertEquals(2,held(1,"tool"));assertFalse(world.getThemeRuntime().path("cooperation").has("result"));}
        else {ObjectNode command=selected("r5");engine.execute(theme,rules,world,teams,leaders,1L,10L,"PLAN_EXECUTE",command,"r5execute",2000,frozen);for(long team:teams.keySet()){assertEquals(6,teams.get(team).getVariables().get("sys.hp"));assertEquals(1,held(team,"tool"));assertTrue(teams.get(team).getThemeRuntime().path("outcomeAttempts").path("advanced:"+team+":1").path("receipt").path("settled").asBoolean());}assertEquals(1,("WORLD".equals(mode)?world:teams.get(1L)).getThemeRuntime().path("cooperation").path("facilities").path("plan:99").path("count").asInt());engine.execute(theme,rules,world,teams,leaders,1L,10L,"PLAN_EXECUTE",command,"r5execute",2000,frozen);assertEquals(6,teams.get(1L).getVariables().get("sys.hp"));}
    }

    @BeforeEach void setup() {
        rules=new JourneyRules();rules.setCheckEnabled(true);rules.getHp().setMax(12);
        theme=json.createObjectNode().put("capacity",4).put("actionLimit",8);
        theme.putArray("items").addObject().put("id","tool").put("kind","ITEM").put("name","工具").put("slotCost",1);
        theme.withArray("items").addObject().put("id","clue").put("kind","ITEM").put("name","线索片").put("slotCost",1);
        ObjectNode g=theme.putObject("gameplayRuntime").put("runtimeScope","TEAM").put("worldMode","WORLD_SHARED").put("allowTrade",true).put("votePolicy","MAJORITY_ACCEPTANCE");
        g.putArray("acts").addObject().put("actKey","first");g.putObject("survival").put("zeroHpPolicy","BLOCK_PAID");
        ObjectNode plan=g.putArray("plans").addObject().put("endingKey","leave").put("title","离开").put("summary","安全离开").put("operationNodeId",99);
        plan.set("materialCost",amounts("tool",2));
        g.withArray("plans").addObject().put("endingKey","stay").put("title","留守").put("operationNodeId",98).set("materialCost",amounts("clue",2));
        ObjectNode f=g.putArray("facilities").addObject().put("facilityKey","shelter").put("actKey","first").put("scope","WORLD").put("targetScope","WORLD").put("settlementSlot","warm").put("limit",1).put("hpDelta",2);
        f.set("resourceCosts",amounts("tool",1));
        world=new State();inventory.initialize(theme,world);
        for(long id=1;id<=2;id++) addTeam(id);
    }
    private void addTeam(long id) {
        State s=new State();inventory.initialize(theme,s);JourneyInitialState.initialize(rules,s);s.getVariables().put("sys.hp",8);s.setStatus("ACTIVE");
        inventory.grantAll(theme,s,rules,"initial_tools",amounts("tool",2));inventory.grantAll(theme,s,rules,"initial_clues",amounts("clue",2));
        teams.put(id,s);leaders.put(id,id*10);
    }
    private ArrayNode amounts(String id,int qty){ArrayNode a=json.createArrayNode();a.addObject().put("itemId",id).put("quantity",qty);return a;}
    private ObjectNode p(){return json.createObjectNode();}
    private ObjectNode call(long team,String action,ObjectNode p){return engine.execute(theme,rules,world,teams,leaders,team,leaders.get(team),action,p,"event_"+(++seq));}
    private ObjectNode trade(String key,int give,int take) {ObjectNode p=p().put("tradeKey",key).put("toTeamId",2);p.set("give",amounts("tool",give));p.set("take",take==0?json.createArrayNode():amounts("clue",take));return p;}
    private int held(long team,String item){return teams.get(team).getThemeRuntime().path("inventory").path(item).asInt();}
    private void propose(String id,String ending){call(1,"PLAN_PROPOSE",p().put("proposalKey",id).put("endingKey",ending));}
    private void intent(long team,String id,String item){ObjectNode p=p().put("proposalKey",id).put("version",1);p.set("amounts",amounts(item,1));call(team,"PLAN_INTENT",p);}
    private void select(String id){call(1,"VOTE_OPEN",p());int round=world.getThemeRuntime().path("cooperation").path("round").asInt();for(Long team:teams.keySet())call(team,"VOTE_CAST",p().put("round",round).put("proposalKey",id));}
    private ObjectNode selected(String id){return p().put("round",world.getThemeRuntime().path("cooperation").path("round").asInt()).put("proposalKey",id);}

    @Test void publicResultsRedactNestedContributionSignersWithoutMutatingAuditOrPublicAmounts() {
        ObjectNode audit = p();
        ObjectNode result = audit.putArray("receipts").addObject().putObject("result");
        ObjectNode contribution = result.putObject("contributions").putObject("2").put("memberId", 20).put("proposalKey", "private");
        contribution.set("amounts", amounts("tool", 1));

        ObjectNode projected = GameplayCooperationEngine.publicResult(audit);

        assertFalse(projected.toString().contains("memberId"));
        assertFalse(projected.toString().contains("proposalKey"));
        assertEquals(amounts("tool", 1), projected.path("receipts").get(0).path("result").path("contributions").path("2"));
        assertTrue(audit.toString().contains("memberId"));
        assertEquals(projected, GameplayCooperationEngine.publicResult(projected));
        assertEquals(p().put("status", "OK"), GameplayCooperationEngine.publicResult(p().put("status", "OK")));
    }

    private void fallbackConfig() {
        theme.with("gameplayRuntime").putObject("templateTools").putArray("nodes").addObject().put("decidesEnding",true);
        theme.putArray("endings").addObject().put("id","none").put("title","无方案").put("fallback",true).put("success",false);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"MAJORITY","UNANIMOUS","MAJORITY_ACCEPTANCE"})
    void fallbackRequiresTheFinalPolicyRoundAndCurrentSignatures(String policy) {
        fallbackConfig();theme.with("gameplayRuntime").put("votePolicy",policy);
        assertEquals("BALLOT_NOT_FINISHED",engine.fallbackDisabledReason(theme,world,teams,leaders));
        propose("p1","leave");intent(1,"p1","tool");intent(2,"p1","tool");call(1,"VOTE_OPEN",p());
        for(long team:new long[]{1,2})call(team,"VOTE_CAST",selected("REJECT"));
        if("MAJORITY_ACCEPTANCE".equals(policy)) {
            assertEquals("BALLOT_NOT_FINISHED",engine.fallbackDisabledReason(theme,world,teams,leaders));
            for(long team:new long[]{1,2})call(team,"ACCEPT_SET",p().put("round",1).set("proposalKeys",json.createArrayNode()));
        }
        assertNull(engine.fallbackDisabledReason(theme,world,teams,leaders));
        leaders.put(2L,21L);assertEquals("BALLOT_NOT_FINISHED",engine.fallbackDisabledReason(theme,world,teams,leaders));
        leaders.put(2L,20L);
        ObjectNode result=engine.closeFallback(theme,world,teams,leaders,88L,"没有获准方案","close");
        assertEquals("none",result.path("endingKey").asText());assertEquals(2,held(1,"tool"));assertEquals(2,held(2,"tool"));
        assertEquals("FALLBACK",engine.view(theme,world,teams,1L,leaders).path("votePhase").asText());
        assertEquals(result,engine.closeFallback(theme,world,teams,leaders,88L,"没有获准方案","close"));
        assertThrows(IllegalStateException.class,()->engine.closeFallback(theme,world,teams,leaders,88L,"改原因","close"));
    }

    @Test void explicitRejectionMustCoverEveryCurrentLeaderAndCannotOverrideSelectedPlan() {
        fallbackConfig();call(1,"TRADE_OPEN",trade("reserved",1,1));call(1,"TRADE_CONFIRM",p().put("tradeKey","reserved").put("version",1));
        call(1,"PLAN_REJECT_ALL",p());assertEquals("BALLOT_NOT_FINISHED",engine.fallbackDisabledReason(theme,world,teams,leaders));
        call(2,"PLAN_REJECT_ALL",p());assertNull(engine.fallbackDisabledReason(theme,world,teams,leaders));
        leaders.put(2L,21L);assertEquals("BALLOT_NOT_FINISHED",engine.fallbackDisabledReason(theme,world,teams,leaders));leaders.put(2L,20L);
        propose("new","leave");assertEquals("BALLOT_NOT_FINISHED",engine.fallbackDisabledReason(theme,world,teams,leaders));
        call(1,"PLAN_REJECT_ALL",p());call(2,"PLAN_REJECT_ALL",p());
        engine.closeFallback(theme,world,teams,leaders,88L,"各队放弃全部方案","close");
        assertEquals("CANCELLED",world.getThemeRuntime().path("cooperation").path("trades").path("reserved").path("status").asText());
        assertEquals(2,held(1,"tool"));assertEquals(0,engine.view(theme,world,teams,1L,leaders).path("locked").path("tool").asInt());
    }

    @Test void fallbackCannotCloseAnExecutablePlanOrAnUnconfiguredTheme() {
        assertEquals("FALLBACK_NOT_CONFIGURED",engine.fallbackDisabledReason(theme,world,teams,leaders));
        fallbackConfig();propose("p1","leave");intent(1,"p1","tool");intent(2,"p1","tool");select("p1");call(1,"PLAN_CONFIRM",selected("p1"));
        String before=world.getThemeRuntime().toString();
        assertEquals("PLAN_AVAILABLE",engine.fallbackDisabledReason(theme,world,teams,leaders));
        assertThrows(IllegalStateException.class,()->engine.closeFallback(theme,world,teams,leaders,88L,"不应结束","close"));
        assertEquals(before,world.getThemeRuntime().toString());assertEquals(1,engine.view(theme,world,teams,1L,leaders).path("locked").path("tool").asInt());
    }

    @Test void publicQuestPreviewIncludesEachTargetsFacilityAndEventRewardsPlusActorChapterEnd() {
        theme.put("capacity",8);ObjectNode game=(ObjectNode)theme.path("gameplayRuntime"),act=(ObjectNode)game.path("acts").get(0);act.putArray("questKeys").add("warmup");act.putObject("completionRule").put("type","ALL");
        game.putArray("quests").addObject().put("questKey","warmup").put("actKey","first").put("nodeId",11).put("settlementSlot","warmup").put("facilityKey","shelter");((ObjectNode)game.path("facilities").get(0)).set("grants",amounts("clue",1));
        ObjectNode chapter=theme.putArray("chapters").addObject().put("index",0);chapter.putObject("binding").putArray("nodeIds").add(11);chapter.putArray("grants").addObject().put("timing","END").put("itemId","clue").put("quantity",1);
        ObjectNode event=theme.putArray("events").addObject().put("id","warm").put("content","补给");event.putArray("when").addObject().put("op","GTE").put("var","sys.hp").put("value",10);event.set("grants",amounts("clue",1));
        GameplayTaskEngine tasks=new GameplayTaskEngine();tasks.execute(theme,rules,teams.get(1L),"warmup","QUEST_START",null,"start","TEAM:1",1000);teams.get(1L).getThemeRuntime().with("verifiedNodes").put("11",true);
        ObjectNode preview=engine.previewQuest(theme,rules,world,teams,leaders,1L,10L,"warmup",null,null,null,"TEAM:1",2000);assertEquals(3,preview.path("grants").path("clue").asInt());assertEquals(2,preview.path("rewardOwners").path(1).path("grants").path("clue").asInt());assertFalse(preview.path("overflow").asBoolean());
        engine.executeQuest(theme,rules,world,teams,leaders,1L,10L,"warmup","QUEST_RESOLVE",null,"done","TEAM:1",2000,null,preview.path("previewVersion").asText());assertEquals(5,held(1,"clue"));assertEquals(4,held(2,"clue"));assertTrue(teams.get(1L).getThemeRuntime().path("pending").isEmpty());assertTrue(teams.get(2L).getThemeRuntime().path("pending").isEmpty());
    }

    @Test void publicQuestPreviewUsesAllActualTeamsForRecoveryCostAndStaleVersion() {
        ObjectNode game=(ObjectNode)theme.path("gameplayRuntime"),act=(ObjectNode)game.path("acts").get(0);act.putArray("questKeys").add("warmup");act.putObject("completionRule").put("type","ALL");
        game.putArray("quests").addObject().put("questKey","warmup").put("actKey","first").put("nodeId",11).put("settlementSlot","warmup").put("facilityKey","shelter");
        teams.get(1L).getVariables().put("sys.hp",12);GameplayTaskEngine tasks=new GameplayTaskEngine();tasks.execute(theme,rules,teams.get(1L),"warmup","QUEST_START",null,"start","TEAM:1",1000);
        ObjectNode preview=engine.previewQuest(theme,rules,world,teams,leaders,1L,10L,"warmup",null,null,null,"TEAM:1",2000);assertEquals(3,preview.path("finalUsed").asInt());assertFalse(preview.path("overflow").asBoolean());
        teams.get(1L).getThemeRuntime().with("verifiedNodes").put("11",true);preview=engine.previewQuest(theme,rules,world,teams,leaders,1L,10L,"warmup",null,null,null,"TEAM:1",2000);String stale=preview.path("previewVersion").asText();teams.get(2L).getVariables().put("sys.hp",7);
        assertThrows(IllegalStateException.class,()->engine.executeQuest(theme,rules,world,teams,leaders,1L,10L,"warmup","QUEST_RESOLVE",null,"stale","TEAM:1",2000,null,stale));assertEquals(2,held(1,"tool"));
        preview=engine.previewQuest(theme,rules,world,teams,leaders,1L,10L,"warmup",null,null,null,"TEAM:1",2000);engine.executeQuest(theme,rules,world,teams,leaders,1L,10L,"warmup","QUEST_RESOLVE",null,"done","TEAM:1",2000,null,preview.path("previewVersion").asText());assertEquals(1,held(1,"tool"));assertEquals(9,teams.get(2L).getVariables().get("sys.hp"));
    }

    @Test void facilityAndTriggeredEventRewardsShareCapacityAndExplicitDiscard() {
        ObjectNode facility=(ObjectNode)theme.path("gameplayRuntime").path("facilities").get(0);facility.put("scope","OWNER").put("targetScope","OWNER");facility.set("grants",amounts("clue",1));
        ObjectNode event=theme.putArray("events").addObject().put("id","warm").put("content","补给");event.putArray("when").addObject().put("op","GTE").put("var","sys.hp").put("value",10);event.set("grants",amounts("clue",1));
        ObjectNode payload=p().put("facilityKey","shelter"),before=json.valueToTree(teams.get(1L));ObjectNode preview=engine.previewFacility(theme,rules,world,teams,leaders,1L,10L,payload,2000);
        assertEquals(2,preview.path("rewardOwners").path(0).path("grants").path("clue").asInt());assertTrue(preview.path("overflow").asBoolean());assertEquals(before,json.valueToTree(teams.get(1L)));
        assertThrows(IllegalStateException.class,()->engine.settleFacility(theme,rules,world,teams,leaders,1L,10L,payload,"full",2000));assertEquals(before,json.valueToTree(teams.get(1L)));
        payload.set("discardRewards",amounts("clue",1));preview=engine.previewFacility(theme,rules,world,teams,leaders,1L,10L,payload,2000);assertFalse(preview.path("overflow").asBoolean());payload.put("previewVersion",preview.path("previewVersion").asText());
        ObjectNode receipt=engine.settleFacility(theme,rules,world,teams,leaders,1L,10L,payload,"claim",2000);assertEquals(4,inventory.used(theme,teams.get(1L)));assertTrue(teams.get(1L).getThemeRuntime().path("pending").isEmpty());before=json.valueToTree(teams.get(1L));
        assertEquals(receipt,engine.settleFacility(theme,rules,world,teams,leaders,1L,10L,payload,"claim",2000));assertEquals(before,json.valueToTree(teams.get(1L)));
        payload.set("discardRewards",amounts("clue",2));assertThrows(IllegalStateException.class,()->engine.settleFacility(theme,rules,world,teams,leaders,1L,10L,payload,"claim",2000));
    }

    @Test void fullBagsSwapAfterBothSignAndConserveResources() {
        call(1,"TRADE_OPEN",trade("swap",1,1));call(1,"TRADE_CONFIRM",p().put("tradeKey","swap").put("version",1));
        assertEquals(2,held(1,"tool"));assertEquals(1,engine.view(theme,world,teams,1L).path("locked").path("tool").asInt());
        call(2,"TRADE_CONFIRM",p().put("tradeKey","swap").put("version",1));
        assertEquals(1,held(1,"tool"));assertEquals(3,held(2,"tool"));assertEquals(3,held(1,"clue"));assertEquals(1,held(2,"clue"));
        assertEquals(4,held(1,"tool")+held(2,"tool"));assertEquals(4,held(1,"clue")+held(2,"clue"));
    }
    @Test void nodeTradeRequiresBothTeamsAtTheSameEnabledNodeAndExplainsReadback() {
        ArrayNode nodes=theme.with("gameplayRuntime").with("templateTools").putArray("nodes");
        nodes.addObject().put("nodeId",11).put("allowTrade",true);
        nodes.addObject().put("nodeId",12).put("allowTrade",true);
        teams.get(1L).setCurrentNodeId(11L);teams.get(2L).setCurrentNodeId(11L);
        assertThrows(IllegalStateException.class,()->call(1,"TRADE_OPEN",trade("swap",1,1)));
        teams.get(1L).getThemeRuntime().with("verifiedNodes").put("11",100);
        teams.get(2L).getThemeRuntime().with("verifiedNodes").put("12",101);
        JsonNode disabled=engine.view(theme,world,teams,1L).path("tradeTargets").path(0);
        assertTrue(disabled.path("disabled").asBoolean());assertTrue(disabled.path("disabledMessage").asText().contains("对方"));assertEquals("NOT_ARRIVED",disabled.path("disabledReason").asText());
        JsonNode before=json.valueToTree(teams);ObjectNode worldBefore=world.getThemeRuntime().deepCopy();
        assertThrows(IllegalStateException.class,()->engine.previewTrade(theme,rules,world,teams,1L,trade("swap",1,1)));
        assertEquals(before,json.valueToTree(teams));assertEquals(worldBefore,world.getThemeRuntime());
        teams.get(2L).getThemeRuntime().with("gameplay").with("arrivalFacts").putObject("21").put("sourceNodeId",11).put("receiptId",200);
        JsonNode enabled=engine.view(theme,world,teams,1L).path("tradeTargets").path(0);
        assertFalse(enabled.path("disabled").asBoolean());assertEquals(11,enabled.path("nodeId").asLong());
        call(1,"TRADE_OPEN",trade("swap",1,1));
        assertEquals(11,engine.view(theme,world,teams,1L).path("trades").path(0).path("nodeId").asLong());
        teams.get(2L).getThemeRuntime().with("gameplay").remove("arrivalFacts");
        assertThrows(IllegalStateException.class,()->call(2,"TRADE_CONFIRM",p().put("tradeKey","swap").put("version",1)));
        teams.get(2L).getThemeRuntime().with("hostArrivals").putObject("yard").put("sourceNodeId",11).put("used",true);
        call(1,"TRADE_CONFIRM",p().put("tradeKey","swap").put("version",1));
        call(2,"TRADE_CONFIRM",p().put("tradeKey","swap").put("version",1));
        assertEquals(1,held(1,"tool"));
    }

    @Test void disabledNodeToolDoesNotChangeLegacyGlobalTrade() {
        theme.with("gameplayRuntime").with("templateTools").putArray("nodes").addObject().put("nodeId",11).put("allowTrade",false);
        assertFalse(engine.view(theme,world,teams,1L).path("tradeTargets").path(0).path("disabled").asBoolean());
        assertDoesNotThrow(()->call(1,"TRADE_OPEN",trade("swap",1,1)));
    }
    @Test void giftOverflowDoesNotPartiallyConsumeOrSign() {
        call(1,"TRADE_OPEN",trade("gift",1,0));call(1,"TRADE_CONFIRM",p().put("tradeKey","gift").put("version",1));
        JsonNode before=json.valueToTree(teams);ObjectNode w=world.getThemeRuntime().deepCopy();
        assertThrows(IllegalStateException.class,()->call(2,"TRADE_CONFIRM",p().put("tradeKey","gift").put("version",1)));
        assertEquals(before,json.valueToTree(teams));assertEquals(w,world.getThemeRuntime());
    }
    @Test void previewBecomesStaleAfterInventoryChanges() {
        call(1,"TRADE_OPEN",trade("swap",1,1));inventory.consumeAll(theme,teams.get(2L),rules,"other",amounts("tool",1));
        assertThrows(IllegalStateException.class,()->call(1,"TRADE_CONFIRM",p().put("tradeKey","swap").put("version",1)));
        assertEquals(2,held(1,"tool"));
    }
    @Test void reusableVoteAndStakeCommandsStillRequireCurrentCaptainWithoutMutation() {
        JsonNode teamBefore = json.valueToTree(teams); ObjectNode worldBefore = world.getThemeRuntime().deepCopy();
        for (String action : Arrays.asList("PLAN_PROPOSE", "PLAN_INTENT", "VOTE_OPEN", "VOTE_CAST", "PLAN_CONFIRM", "PLAN_EXECUTE")) {
            String reason = assertThrows(IllegalStateException.class, () -> engine.execute(theme, rules, world, teams,
                    leaders, 1L, 11L, action, p(), "member_" + action)).getMessage();
            assertTrue(reason.contains("队长"), reason);
            assertEquals(teamBefore, json.valueToTree(teams)); assertEquals(worldBefore, world.getThemeRuntime());
        }
        assertDoesNotThrow(() -> propose("captain_plan", "leave"));
    }
    @Test void changedCaptainMustSignAgainAndOtherTeamCannotSign() {
        call(1,"TRADE_OPEN",trade("swap",1,1));call(1,"TRADE_CONFIRM",p().put("tradeKey","swap").put("version",1));leaders.put(1L,11L);
        call(2,"TRADE_CONFIRM",p().put("tradeKey","swap").put("version",1));assertEquals(2,held(1,"tool"));
        assertThrows(IllegalStateException.class,()->engine.execute(theme,rules,world,teams,leaders,1L,10L,"TRADE_CONFIRM",p().put("tradeKey","swap").put("version",1),"old_leader"));
        call(1,"TRADE_CONFIRM",p().put("tradeKey","swap").put("version",1));assertEquals(1,held(1,"tool"));
    }
    @Test void confirmedReservationsCannotBeSpentAndCancellationReleases() {
        call(1,"TRADE_OPEN",trade("swap",2,1));call(1,"TRADE_CONFIRM",p().put("tradeKey","swap").put("version",1));
        assertThrows(IllegalStateException.class,()->engine.requireAvailable(world,1L,teams.get(1L),amounts("tool",1)));
        call(1,"TRADE_CANCEL",p().put("tradeKey","swap").put("version",1));assertDoesNotThrow(()->engine.requireAvailable(world,1L,teams.get(1L),amounts("tool",2)));
    }
    @Test void worldFacilityCompetitorDoesNotPayOrRepeatAnyEffect() {
        ObjectNode first=call(1,"FACILITY_USE",p().put("facilityKey","shelter"));ObjectNode second=call(2,"FACILITY_USE",p().put("facilityKey","shelter"));
        assertEquals(first,second);assertEquals(1,held(1,"tool"));assertEquals(2,held(2,"tool"));assertEquals(10,teams.get(1L).getVariables().get("sys.hp"));assertEquals(10,teams.get(2L).getVariables().get("sys.hp"));
    }
    @Test void pureRecoveryChecksActualGainBeforeChargingOrCounting() {
        teams.values().forEach(state->state.getVariables().put("sys.hp",12));
        ObjectNode empty=call(1,"FACILITY_USE",p().put("facilityKey","shelter"));
        assertTrue(empty.path("noEffect").asBoolean());assertEquals(0,empty.path("count").asInt());
        assertEquals(2,held(1,"tool"));assertFalse(world.getThemeRuntime().path("cooperation").path("facilities").has("warm"));
        teams.get(2L).getVariables().put("sys.hp",10);
        ObjectNode restored=call(1,"FACILITY_USE",p().put("facilityKey","shelter"));
        assertFalse(restored.path("noEffect").asBoolean());assertEquals(1,restored.path("count").asInt());
        assertEquals(1,held(1,"tool"));assertEquals(12,teams.get(2L).getVariables().get("sys.hp"));
    }
    @Test void completedTeamIsUntouchedByWorldAndParticipantEffects() {
        for(String scope:Arrays.asList("WORLD","PARTICIPANTS")) {
            setup();theme.with("gameplayRuntime").remove("plans");addTeam(3);
            teams.get(2L).setStatus("COMPLETED");
            ObjectNode facility=(ObjectNode)theme.path("gameplayRuntime").path("facilities").path(0);
            facility.put("targetScope",scope).put("arrivalLocationKey","yard");
            facility.putArray("effects").addObject().put("op","INC").put("var","counter.heat").put("value",1);
            for(long team:new long[]{1,3})teams.get(team).getThemeRuntime().with("hostArrivals").putObject("yard").put("used",true);
            JsonNode completed=json.valueToTree(teams.get(2L));
            ObjectNode use=p().put("facilityKey","shelter");if("PARTICIPANTS".equals(scope))use.putArray("participants").add(1).add(2).add(3);
            ObjectNode result=call(1,"FACILITY_USE",use);
            assertEquals(json.createArrayNode().add(1L).add(3L),result.path("targets"));
            assertEquals(completed,json.valueToTree(teams.get(2L)));
            assertEquals(10,teams.get(3L).getVariables().get("sys.hp"));assertEquals(1,held(1,"tool"));
        }
    }

    @Test void completedActorsAndTradeCounterpartsCannotSpendOrReceiveEffects() {
        theme.with("gameplayRuntime").remove("plans");teams.get(2L).setStatus("COMPLETED");
        JsonNode before=json.valueToTree(teams);ObjectNode worldBefore=world.getThemeRuntime().deepCopy();
        assertThrows(IllegalStateException.class,()->call(2,"FACILITY_USE",p().put("facilityKey","shelter")));
        assertThrows(IllegalStateException.class,()->call(1,"TRADE_OPEN",trade("finished",1,1)));
        assertTrue(engine.view(theme,world,teams,1L).path("tradeTargets").path(0).path("disabled").asBoolean());
        ObjectNode facility=(ObjectNode)theme.path("gameplayRuntime").path("facilities").path(0);facility.put("scope","OWNER").put("targetScope","OWNER");
        assertThrows(IllegalStateException.class,()->engine.ownerFacility(theme,rules,teams.get(2L),2L,p().put("facilityKey","shelter"),"finished"));
        assertEquals(before,json.valueToTree(teams));assertEquals(worldBefore,world.getThemeRuntime());
    }

    @Test void terminalPlansAndOutstandingChallengesRemainEffectiveForCompatibility() {
        teams.get(2L).setStatus("COMPLETED");
        assertTrue(GameplayCooperationEngine.isEffectiveTarget(theme,teams.get(2L)));
        theme.with("gameplayRuntime").remove("plans");
        assertFalse(GameplayCooperationEngine.isEffectiveTarget(theme,teams.get(2L)));
        teams.get(2L).getThemeRuntime().put("awaitingChallenge",true);
        assertTrue(GameplayCooperationEngine.isEffectiveTarget(theme,teams.get(2L)));
        call(1,"FACILITY_USE",p().put("facilityKey","shelter"));assertEquals(10,teams.get(2L).getVariables().get("sys.hp"));
        teams.get(2L).getThemeRuntime().remove("awaitingChallenge");theme.with("gameplayRuntime").put("worldMode","INDEPENDENT");
        assertTrue(GameplayCooperationEngine.isEffectiveTarget(theme,teams.get(2L)));
    }

    @Test void completedTargetsDoNotTurnAnOtherwiseEmptyRecoveryIntoAPaidUse() {
        theme.with("gameplayRuntime").remove("plans");teams.get(2L).setStatus("COMPLETED");teams.get(1L).getVariables().put("sys.hp",12);
        JsonNode facility=theme.path("gameplayRuntime").path("facilities").path(0);
        assertTrue(engine.boundFacilityNoEffect(theme,rules,teams.get(1L),facility,p().put("questKey","recover"),teams.values()));
        ObjectNode result=call(1,"FACILITY_USE",p().put("facilityKey","shelter"));
        assertTrue(result.path("noEffect").asBoolean());assertEquals(0,result.path("count").asInt());assertEquals(2,held(1,"tool"));
        assertEquals(8,teams.get(2L).getVariables().get("sys.hp"));
    }

    @Test void completedTeamsAreNotRequiredToVoteOnNonterminalWorldOperations() {
        for(JsonNode plan:theme.path("gameplayRuntime").path("plans"))((ObjectNode)plan).put("terminal",false);
        teams.get(2L).setStatus("COMPLETED");
        propose("one","leave");propose("two","stay");
        ObjectNode contribution=p().put("proposalKey","one").put("version",1);contribution.set("amounts",amounts("tool",2));call(1,"PLAN_INTENT",contribution);
        contribution=p().put("proposalKey","two").put("version",1);contribution.set("amounts",amounts("clue",2));call(1,"PLAN_INTENT",contribution);
        call(1,"VOTE_OPEN",p());
        int round=world.getThemeRuntime().path("cooperation").path("round").asInt();
        call(1,"VOTE_CAST",p().put("round",round).put("proposalKey","one"));
        assertEquals("SELECTED",world.getThemeRuntime().path("cooperation").path("stage").asText());
    }
    @Test void explicitConsumptionCountsEvenWhenRecoveryIsFull() {
        teams.values().forEach(state->state.getVariables().put("sys.hp",12));
        theme.with("gameplayRuntime").with("survival").put("consumeOnNoEffect",true);
        ObjectNode result=call(1,"FACILITY_USE",p().put("facilityKey","shelter"));
        assertTrue(result.path("noEffect").asBoolean());assertEquals(1,result.path("count").asInt());
        assertEquals(1,held(1,"tool"));
    }
    @Test void playerRecoveryPaymentMarkerRequiresActualConsumptionAndReplayKeepsIt() {
        theme.with("gameplayRuntime").put("runtimeScope","PLAYER");
        ObjectNode facility=(ObjectNode)theme.path("gameplayRuntime").path("facilities").get(0);
        facility.put("scope","OWNER").put("targetScope","OWNER");
        State state=teams.get(1L);state.getVariables().put("sys.hp",12);
        ObjectNode free=engine.ownerFacility(theme,rules,state,1L,p().put("facilityKey","shelter"),"full_free");
        assertFalse(free.path("consumed").asBoolean());assertFalse(free.has("paidByOwnerId"));assertFalse(free.has("paidByTeamId"));
        assertEquals(1,free.path("ownerId").asLong());assertEquals(2,held(1,"tool"));
        theme.with("gameplayRuntime").with("survival").put("consumeOnNoEffect",true);
        ObjectNode paid=engine.ownerFacility(theme,rules,state,1L,p().put("facilityKey","shelter"),"full_paid");
        assertTrue(paid.path("noEffect").asBoolean());assertTrue(paid.path("consumed").asBoolean());
        assertEquals(1,paid.path("paidByOwnerId").asLong());assertFalse(paid.has("paidByTeamId"));assertEquals(1,held(1,"tool"));
        JsonNode before=json.valueToTree(state);
        assertEquals(paid,engine.ownerFacility(theme,rules,state,1L,p().put("facilityKey","shelter"),"paid_replay"));
        assertEquals(before,json.valueToTree(state));
    }
    @Test void freeFacilityConsumesUseWithoutInventingPayerAndTeamNoEffectStaysFree() {
        teams.values().forEach(state->state.getVariables().put("sys.hp",12));
        ObjectNode free=call(1,"FACILITY_USE",p().put("facilityKey","shelter"));
        assertFalse(free.path("consumed").asBoolean());assertFalse(free.has("paidByTeamId"));
        ObjectNode facility=(ObjectNode)theme.path("gameplayRuntime").path("facilities").get(0);facility.remove("resourceCosts");
        teams.get(1L).getVariables().put("sys.hp",10);
        ObjectNode used=call(1,"FACILITY_USE",p().put("facilityKey","shelter"));
        assertTrue(used.path("consumed").asBoolean());assertFalse(used.has("paidByTeamId"));assertEquals(1,used.path("count").asInt());
        assertEquals(2,held(1,"tool"));assertEquals(12,teams.get(1L).getVariables().get("sys.hp"));
    }
    @Test void fullyReducedStatFeeDoesNotInventPayment() {
        ObjectNode facility=(ObjectNode)theme.path("gameplayRuntime").path("facilities").get(0);
        facility.remove("resourceCosts");facility.put("luckCost",1);
        ((ObjectNode)theme.path("items").get(0)).put("stat","LUCK").put("delta",1).put("mode","REDUCE");
        ObjectNode result=call(1,"FACILITY_USE",p().put("facilityKey","shelter"));
        assertTrue(result.path("consumed").asBoolean());assertFalse(result.has("paidByTeamId"));
        assertEquals(1,result.path("count").asInt());assertEquals(2,held(1,"tool"));assertEquals(10,teams.get(1L).getVariables().get("sys.hp"));
    }
    @Test void participantRecoveryWithoutActiveTasksChargesOnceOnlyWhenSomeoneGains() {
        ObjectNode facility=(ObjectNode)theme.path("gameplayRuntime").path("facilities").path(0);facility.put("targetScope","PARTICIPANTS");
        ObjectNode use=p().put("facilityKey","shelter");use.putArray("participants").add(1).add(2);
        teams.values().forEach(state->state.getVariables().put("sys.hp",12));
        facility.set("resourceCosts",amounts("tool",99));
        ObjectNode free=call(1,"FACILITY_USE",use);
        assertTrue(free.path("noEffect").asBoolean());assertEquals(0,free.path("count").asInt());assertEquals(2,held(1,"tool"));
        assertFalse(world.getThemeRuntime().path("cooperation").path("facilities").has("warm"));
        facility.set("resourceCosts",amounts("tool",1));teams.get(2L).getVariables().put("sys.hp",10);
        ObjectNode paid=engine.execute(theme,rules,world,teams,leaders,1L,10L,"FACILITY_USE",use,"paid-recovery");
        assertEquals(1,paid.path("count").asInt());assertEquals(1,held(1,"tool"));assertEquals(2,held(2,"tool"));
        assertEquals(12,teams.get(1L).getVariables().get("sys.hp"));assertEquals(12,teams.get(2L).getVariables().get("sys.hp"));
        JsonNode before=json.valueToTree(teams);
        assertEquals(paid,engine.execute(theme,rules,world,teams,leaders,1L,10L,"FACILITY_USE",use,"paid-recovery"));
        assertEquals(paid,call(1,"FACILITY_USE",use));assertEquals(before,json.valueToTree(teams));
    }

    @Test void participantRecoveryHonorsExplicitConsumptionAtFullValues() {
        ((ObjectNode)theme.path("gameplayRuntime").path("facilities").path(0)).put("targetScope","PARTICIPANTS");
        theme.with("gameplayRuntime").with("survival").put("consumeOnNoEffect",true);
        teams.values().forEach(state->state.getVariables().put("sys.hp",12));
        ObjectNode use=p().put("facilityKey","shelter");use.putArray("participants").add(1).add(2);
        ObjectNode result=call(1,"FACILITY_USE",use);
        assertTrue(result.path("noEffect").asBoolean());assertEquals(1,result.path("count").asInt());assertEquals(1,held(1,"tool"));
    }

    @Test void nonpayerPendingTaskEffectsPreventMisclassifyingMixedSettlementAsFree() {
        bindFacilityQuest();ObjectNode quest=(ObjectNode)theme.path("gameplayRuntime").path("quests").path(0);quest.remove(Arrays.asList("facilityKey","resourceCosts","hpCost"));
        ((ObjectNode)theme.path("gameplayRuntime").path("facilities").path(0)).put("targetScope","PARTICIPANTS").put("operationNodeId",101);
        new GameplayTaskEngine().execute(theme,rules,teams.get(2L),"q1","QUEST_START",null,"start","TEAM:2",1);
        for(State state:teams.values()){state.getVariables().put("sys.hp",12);state.getThemeRuntime().with("verifiedNodes").put("101",true);}
        ObjectNode pending=teams.get(2L).getThemeRuntime().with("gameplay").with("pendingTaskEffects").putObject("q1");pending.putObject("sources").put("ROOT",true);
        pending.putArray("effects").addObject().put("op","INC").put("var","sys.hp").put("value",-2);
        ObjectNode use=p().put("facilityKey","shelter");use.putArray("participants").add(1).add(2);
        ObjectNode result=call(1,"FACILITY_USE",use);
        assertFalse(result.path("noEffect").asBoolean());assertEquals(1,result.path("count").asInt());assertEquals(1,held(1,"tool"));
        assertEquals(12,teams.get(2L).getVariables().get("sys.hp"));assertTrue(teams.get(2L).getCompletedNodeIds().contains(101L));
        assertFalse(teams.get(2L).getThemeRuntime().path("gameplay").path("pendingTaskEffects").has("q1"));
    }

    @Test void invalidParticipantTaskRollsBackRecoveryCostsAndCounters() {
        bindFacilityQuest();ObjectNode quest=(ObjectNode)theme.path("gameplayRuntime").path("quests").path(0);quest.remove("facilityKey");
        ((ObjectNode)theme.path("gameplayRuntime").path("facilities").path(0)).put("targetScope","PARTICIPANTS").put("operationNodeId",101);
        new GameplayTaskEngine().execute(theme,rules,teams.get(2L),"q1","QUEST_START",null,"start","TEAM:2",1);
        teams.values().forEach(state->state.getThemeRuntime().with("verifiedNodes").put("101",true));
        ObjectNode use=p().put("facilityKey","shelter");use.putArray("participants").add(1).add(2);
        JsonNode before=json.valueToTree(teams);ObjectNode worldBefore=world.getThemeRuntime().deepCopy();
        assertThrows(IllegalStateException.class,()->call(1,"FACILITY_USE",use));
        assertEquals(before,json.valueToTree(teams));assertEquals(worldBefore,world.getThemeRuntime());
    }
    @Test void facilityCostOptionChargesOneGroupAndReplayCannotSwitchIt() {
        ObjectNode f=(ObjectNode)theme.path("gameplayRuntime").path("facilities").get(0);f.remove("resourceCosts");
        ArrayNode options=f.putArray("costOptions");options.addObject().set("resourceCosts",amounts("tool",1));options.addObject().set("resourceCosts",amounts("clue",1));
        assertThrows(IllegalArgumentException.class,()->call(1,"FACILITY_USE",p().put("facilityKey","shelter")));
        ObjectNode payload=p().put("facilityKey","shelter").put("costOption",1);
        ObjectNode receipt=engine.execute(theme,rules,world,teams,leaders,1L,10L,"FACILITY_USE",payload,"selected");
        assertEquals(1,receipt.path("costOption").asInt());assertEquals(2,held(1,"tool"));assertEquals(1,held(1,"clue"));
        assertEquals(receipt,engine.execute(theme,rules,world,teams,leaders,1L,10L,"FACILITY_USE",payload,"selected"));
        payload.put("costOption",0);assertThrows(IllegalStateException.class,()->engine.execute(theme,rules,world,teams,leaders,1L,10L,"FACILITY_USE",payload,"selected"));
    }
    @Test void ownerFacilitySlotSurvivesInventoryEngineReplacingRuntimeObject() {
        ((ObjectNode)theme.path("gameplayRuntime").path("facilities").get(0)).put("scope","OWNER").put("targetScope","OWNER");
        call(1,"FACILITY_USE",p().put("facilityKey","shelter"));call(1,"FACILITY_USE",p().put("facilityKey","shelter"));assertEquals(1,held(1,"tool"));
        call(2,"FACILITY_USE",p().put("facilityKey","shelter"));assertEquals(1,held(2,"tool"));
    }
    @Test void majorityCannotSpendUnconfirmedContributionAndTrustedOperationIsRequired() {
        propose("p1","leave");intent(1,"p1","tool");intent(2,"p1","tool");select("p1");
        call(1,"PLAN_CONFIRM",selected("p1"));teams.get(1L).getThemeRuntime().with("verifiedNodes").put("99",true);
        assertThrows(IllegalStateException.class,()->call(1,"PLAN_EXECUTE",selected("p1")));assertEquals(2,held(1,"tool"));
        call(2,"PLAN_CONFIRM",selected("p1"));teams.get(1L).getThemeRuntime().with("verifiedNodes").remove("99");
        assertThrows(IllegalStateException.class,()->call(1,"PLAN_EXECUTE",selected("p1")));assertFalse(world.getThemeRuntime().path("cooperation").has("result"));
        teams.get(1L).getThemeRuntime().with("verifiedNodes").put("99",true);call(1,"PLAN_EXECUTE",selected("p1"));
        assertEquals(1,held(1,"tool"));assertEquals(1,held(2,"tool"));assertEquals("leave",world.getThemeRuntime().path("cooperation").path("result").path("endingKey").asText());
        assertThrows(IllegalStateException.class,()->call(1,"PLAN_EXECUTE",selected("p1")));assertEquals(1,held(1,"tool"));
    }
    @Test void changeAndExitReleaseEscrowWithoutConfiscatingMaterial() {
        propose("p1","leave");intent(1,"p1","tool");intent(2,"p1","tool");select("p1");call(1,"PLAN_CONFIRM",selected("p1"));
        assertEquals(1,engine.view(theme,world,teams,1L).path("locked").path("tool").asInt());call(2,"PLAN_CHANGE",p());
        assertEquals(0,engine.view(theme,world,teams,1L).path("locked").path("tool").asInt());assertEquals(2,held(1,"tool"));
        select("p1");call(1,"PLAN_CONFIRM",selected("p1"));engine.exit(world,2L);assertEquals("DRAFT",world.getThemeRuntime().path("cooperation").path("stage").asText());assertEquals(2,held(2,"tool"));assertFalse(world.getThemeRuntime().path("cooperation").has("locks"));
    }
    @Test void fourTeamsAcceptSeveralPlansAndTieRequiresRealConsensus() {
        addTeam(3);addTeam(4);propose("p1","leave");propose("p2","stay");intent(1,"p1","tool");intent(2,"p1","tool");intent(1,"p2","clue");intent(2,"p2","clue");
        call(1,"VOTE_OPEN",p());int round=world.getThemeRuntime().path("cooperation").path("round").asInt();
        for(long i=1;i<=4;i++)call(i,"VOTE_CAST",p().put("round",round).put("proposalKey",i<=2?"p1":"p2"));assertEquals("TIED",world.getThemeRuntime().path("cooperation").path("stage").asText());
        for(long i=1;i<=4;i++){ObjectNode p=p().put("round",round);p.putArray("proposalKeys").add("p1").add("p2");call(i,"ACCEPT_SET",p);}
        assertEquals("CONSENSUS",world.getThemeRuntime().path("cooperation").path("stage").asText());
        for(long i=1;i<=3;i++)call(i,"CONSENSUS_CAST",p().put("round",round).put("proposalKey","p1"));assertFalse(world.getThemeRuntime().path("cooperation").has("selected"));
        call(4,"CONSENSUS_CAST",p().put("round",round).put("proposalKey","p1"));assertEquals("p1",world.getThemeRuntime().path("cooperation").path("selected").asText());
    }
    @Test void votingFreezesBalancesAndOldRoundCannotConfirm() {
        propose("p1","leave");intent(1,"p1","tool");intent(2,"p1","tool");call(1,"VOTE_OPEN",p());assertTrue(engine.isVotingFrozen(world));
        assertThrows(IllegalStateException.class,()->call(1,"TRADE_OPEN",trade("swap",1,1)));
        inventory.consumeAll(theme,teams.get(1L),rules,"changed",amounts("tool",1));
        assertThrows(IllegalStateException.class,()->call(1,"VOTE_CAST",p().put("round",1).put("proposalKey","p1")));
        call(1,"PLAN_CHANGE",p());assertThrows(IllegalStateException.class,()->call(1,"PLAN_CONFIRM",p().put("round",1).put("proposalKey","p1")));
    }
    @Test void receiptRetryDoesNotTransferAgainAndConflictingContentFails() {
        ObjectNode p=trade("swap",1,1);ObjectNode original=engine.execute(theme,rules,world,teams,leaders,1L,10L,"TRADE_OPEN",p,"same");
        assertEquals(original,engine.execute(theme,rules,world,teams,leaders,1L,10L,"TRADE_OPEN",p,"same"));
        p.put("toTeamId",1);assertThrows(IllegalStateException.class,()->engine.execute(theme,rules,world,teams,leaders,1L,10L,"TRADE_OPEN",p,"same"));
    }
    @Test void clientBalancePassedAndExtraFieldsAreNeverTrusted() {
        ObjectNode p=trade("swap",1,1).put("balance",100);assertThrows(IllegalArgumentException.class,()->call(1,"TRADE_OPEN",p));
        assertThrows(IllegalArgumentException.class,()->call(1,"FACILITY_USE",p().put("facilityKey","shelter").put("passed",true)));
    }
    @Test void publicViewDoesNotExposePrivateInventoriesIntentOrSignerMemberIds() {
        propose("p1","leave");intent(1,"p1","tool");call(1,"TRADE_OPEN",trade("private",1,1));
        String view=engine.view(theme,world,teams,3L).toString();assertFalse(view.contains("fromSnapshot"));assertFalse(view.contains("memberId"));assertFalse(view.contains("intents"));assertEquals(0,engine.view(theme,world,teams,3L).path("trades").size());
    }
    @Test void tradeCatalogIncludesPublicUnheldItemsButNeverInventoryOrWearables() {
        theme.withArray("items").addObject().put("id","spare").put("kind","ITEM").put("name","备用物资").put("slotCost",2);
        theme.withArray("items").addObject().put("id","story").put("kind","CONTENT").put("name","内容");
        theme.withArray("items").addObject().put("id","gear").put("kind","ITEM").put("name","穿戴物资").put("wear",true);
        JsonNode catalog=engine.view(theme,world,teams,1L,leaders).path("itemCatalog");assertEquals(3,catalog.size());
        assertEquals(json.createObjectNode().put("id","spare").put("name","备用物资").put("slotCost",2),catalog.path(2));
        for(JsonNode item:catalog) {assertEquals(3,item.size());assertTrue(item.has("id"));assertTrue(item.has("name"));assertTrue(item.has("slotCost"));assertFalse(item.has("quantity"));}
        teams.get(1L).getThemeRuntime().with("inventory").put("tool",0);teams.get(2L).getThemeRuntime().with("inventory").put("tool",999);
        assertEquals(catalog,engine.view(theme,world,teams,2L,leaders).path("itemCatalog"));
        assertEquals(catalog,engine.view(theme,world,teams,null,leaders).path("itemCatalog"));
    }
    @Test void tradeTargetsDistinguishOwnAndCounterpartCompletionUsingEffectiveTargetPolicy() {
        for(JsonNode plan:theme.path("gameplayRuntime").path("plans"))((ObjectNode)plan).put("terminal",false);
        assertFalse(engine.view(theme,world,teams,1L,leaders).path("tradeTargets").path(0).path("completed").asBoolean());
        teams.get(2L).setStatus("COMPLETED");
        JsonNode counterpart=engine.view(theme,world,teams,1L,leaders).path("tradeTargets").path(0);
        assertTrue(counterpart.path("completed").asBoolean());assertEquals("COUNTERPART_COMPLETED",counterpart.path("disabledReason").asText());assertFalse(counterpart.path("enabled").asBoolean());
        JsonNode own=engine.view(theme,world,teams,2L,leaders).path("tradeTargets").path(0);
        assertFalse(own.path("completed").asBoolean());assertEquals("TEAM_COMPLETED",own.path("disabledReason").asText());
        teams.get(2L).getThemeRuntime().put("awaitingChallenge",true);
        assertEquals(!GameplayCooperationEngine.isEffectiveTarget(theme,teams.get(2L)),engine.view(theme,world,teams,1L,leaders).path("tradeTargets").path(0).path("completed").asBoolean());
    }
    @Test void reentryShowsOnlyOwnIntentAndCurrentCaptainSignatures() {
        addTeam(3);propose("private","leave");intent(1,"private","tool");intent(2,"private","tool");
        ObjectNode own=engine.view(theme,world,teams,1L,leaders);
        assertEquals(amounts("tool",1),own.path("proposals").path(0).path("ownIntent").path("amounts"));
        assertTrue(own.path("proposals").path(0).path("ownIntent").path("signatureCurrent").asBoolean());
        assertFalse(own.path("proposals").path(0).path("ownConfirmed").asBoolean());
        assertEquals(own,engine.view(theme,world,teams,1L,leaders));
        assertFalse(engine.view(theme,world,teams,3L,leaders).toString().contains("ownIntent"));
        assertFalse(engine.view(theme,world,teams,null,leaders).toString().contains("ownIntent"));
        assertFalse(own.toString().contains("memberId"));assertFalse(own.toString().contains("intents"));
        call(1,"TRADE_OPEN",trade("swap",1,1));call(1,"TRADE_CONFIRM",p().put("tradeKey","swap").put("version",1));
        assertTrue(engine.view(theme,world,teams,1L,leaders).path("trades").path(0).path("ownSigned").asBoolean());
        assertEquals(0,engine.view(theme,world,teams,3L,leaders).path("trades").size());
        leaders.put(1L,11L);
        JsonNode changed=engine.view(theme,world,teams,1L,leaders);
        assertFalse(changed.path("proposals").path(0).path("ownIntent").path("signatureCurrent").asBoolean());
        assertFalse(changed.path("trades").path(0).path("ownSigned").asBoolean());assertEquals(0,changed.path("trades").path(0).path("confirmedTeams").size());
        assertEquals(amounts("tool",1),changed.path("proposals").path(0).path("ownIntent").path("amounts"));
    }
    @Test void voteReadbackPreservesBallotTypesAndTracksCurrentOwnSignaturesAcrossPhases() throws Exception {
        JsonNode funding = engine.view(theme,world,teams,1L,leaders);
        assertEquals("FUNDING",funding.path("votePhase").asText());
        assertTrue(funding.path("plans").path(0).path("conditionsMet").asBoolean());
        assertTrue(funding.path("plans").path(0).path("actions").path("canFund").asBoolean());
        assertTrue(funding.path("ownVote").isNull());assertTrue(funding.path("ownAcceptance").isNull());
        propose("p1","leave");intent(1,"p1","tool");intent(2,"p1","tool");call(1,"VOTE_OPEN",p());
        JsonNode voting = engine.view(theme,world,teams,1L,leaders);
        assertEquals("VOTING",voting.path("votePhase").asText());assertFalse(voting.path("plans").path(0).path("actions").path("canFund").asBoolean());
        assertTrue(voting.path("plans").path(0).path("actions").path("canVote").asBoolean());
        call(1,"VOTE_CAST",selected("REJECT"));call(2,"VOTE_CAST",selected("REJECT"));
        assertTrue(engine.view(theme,world,teams,1L,leaders).path("plans").path(0).path("actions").path("canAccept").asBoolean());
        call(1,"ACCEPT_SET",p().put("round",1).set("proposalKeys",json.createArrayNode()));
        JsonNode accepting = engine.view(theme,world,teams,1L,leaders);
        assertEquals("ACCEPTANCE",accepting.path("votePhase").asText());
        assertTrue(accepting.path("votes").path("1").isTextual());assertTrue(accepting.path("acceptances").path("1").isArray());
        assertEquals(json.readTree("{\"planKey\":\"REJECT\",\"ownSignatureCurrent\":true}"),accepting.path("ownVote"));
        assertEquals(json.readTree("{\"planKeys\":[],\"ownSignatureCurrent\":true}"),accepting.path("ownAcceptance"));
        assertEquals(json.readTree("{\"endingKey\":\"leave\",\"title\":\"离开\",\"summary\":\"安全离开\",\"materialCost\":[{\"itemId\":\"tool\",\"quantity\":2}],\"operationNodeId\":99,\"conditionsMet\":true,\"proposalKey\":\"p1\",\"version\":1,\"actions\":{\"canFund\":false,\"canVote\":false,\"canAccept\":true,\"canExecute\":false,\"disabledReason\":null}}"),accepting.path("plans").path(0));
        assertFalse(accepting.toString().contains("memberId"));
        assertTrue(engine.view(theme,world,teams,2L,leaders).path("ownAcceptance").isNull());
        assertTrue(engine.view(theme,world,teams,null,leaders).path("ownMaterials").isEmpty());
        leaders.put(1L,11L);
        JsonNode changed = engine.view(theme,world,teams,1L,leaders);
        assertFalse(changed.path("ownVote").path("ownSignatureCurrent").asBoolean());
        assertFalse(changed.path("ownAcceptance").path("ownSignatureCurrent").asBoolean());
        assertEquals(accepting.path("votes"),changed.path("votes"));
    }
    @Test void voteReadbackProjectsOnlyOwnAvailableMaterialsAndNeverEnablesUnverifiedExecution() {
        call(1,"TRADE_OPEN",trade("reserved",1,0));call(1,"TRADE_CONFIRM",p().put("tradeKey","reserved").put("version",1));
        JsonNode own = engine.view(theme,world,teams,1L,leaders).path("ownMaterials").path(0);
        assertEquals("tool",own.path("itemId").asText());assertEquals(1,own.path("available").asInt());assertEquals(1,own.path("locked").asInt());
        assertEquals(2,engine.view(theme,world,teams,2L,leaders).path("ownMaterials").path(0).path("available").asInt());
        call(1,"TRADE_CANCEL",p().put("tradeKey","reserved").put("version",1));
        propose("p1","leave");intent(1,"p1","tool");intent(2,"p1","tool");select("p1");
        call(1,"PLAN_CONFIRM",selected("p1"));call(2,"PLAN_CONFIRM",selected("p1"));
        assertEquals("SELECTED",engine.view(theme,world,teams,1L,leaders).path("votePhase").asText());
        assertFalse(engine.view(theme,world,teams,1L,leaders).path("plans").path(0).path("actions").path("canExecute").asBoolean());
        teams.get(1L).getThemeRuntime().with("verifiedNodes").put("99",true);
        teams.get(1L).getThemeRuntime().with("operationFacts").putObject("PLAN:leave").put("round",1);
        assertTrue(engine.view(theme,world,teams,1L,leaders).path("plans").path(0).path("actions").path("canExecute").asBoolean());
        call(1,"PLAN_EXECUTE",selected("p1"));
        JsonNode ended = engine.view(theme,world,teams,1L,leaders);
        assertEquals("EXECUTED",ended.path("votePhase").asText());
        assertEquals("SESSION_ENDED",ended.path("plans").path(0).path("actions").path("disabledReason").asText());
    }
    @Test void unmetWorldConditionsBlockPlanReadbackWithoutLeakingOrChangingPrivateState() {
        ((ObjectNode)theme.path("gameplayRuntime").path("plans").path(0)).putArray("when").addObject()
                .put("op","ITEM_HELD").put("itemId","tool").put("quantity",1);
        String before = world.getThemeRuntime().toString();
        JsonNode plan = engine.view(theme,world,teams,1L,leaders).path("plans").path(0);
        assertFalse(plan.path("conditionsMet").asBoolean());
        assertFalse(plan.path("actions").path("canFund").asBoolean());
        assertEquals("RULE_BLOCKED",plan.path("actions").path("disabledReason").asText());
        assertEquals(before,world.getThemeRuntime().toString());
    }
    @Test void consensusReadbackAllowsVotesOnlyForTiedCandidatesWithUnchangedBalances() {
        addTeam(3);propose("p1","leave");propose("p2","stay");
        for(long team:new long[]{1,2}) {intent(team,"p1","tool");intent(team,"p2","clue");}
        theme.with("gameplayRuntime").withArray("plans").addObject().put("endingKey","other").put("operationNodeId",97).putArray("materialCost");
        propose("p3","other");call(1,"VOTE_OPEN",p());
        for(long team:new long[]{1,2,3})call(team,"VOTE_CAST",selected("REJECT"));
        for(long team:new long[]{1,2,3})call(team,"ACCEPT_SET",p().put("round",1).set("proposalKeys",json.createArrayNode().add("p1").add("p2")));
        JsonNode view = engine.view(theme,world,teams,1L,leaders);
        assertEquals("CONSENSUS",view.path("votePhase").asText());
        for(int index:new int[]{0,1})assertTrue(view.path("plans").path(index).path("actions").path("canVote").asBoolean());
        assertFalse(view.path("plans").path(2).path("actions").path("canVote").asBoolean());
        assertEquals("RULE_BLOCKED",view.path("plans").path(2).path("actions").path("disabledReason").asText());
        teams.get(2L).getThemeRuntime().with("inventory").put("tool",1);
        assertFalse(engine.view(theme,world,teams,1L,leaders).path("plans").path(0).path("actions").path("canVote").asBoolean());
    }
    @Test void tradePreviewIsPureAndChecksFinalCapacity() {
        JsonNode before=json.valueToTree(teams);ObjectNode w=world.getThemeRuntime().deepCopy();
        ObjectNode preview=engine.previewTrade(theme,rules,world,teams,1L,trade("preview",1,1));
        assertEquals(4,preview.path("fromUsed").asInt());assertEquals(4,preview.path("toUsed").asInt());
        assertEquals(before,json.valueToTree(teams));assertEquals(w,world.getThemeRuntime());
        assertThrows(IllegalStateException.class,()->engine.previewTrade(theme,rules,world,teams,1L,trade("over",1,0)));
    }

    @Test void ownConfirmationRequiresTheCurrentCaptainLock() {
        propose("confirmed","leave");intent(1,"confirmed","tool");intent(2,"confirmed","tool");select("confirmed");
        call(1,"PLAN_CONFIRM",selected("confirmed"));
        assertTrue(engine.view(theme,world,teams,1L,leaders).path("proposals").path(0).path("ownConfirmed").asBoolean());
        assertFalse(engine.view(theme,world,teams,2L,leaders).path("proposals").path(0).path("ownConfirmed").asBoolean());
        leaders.put(1L,11L);
        assertFalse(engine.view(theme,world,teams,1L,leaders).path("proposals").path(0).path("ownConfirmed").asBoolean());
    }
    @Test void usableNonwearFoodTransfersWithoutRestoringEitherOwner() {
        theme.withArray("items").addObject().put("id","food").put("kind","ITEM").put("name","口粮").put("slotCost",1).put("stat","HP").put("delta",2).put("wear",false);
        inventory.consumeAll(theme,teams.get(1L),rules,"space",amounts("tool",1));inventory.grantAll(theme,teams.get(1L),rules,"food",amounts("food",1));
        ObjectNode request=p().put("tradeKey","food_swap").put("toTeamId",2);request.set("give",amounts("food",1));request.set("take",amounts("clue",1));
        call(1,"TRADE_OPEN",request);call(1,"TRADE_CONFIRM",p().put("tradeKey","food_swap").put("version",1));call(2,"TRADE_CONFIRM",p().put("tradeKey","food_swap").put("version",1));
        assertEquals(1,held(2,"food"));assertEquals(0,held(1,"food"));assertEquals(8,teams.get(1L).getVariables().get("sys.hp"));assertEquals(8,teams.get(2L).getVariables().get("sys.hp"));
    }
    @Test void ownerFacilityPersistsLimitWithoutWorldMode() {
        theme.with("gameplayRuntime").put("worldMode","TEAM_INDEPENDENT");ObjectNode f=(ObjectNode)theme.path("gameplayRuntime").path("facilities").get(0);f.put("scope","OWNER").put("targetScope","OWNER");
        State owner=teams.get(1L);ObjectNode a=engine.ownerFacility(theme,rules,owner,1L,p().put("facilityKey","shelter"),"owner_one");
        ObjectNode b=engine.ownerFacility(theme,rules,owner,1L,p().put("facilityKey","shelter"),"owner_two");assertEquals(a,b);assertEquals(1,held(1,"tool"));assertEquals(10,owner.getVariables().get("sys.hp"));
    }
    @Test void explicitPublicFacilityFactUnlocksPlanWithoutPublishingPrivateCounter() {
        ObjectNode f=(ObjectNode)theme.path("gameplayRuntime").path("facilities").get(0);f.putArray("worldEffects").addObject().put("var","clue.open").put("op","SET").put("value",1);
        f.putArray("effects").addObject().put("var","counter.secret").put("op","SET").put("value",3);
        ((ObjectNode)theme.path("gameplayRuntime").path("plans").get(0)).putArray("when").addObject().put("var","clue.open").put("op","EQ").put("value",1);
        assertThrows(IllegalStateException.class,()->propose("before","leave"));call(1,"FACILITY_USE",p().put("facilityKey","shelter"));propose("after","leave");
        assertEquals(1,world.getVariables().get("clue.open"));assertFalse(world.getVariables().containsKey("counter.secret"));assertEquals(3,teams.get(1L).getVariables().get("counter.secret"));
    }
    @Test void newCaptainInvalidatesSelectedVotesBeforeEscrow() {
        propose("p1","leave");intent(1,"p1","tool");intent(2,"p1","tool");select("p1");leaders.put(2L,21L);
        assertThrows(IllegalStateException.class,()->call(1,"PLAN_CONFIRM",selected("p1")));assertEquals(0,engine.view(theme,world,teams,1L).path("locked").path("tool").asInt());
    }
    private void bindFacilityQuest() {
        ObjectNode g=theme.with("gameplayRuntime"),act=(ObjectNode)g.path("acts").get(0);act.putArray("questKeys").add("q1");act.putObject("completionRule").put("type","ALL");
        ObjectNode q=g.putArray("quests").addObject().put("questKey","q1").put("actKey","first").put("nodeId",101).put("settlementSlot","task_one").put("facilityKey","shelter").put("hpCost",2);q.set("resourceCosts",amounts("tool",1));
    }
    @Test void boundFacilityOptionFreezesAtQuestStartAndResolveInheritsIt() {
        bindFacilityQuest();ObjectNode f=(ObjectNode)theme.path("gameplayRuntime").path("facilities").get(0);f.remove("resourceCosts");
        ArrayNode options=f.putArray("costOptions");options.addObject().set("resourceCosts",amounts("tool",1));options.addObject().set("resourceCosts",amounts("clue",1));
        engine.executeQuest(theme,rules,world,teams,leaders,1L,10L,"q1","QUEST_START",null,"start","TEAM:1",1,null,null,1);
        teams.get(1L).getThemeRuntime().with("verifiedNodes").put("101",true);
        ObjectNode result=engine.executeQuest(theme,rules,world,teams,leaders,1L,10L,"q1","QUEST_RESOLVE",null,"resolve","TEAM:1",2);
        assertEquals(1,result.path("costOption").asInt());assertEquals(1,result.path("facilityResult").path("costOption").asInt());
        assertEquals(1,held(1,"tool"));assertEquals(1,held(1,"clue"));
    }


    @Test void freeTaskBindingStillSkipsPureRecoveryFeeWhenAllTargetsAreFull() {
        bindFacilityQuest();ObjectNode q=(ObjectNode)theme.path("gameplayRuntime").path("quests").get(0);q.remove("hpCost");q.remove("resourceCosts");
        ObjectNode f=(ObjectNode)theme.path("gameplayRuntime").path("facilities").get(0);f.set("resourceCosts",amounts("tool",99));
        teams.values().forEach(state->state.getVariables().put("sys.hp",12));
        engine.executeQuest(theme,rules,world,teams,leaders,1L,10L,"q1","QUEST_START",null,"full-start","TEAM:1",1);
        teams.get(1L).getThemeRuntime().with("verifiedNodes").put("101",true);
        ObjectNode result=engine.executeQuest(theme,rules,world,teams,leaders,1L,10L,"q1","QUEST_RESOLVE",null,"full-resolve","TEAM:1",2);
        assertTrue(result.path("facilityResult").path("noEffect").asBoolean());assertEquals(0,result.path("facilityResult").path("count").asInt());
        assertEquals(2,held(1,"tool"));assertEquals(12,teams.get(1L).getVariables().get("sys.hp"));assertFalse(world.getThemeRuntime().path("cooperation").path("facilities").has("warm"));
    }
    @Test void facilityAndQuestAreOneAtomicCompetitionAndLoserKeepsCostsAndSlot() {
        bindFacilityQuest();GameplayTaskEngine tasks=new GameplayTaskEngine();
        for(long team=1;team<=2;team++){tasks.execute(theme,rules,teams.get(team),"q1","QUEST_START",null,"start"+team,"TEAM:"+team,1);teams.get(team).getThemeRuntime().with("verifiedNodes").put("101",true);}
        ObjectNode winner=engine.executeQuest(theme,rules,world,teams,leaders,1L,10L,"q1","QUEST_RESOLVE",null,"winner","TEAM:1",2);assertTrue(winner.has("facilityResult"));assertEquals(0,held(1,"tool"));assertEquals(8,teams.get(1L).getVariables().get("sys.hp"));
        ObjectNode loser=engine.executeQuest(theme,rules,world,teams,leaders,2L,20L,"q1","QUEST_RESOLVE",null,"loser","TEAM:2",3);
        assertTrue(loser.path("competitionLost").asBoolean());assertFalse(loser.path("settled").asBoolean());assertEquals(2,held(2,"tool"));assertFalse(teams.get(2L).getCompletedNodeIds().contains(101L));assertFalse(teams.get(2L).getThemeRuntime().path("gameplay").path("slots").has("task_one"));assertFalse(teams.get(2L).getThemeRuntime().path("gameplay").has("activeQuest"));
    }
    @Test void jointTaskFeeCannotBePaidWithFacilityRewardAndFailureDoesNotClaimWorldSlot() {
        bindFacilityQuest();GameplayTaskEngine tasks=new GameplayTaskEngine();inventory.consumeAll(theme,teams.get(1L),rules,"insufficient",amounts("tool",1));
        tasks.execute(theme,rules,teams.get(1L),"q1","QUEST_START",null,"start","TEAM:1",1);teams.get(1L).getThemeRuntime().with("verifiedNodes").put("101",true);
        JsonNode before=json.valueToTree(teams);ObjectNode w=world.getThemeRuntime().deepCopy();assertThrows(IllegalStateException.class,()->engine.executeQuest(theme,rules,world,teams,leaders,1L,10L,"q1","QUEST_RESOLVE",null,"unaffordable","TEAM:1",2));
        assertEquals(before,json.valueToTree(teams));assertEquals(w,world.getThemeRuntime());
    }

    @Test void boundFacilityCannotBeUsedToBypassItsTaskFees() {
        bindFacilityQuest();assertThrows(IllegalStateException.class,()->call(1,"FACILITY_USE",p().put("facilityKey","shelter")));assertEquals(2,held(1,"tool"));assertFalse(world.getThemeRuntime().path("cooperation").path("facilities").has("warm"));
    }
    @Test void completedBoundTaskAllowsLaterToolUsesWithoutChargingTaskAgain() {
        bindFacilityQuest();((ObjectNode)theme.path("gameplayRuntime").path("facilities").get(0)).put("limit",2);
        assertThrows(IllegalStateException.class,()->call(1,"FACILITY_USE",p().put("facilityKey","shelter")));
        teams.get(1L).getThemeRuntime().with("gameplay").with("slots").putObject("task_one").put("settled",true);
        call(1,"FACILITY_USE",p().put("facilityKey","shelter"));
        call(1,"FACILITY_USE",p().put("facilityKey","shelter"));
        assertEquals(0,held(1,"tool"));assertEquals(12,teams.get(1L).getVariables().get("sys.hp"));
        assertEquals(2,world.getThemeRuntime().path("cooperation").path("facilities").path("warm").path("count").asInt());
        call(1,"FACILITY_USE",p().put("facilityKey","shelter"));assertEquals(0,held(1,"tool"));
    }
    @Test void intermediateJointDecisionReleasesLocksAndKeepsTheGameOpen() {
        ((ObjectNode)theme.path("gameplayRuntime").path("plans").get(0)).put("terminal",false);
        propose("middle","leave");intent(1,"middle","tool");intent(2,"middle","tool");select("middle");
        call(1,"PLAN_CONFIRM",selected("middle"));call(2,"PLAN_CONFIRM",selected("middle"));
        teams.get(1L).getThemeRuntime().with("verifiedNodes").put("99",true);
        ObjectNode result=call(1,"PLAN_EXECUTE",selected("middle"));
        assertFalse(result.path("terminal").asBoolean());assertFalse(world.getThemeRuntime().path("cooperation").has("result"));
        assertEquals("DRAFT",world.getThemeRuntime().path("cooperation").path("stage").asText());
        assertEquals(0,world.getThemeRuntime().path("cooperation").path("locks").size());assertEquals(1,held(1,"tool"));
        assertThrows(IllegalStateException.class,()->propose("again","leave"));
        call(1,"TRADE_OPEN",trade("after",1,1));assertTrue(engine.view(theme,world,teams,1L).has("lastOperationResult"));
        assertFalse(engine.view(theme,world,teams,1L).path("lastOperationResult").toString().contains("memberId"));
    }
    @Test void participantsFinishFreeTasksWithChapterCostsBeforeRestoration() {
        bindFacilityQuest();ObjectNode q=(ObjectNode)theme.path("gameplayRuntime").path("quests").get(0);q.remove("facilityKey");q.remove("resourceCosts");q.put("hpCost",0);
        ObjectNode f=(ObjectNode)theme.path("gameplayRuntime").path("facilities").get(0);f.put("targetScope","PARTICIPANTS").put("operationNodeId",101);
        ObjectNode chapter=theme.putArray("chapters").addObject().put("index",0).put("hpCost",3);chapter.putObject("binding").putArray("nodeIds").add(101);
        GameplayTaskEngine tasks=new GameplayTaskEngine();
        for(long id=1;id<=2;id++){tasks.execute(theme,rules,teams.get(id),"q1","QUEST_START",null,"start"+id,"TEAM:"+id,1);teams.get(id).getThemeRuntime().with("verifiedNodes").put("101",true);}
        ObjectNode payload=p().put("facilityKey","shelter");payload.putArray("participants").add(1).add(2);
        call(1,"FACILITY_USE",payload);
        assertTrue(teams.get(1L).getCompletedNodeIds().contains(101L));assertTrue(teams.get(2L).getCompletedNodeIds().contains(101L));
        assertEquals(7,teams.get(1L).getVariables().get("sys.hp"));assertEquals(7,teams.get(2L).getVariables().get("sys.hp"));assertEquals(1,held(1,"tool"));
    }
    @Test void participantOperationEffectsReachOwnersWithTheirOwnPendingTaskEffectsOnce() {
        bindFacilityQuest();rules.getHp().setMax(20);
        ObjectNode quest=(ObjectNode)theme.path("gameplayRuntime").path("quests").get(0);
        quest.remove("facilityKey");quest.remove("resourceCosts");quest.put("hpCost",0);
        ObjectNode facility=(ObjectNode)theme.path("gameplayRuntime").path("facilities").get(0);
        facility.put("targetScope","PARTICIPANTS").put("operationNodeId",101);facility.remove("hpDelta");
        GameplayTaskEngine tasks=new GameplayTaskEngine();
        for(long id=1;id<=2;id++) {
            tasks.execute(theme,rules,teams.get(id),"q1","QUEST_START",null,"start"+id,"TEAM:"+id,1);
            teams.get(id).getThemeRuntime().with("verifiedNodes").put("101",true);
            teams.get(id).getThemeRuntime().with("gameplay").with("pendingTaskEffects").putObject("q1").putArray("effects");
        }
        teams.get(1L).getThemeRuntime().with("operationFacts").putObject("FACILITY:shelter").putArray("effects")
                .addObject().put("var","sys.hp").put("op","INC").put("value",2);
        ObjectNode payload=p().put("facilityKey","shelter");payload.putArray("participants").add(1).add(2);
        call(1,"FACILITY_USE",payload);
        for(State state:teams.values())assertEquals(10,state.getVariables().get("sys.hp").intValue());
        assertEquals(1,held(1,"tool"));assertEquals(2,held(2,"tool"));
    }
    @Test void executingOneGeneratedOptionExcludesItsOpenSiblingFromNextNodeVote() {
        ObjectNode game=theme.with("gameplayRuntime");ArrayNode acts=game.putArray("acts");ArrayNode quests=game.putArray("quests");
        for(int index=1;index<=2;index++) {
            ObjectNode act=acts.addObject().put("actKey","act_"+index);act.putArray("questKeys").add("quest_"+index);act.putObject("completionRule").put("type","ALL");
            quests.addObject().put("questKey","quest_"+index).put("actKey","act_"+index).put("nodeId",100+index).put("locationKey","node_"+index).put("settlementSlot","quest_"+index);
        }
        ArrayNode provenance=game.putObject("templateTools").putArray("nodes");
        ObjectNode first=provenance.addObject().put("nodeId",101).put("votePolicy","MAJORITY_ACCEPTANCE");first.putArray("planKeys").add("tpl_vote_101_1").add("tpl_vote_101_2");
        ObjectNode second=provenance.addObject().put("nodeId",102).put("votePolicy","MAJORITY_ACCEPTANCE");second.putArray("planKeys").add("tpl_vote_102_1");
        for(String key:Arrays.asList("tpl_vote_101_1","tpl_vote_101_2","tpl_vote_102_1")) {
            ObjectNode plan=game.withArray("plans").addObject().put("endingKey",key).put("title",key).put("operationNodeId",key.startsWith("tpl_vote_101")?101:102).put("terminal",false);
            plan.set("materialCost",amounts(key.startsWith("tpl_vote_101")?"tool":"clue",2));
        }
        GameplayTaskEngine tasks=new GameplayTaskEngine();
        for(Long team:teams.keySet())tasks.execute(theme,rules,teams.get(team),"quest_1","QUEST_START",null,"start_1_"+team,"TEAM:"+team,1);
        propose("first","tpl_vote_101_1");propose("sibling","tpl_vote_101_2");
        for(Long team:teams.keySet()){intent(team,"first","tool");intent(team,"sibling","tool");}
        select("first");for(Long team:teams.keySet())call(team,"PLAN_CONFIRM",selected("first"));
        teams.get(1L).getThemeRuntime().with("verifiedNodes").put("101",true);call(1,"PLAN_EXECUTE",selected("first"));
        assertEquals("OPEN",world.getThemeRuntime().path("cooperation").path("proposals").path("sibling").path("status").asText());
        for(Long team:teams.keySet())tasks.execute(theme,rules,teams.get(team),"quest_2","QUEST_START",null,"start_2_"+team,"TEAM:"+team,2);
        propose("next","tpl_vote_102_1");for(Long team:teams.keySet())intent(team,"next","clue");
        call(1,"VOTE_OPEN",p());
        assertEquals(json.createArrayNode().add("next"),world.getThemeRuntime().path("cooperation").path("candidates"));
        assertFalse(world.getThemeRuntime().path("cooperation").has("result"));
        assertTrue(theme.path("gameplayRuntime").path("plans").get(0).path("terminal").asBoolean(true));
    }
    @Test void jointPreviewVersionWithoutDiscardIsStillValidated() {
        bindFacilityQuest();GameplayTaskEngine tasks=new GameplayTaskEngine();tasks.execute(theme,rules,teams.get(1L),"q1","QUEST_START",null,"start","TEAM:1",1);teams.get(1L).getThemeRuntime().with("verifiedNodes").put("101",true);
        assertThrows(IllegalStateException.class,()->engine.executeQuest(theme,rules,world,teams,leaders,1L,10L,"q1","QUEST_RESOLVE",null,"bad_preview","TEAM:1",2,json.createArrayNode(),"stale"));assertEquals(2,held(1,"tool"));
    }
    @Test void tradeAndBallotSnapshotsSurviveJsonDatabaseRoundTrip() throws Exception {
        call(1,"TRADE_OPEN",trade("swap",1,1));world.setThemeRuntime((ObjectNode)json.readTree(world.getThemeRuntime().toString()));
        call(1,"TRADE_CONFIRM",p().put("tradeKey","swap").put("version",1));call(2,"TRADE_CONFIRM",p().put("tradeKey","swap").put("version",1));assertEquals(1,held(1,"tool"));
        propose("p1","leave");intent(1,"p1","tool");intent(2,"p1","tool");call(1,"VOTE_OPEN",p());world.setThemeRuntime((ObjectNode)json.readTree(world.getThemeRuntime().toString()));
        call(1,"VOTE_CAST",p().put("round",1).put("proposalKey","p1"));call(2,"VOTE_CAST",p().put("round",1).put("proposalKey","p1"));assertEquals("SELECTED",world.getThemeRuntime().path("cooperation").path("stage").asText());
    }

    @Test void jointFacilityRecoveryAfterTaskFeeUsesFinalNetStatOnce() {
        bindFacilityQuest();rules.setStateEnabled(true);rules.getAttributes().addObject().put("key","counter.temperature").put("label","体温").put("initial",10).put("min",0).put("max",10).put("visible",false);
        ObjectNode link=rules.getStatLinks().addObject().put("id","drain").put("sourceKey","counter.temperature").put("targetKey","sys.hp").put("trigger","PER_POINT").put("frequency","EVENT").put("multiplier",2);link.putArray("allowedReasons").add("TASK_COST").add("FACILITY_EFFECT");
        ObjectNode q=(ObjectNode)theme.path("gameplayRuntime").path("quests").get(0);q.put("hpCost",0);q.putArray("statCosts").addObject().put("statKey","counter.temperature").put("amount",4);
        ObjectNode f=(ObjectNode)theme.path("gameplayRuntime").path("facilities").get(0);f.remove("hpDelta");f.putArray("statChanges").addObject().put("statKey","counter.temperature").put("delta",2);
        for(State state:teams.values())state.getVariables().put("counter.temperature",10);
        State actor=teams.get(1L);GameplayTaskEngine tasks=new GameplayTaskEngine();tasks.execute(theme,rules,actor,"q1","QUEST_START",null,"start","TEAM:1",1);actor.getThemeRuntime().with("verifiedNodes").put("101",true);
        engine.executeQuest(theme,rules,world,teams,leaders,1L,10L,"q1","QUEST_RESOLVE",null,"joint_net","TEAM:1",2);
        assertEquals(8,teams.get(1L).getVariables().get("counter.temperature"));assertEquals(4,teams.get(1L).getVariables().get("sys.hp"));
        assertEquals(10,teams.get(2L).getVariables().get("counter.temperature"));
    }

    @Test void lostFacilityStartCannotCancelOtherActiveTaskAndUnknownActionCannotReleaseIt() {
        bindFacilityQuest();ObjectNode game=theme.with("gameplayRuntime");
        ((ObjectNode)game.path("acts").get(0)).withArray("questKeys").add("other");
        game.withArray("quests").addObject().put("questKey","other").put("actKey","first").put("nodeId",102).put("settlementSlot","other_slot");
        State state=teams.get(2L);new GameplayTaskEngine().execute(theme,rules,state,"other","QUEST_START",null,"start_other","TEAM:2",1);
        state.getThemeRuntime().with("verifiedNodes").put("102",true);state.getThemeRuntime().with("gameplay").put("activeQuestReceiptId","other_instance");
        state.getThemeRuntime().with("gameplay").with("pendingTaskEffects").putObject("other").putObject("sources").put("other_instance",true);
        world.getThemeRuntime().with("cooperation").with("facilities").putObject("warm").put("count",1).putObject("result").put("facilityKey","shelter");
        JsonNode before=json.valueToTree(teams),worldBefore=json.valueToTree(world);
        assertThrows(IllegalStateException.class,()->engine.executeQuest(theme,rules,world,teams,leaders,2L,20L,"q1","QUEST_START",null,"lost_other","TEAM:2",2));
        assertThrows(IllegalArgumentException.class,()->engine.executeQuest(theme,rules,world,teams,leaders,2L,20L,"q1","UNRECOGNIZED",null,"invalid","TEAM:2",2));
        assertEquals(before,json.valueToTree(teams));assertEquals(worldBefore,json.valueToTree(world));assertEquals("other",state.getThemeRuntime().path("gameplay").path("activeQuest").asText());
    }

    @Test void lostFacilityClearsOnlyThatTaskProofAndPendingEffectsWithoutCost() {
        bindFacilityQuest();State state=teams.get(2L);new GameplayTaskEngine().execute(theme,rules,state,"q1","QUEST_START",null,"start","TEAM:2",1);
        ObjectNode progress=state.getThemeRuntime().with("gameplay");progress.put("activeQuestReceiptId","loser_instance");
        ObjectNode pending=progress.with("pendingTaskEffects");pending.putObject("q1").putArray("effects");pending.putObject("unrelated").put("keep",true);
        state.getThemeRuntime().with("verifiedNodes").put("101",true).put("102",true);state.getVariables().put("sys.passed.101",1);state.getVariables().put("sys.passed.102",1);
        state.getThemeRuntime().with("operationFacts").putObject("FACILITY:shelter").put("sourceNodeId",101);
        world.getThemeRuntime().with("cooperation").with("facilities").putObject("warm").put("count",1).putObject("result").put("facilityKey","shelter");
        ObjectNode result=engine.executeQuest(theme,rules,world,teams,leaders,2L,20L,"q1","QUEST_RESOLVE",null,"lost","TEAM:2",2);
        assertTrue(result.path("competitionLost").asBoolean());assertEquals(8,state.getVariables().get("sys.hp"));assertEquals(2,held(2,"tool"));
        progress=state.getThemeRuntime().with("gameplay");assertFalse(progress.has("activeQuest"));assertFalse(progress.has("activeQuestReceiptId"));assertFalse(progress.path("pendingTaskEffects").has("q1"));assertTrue(progress.path("pendingTaskEffects").has("unrelated"));
        assertFalse(state.getThemeRuntime().path("verifiedNodes").has("101"));assertTrue(state.getThemeRuntime().path("verifiedNodes").has("102"));assertFalse(state.getVariables().containsKey("sys.passed.101"));assertEquals(1,state.getVariables().get("sys.passed.102"));
        assertFalse(state.getThemeRuntime().path("operationFacts").has("FACILITY:shelter"));assertFalse(progress.path("slots").has("task_one"));assertFalse(state.getCompletedNodeIds().contains(101L));
    }

    private ObjectNode ownerOperationFacility(String allowedReason,int cost,int effect) {
        rules.setStateEnabled(true);rules.getAttributes().addObject().put("key","counter.temperature").put("label","体温").put("initial",8).put("min",0).put("max",10).put("visible",true);
        ObjectNode link=rules.getStatLinks().addObject().put("id","operation_drain").put("sourceKey","counter.temperature").put("targetKey","sys.hp").put("trigger","PER_POINT").put("frequency","EVENT").put("multiplier",1);link.putArray("allowedReasons").add(allowedReason);
        theme.with("gameplayRuntime").put("runtimeScope","PLAYER").remove("worldMode");
        ObjectNode facility=(ObjectNode)theme.path("gameplayRuntime").path("facilities").get(0);facility.put("scope","OWNER").put("targetScope","OWNER").put("operationNodeId",101);facility.remove("hpDelta");
        if(cost>0)facility.putArray("statCosts").addObject().put("statKey","counter.temperature").put("amount",cost);
        State state=teams.get(1L);state.getVariables().put("counter.temperature",8);state.getThemeRuntime().with("verifiedNodes").put("101",true);
        state.getThemeRuntime().with("operationFacts").putObject("FACILITY:shelter").putArray("effects").addObject().put("var","counter.temperature").put("op","INC").put("value",effect);
        return p().put("facilityKey","shelter");
    }

    @Test void ownerFacilityFeeAndTrustedRestorationUseOneFinalNetLinkAndRetryDoesNotReapply() {
        ObjectNode payload=ownerOperationFacility("FACILITY_EFFECT",4,2);State state=teams.get(1L);
        ObjectNode receipt=engine.ownerFacility(theme,rules,state,1L,payload,"owner_operation");
        assertEquals(6,state.getVariables().get("counter.temperature"));assertEquals(6,state.getVariables().get("sys.hp"));assertEquals(1,state.getThemeRuntime().path("statLinks").path("audit").size());
        assertFalse(state.getThemeRuntime().path("operationFacts").path("FACILITY:shelter").has("effects"));
        JsonNode before=json.valueToTree(state);assertEquals(receipt,engine.ownerFacility(theme,rules,state,1L,payload,"owner_operation"));assertEquals(before,json.valueToTree(state));
    }

    @Test void ownerFacilityTrustedDeclineUsesTemplateReasonAndDeclaredCostCannotBorrowIt() {
        ObjectNode payload=ownerOperationFacility("TEMPLATE_EFFECT",0,-2);State state=teams.get(1L);
        engine.ownerFacility(theme,rules,state,1L,payload,"template_operation");assertEquals(6,state.getVariables().get("counter.temperature"));assertEquals(6,state.getVariables().get("sys.hp"));
        assertEquals("TEMPLATE_EFFECT",state.getThemeRuntime().path("statLinks").path("events").path("template_operation").path("counter.temperature").get(0).asText());
    }

    @Test void ownerFacilityDeclaredCostDoesNotTriggerTemplateOnlyRuleWhenOperationRestores() {
        ObjectNode payload=ownerOperationFacility("TEMPLATE_EFFECT",4,2);State state=teams.get(1L);
        engine.ownerFacility(theme,rules,state,1L,payload,"facility_only");assertEquals(6,state.getVariables().get("counter.temperature"));assertEquals(8,state.getVariables().get("sys.hp"));
        assertEquals(0,state.getThemeRuntime().path("statLinks").path("audit").size());
    }

    @Test void ownerFacilityTrustedDeclineClampsBeforeNetLinkInsteadOfRejectingBelowMinimum() {
        ObjectNode payload=ownerOperationFacility("TEMPLATE_EFFECT",0,-20);State state=teams.get(1L);
        engine.ownerFacility(theme,rules,state,1L,payload,"template_floor");
        assertEquals(0,state.getVariables().get("counter.temperature"));assertEquals(0,state.getVariables().get("sys.hp"));
        assertEquals(8,state.getThemeRuntime().path("statLinks").path("audit").get(0).path("actualDamage").asInt());
    }

    private ObjectNode sharedOperationFacility(String allowedReason,int cost,int effect) {
        ObjectNode payload=ownerOperationFacility(allowedReason,cost,effect);
        theme.with("gameplayRuntime").put("runtimeScope","TEAM").put("worldMode","WORLD_SHARED");
        ((ObjectNode)theme.path("gameplayRuntime").path("facilities").get(0)).put("scope","WORLD");
        for(State state:teams.values())state.getVariables().put("counter.temperature",8);
        return payload;
    }

    @Test void sharedFacilityTrustedDeclineUsesTemplateOnlyRuleAndRetryKeepsOriginalResult() {
        ObjectNode payload=sharedOperationFacility("TEMPLATE_EFFECT",0,-2);
        ObjectNode receipt=engine.execute(theme,rules,world,teams,leaders,1L,10L,"FACILITY_USE",payload,"shared_template");State actor=teams.get(1L);
        assertEquals(6,actor.getVariables().get("counter.temperature"));assertEquals(6,actor.getVariables().get("sys.hp"));assertEquals(1,actor.getThemeRuntime().path("statLinks").path("audit").size());
        assertEquals("TEMPLATE_EFFECT",actor.getThemeRuntime().path("statLinks").path("events").path("shared_template").path("counter.temperature").get(0).asText());
        JsonNode before=json.valueToTree(teams);assertEquals(receipt,engine.execute(theme,rules,world,teams,leaders,1L,10L,"FACILITY_USE",payload,"shared_template"));assertEquals(before,json.valueToTree(teams));assertEquals(8,teams.get(2L).getVariables().get("sys.hp"));
    }

    @Test void sharedFacilityFeeAndOperationRestorationLinkFinalNetOnce() {
        sharedOperationFacility("FACILITY_EFFECT",4,2);call(1,"FACILITY_USE",p().put("facilityKey","shelter"));State actor=teams.get(1L);
        assertEquals(6,actor.getVariables().get("counter.temperature"));assertEquals(6,actor.getVariables().get("sys.hp"));assertEquals(1,actor.getThemeRuntime().path("statLinks").path("audit").size());
    }

    @Test void jointDeferredFacilityTrustedDeclineKeepsTemplateReason() {
        sharedOperationFacility("TEMPLATE_EFFECT",0,-2);bindFacilityQuest();ObjectNode quest=(ObjectNode)theme.path("gameplayRuntime").path("quests").get(0);quest.put("hpCost",0);
        State actor=teams.get(1L);new GameplayTaskEngine().execute(theme,rules,actor,"q1","QUEST_START",null,"start_joint","TEAM:1",1);actor.getThemeRuntime().with("verifiedNodes").put("101",true);
        engine.executeQuest(theme,rules,world,teams,leaders,1L,10L,"q1","QUEST_RESOLVE",null,"joint_template","TEAM:1",2);actor=teams.get(1L);
        assertEquals(6,actor.getVariables().get("counter.temperature"));assertEquals(6,actor.getVariables().get("sys.hp"));assertEquals(1,actor.getThemeRuntime().path("statLinks").path("audit").size());
        assertEquals("TEMPLATE_EFFECT",actor.getThemeRuntime().path("statLinks").path("events").path("joint_template").path("counter.temperature").get(0).asText());
    }

    @Test void jointDeferredFacilityCostsAndOperationRestorationUseFinalNetWithoutBorrowingTemplateReason() {
        sharedOperationFacility("FACILITY_EFFECT",4,2);bindFacilityQuest();ObjectNode quest=(ObjectNode)theme.path("gameplayRuntime").path("quests").get(0);quest.put("hpCost",0);
        State actor=teams.get(1L);new GameplayTaskEngine().execute(theme,rules,actor,"q1","QUEST_START",null,"start_net","TEAM:1",1);actor.getThemeRuntime().with("verifiedNodes").put("101",true);
        engine.executeQuest(theme,rules,world,teams,leaders,1L,10L,"q1","QUEST_RESOLVE",null,"joint_facility_net","TEAM:1",2);actor=teams.get(1L);
        assertEquals(6,actor.getVariables().get("counter.temperature"));assertEquals(6,actor.getVariables().get("sys.hp"));assertEquals(1,actor.getThemeRuntime().path("statLinks").path("audit").size());
    }

}
