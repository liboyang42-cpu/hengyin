package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ThemeRuntimeSettlementTest {
    private final ObjectMapper json=new ObjectMapper();
    private final ThemeRuntimeEngine engine=new ThemeRuntimeEngine();
    private ObjectNode config() {
        ObjectNode c=json.createObjectNode().put("schemaVersion",1).put("capacity",6).put("actionLimit",8);
        ArrayNode items=c.putArray("items");
        items.addObject().put("id","part").put("kind","ITEM").put("name","部件").put("slotCost",2);
        items.addObject().put("id","food").put("kind","ITEM").put("name","补给").put("stat","HP").put("delta",3);
        items.addObject().put("id","once").put("kind","ITEM").put("name","一次装备").put("statKey","counter.focus").put("delta",2).put("wear",true).put("mode","ONCE");
        items.addObject().put("id","held").put("kind","ITEM").put("name","持续装备").put("statKey","counter.focus").put("delta",2).put("wear",true).put("mode","HELD");
        return ThemeRuntimeConfig.parseDraft(c.toString());
    }
    private JourneyRules rules(){return JourneyRules.parse("{\"schemaVersion\":1,\"checkEnabled\":true,\"stateEnabled\":true,\"attributes\":[{\"key\":\"counter.focus\",\"label\":\"专注\",\"initial\":4,\"min\":0,\"max\":10}]}");}
    private TopicRouteEngine.State state(ObjectNode config) {
        TopicRouteEngine.State s=new TopicRouteEngine.State();engine.initialize(config,s);JourneyInitialState.initialize(rules(),s);s.getVariables().put("sys.hp",5);s.getVariables().put("sys.luck",2);return s;
    }
    private ArrayNode amounts(String item,int qty){ArrayNode a=json.createArrayNode();a.addObject().put("itemId",item).put("quantity",qty);return a;}
    private ObjectNode costs(){return json.createObjectNode();}
    private String serialized(TopicRouteEngine.State s) throws Exception{return json.writeValueAsString(s);}

    @Test void fullBagCanPayOwnedMaterialAndReceiveWholeRewardAfterwards() {
        ObjectNode c=config();TopicRouteEngine.State s=state(c);engine.grantAll(c,s,rules(),"initial",amounts("part",3));
        ObjectNode costs=costs().put("hpCost",1).put("actionCost",1);costs.set("resourceCosts",amounts("part",2));
        ObjectNode result=engine.settle(c,s,rules(),"quest:first",costs,amounts("once",2));
        assertEquals(4,result.path("used").asInt());assertEquals(4,s.getVariables().get("sys.hp"));assertEquals(8,s.getVariables().get("counter.focus"));assertEquals(1,s.getVariables().get("sys.actions"));
        assertEquals(1,s.getThemeRuntime().path("inventory").path("part").asInt());assertEquals(3,s.getThemeRuntime().path("acquired").path("part").asInt());assertTrue(s.getThemeRuntime().path("pending").isEmpty());
    }
    @Test void overflowingWholeRewardLeavesCostsOnceEffectAndSlotUntouched() throws Exception {
        ObjectNode c=config();TopicRouteEngine.State s=state(c);engine.grantAll(c,s,rules(),"initial",amounts("part",2));String before=serialized(s);
        ObjectNode costs=costs().put("hpCost",1).put("actionCost",1);ArrayNode rewards=amounts("once",1);rewards.addObject().put("itemId","part").put("quantity",1);
        assertThrows(IllegalStateException.class,()->engine.settle(c,s,rules(),"overflow",costs,rewards));assertEquals(before,serialized(s));
    }
    @Test void missingLastMaterialDoesNotPartlyConsumeFirstMaterial() throws Exception {
        ObjectNode c=config();TopicRouteEngine.State s=state(c);engine.grantAll(c,s,rules(),"initial",amounts("part",1));String before=serialized(s);
        ArrayNode paid=amounts("part",1);paid.addObject().put("itemId","food").put("quantity",1);
        assertThrows(IllegalStateException.class,()->engine.consumeAll(c,s,rules(),"missing",paid));assertEquals(before,serialized(s));
    }
    @Test void invalidLastRewardDoesNotApplyEarlierOnceEffect() throws Exception {
        ObjectNode c=config();((ObjectNode)c.path("items").get(3)).put("statKey","counter.missing");TopicRouteEngine.State s=state(c);String before=serialized(s);
        ArrayNode rewards=amounts("once",1);rewards.addObject().put("itemId","held").put("quantity",1);
        assertThrows(IllegalArgumentException.class,()->engine.grantAll(c,s,rules(),"bad",rewards));assertEquals(before,serialized(s));
    }
    @Test void settlementSlotReplaysAndChangedContentCannotChargeAgain() throws Exception {
        ObjectNode c=config();TopicRouteEngine.State s=state(c);ObjectNode costs=costs().put("hpCost",1).put("actionCost",1);
        ObjectNode receipt=engine.settle(c,s,rules(),"once",costs,amounts("food",1));String after=serialized(s);
        assertEquals(receipt,engine.settle(c,s,rules(),"once",costs,amounts("food",1)));assertEquals(after,serialized(s));
        assertThrows(IllegalStateException.class,()->engine.settle(c,s,rules(),"once",costs,amounts("food",2)));assertEquals(after,serialized(s));
    }
    @Test void eighthActionAndZeroCostAtHpZeroWorkButNinthPaidActionFails() throws Exception {
        ObjectNode c=config();TopicRouteEngine.State s=state(c);s.getVariables().put("sys.actions",7);s.getVariables().put("sys.hp",1);
        engine.settle(c,s,rules(),"eighth",costs().put("hpCost",1).put("actionCost",1),json.createArrayNode());assertEquals(0,s.getVariables().get("sys.hp"));assertEquals(8,s.getVariables().get("sys.actions"));
        engine.settle(c,s,rules(),"free_final",costs(),json.createArrayNode());String before=serialized(s);
        assertThrows(IllegalStateException.class,()->engine.settle(c,s,rules(),"ninth",costs().put("actionCost",1),json.createArrayNode()));assertEquals(before,serialized(s));
    }
    @Test void rewardsCannotBeUsedToPayUnownedCostAndHpShortageKeepsEverything() throws Exception {
        ObjectNode c=config();TopicRouteEngine.State s=state(c);String before=serialized(s);ObjectNode cost=costs();cost.set("resourceCosts",amounts("food",1));
        assertThrows(IllegalStateException.class,()->engine.settle(c,s,rules(),"borrow",cost,amounts("food",1)));assertEquals(before,serialized(s));
        assertThrows(IllegalStateException.class,()->engine.settle(c,s,rules(),"hp_short",costs().put("hpCost",6),amounts("once",1)));assertEquals(before,serialized(s));
    }
    @Test void independentCostsAndMaterialUseOneInventoryAndOneAttributeMap() {
        ObjectNode c=config();TopicRouteEngine.State s=state(c);engine.grantAll(c,s,rules(),"initial",amounts("held",1));assertEquals(6,s.getVariables().get("counter.focus"));
        ObjectNode costs=costs();costs.putArray("statCosts").addObject().put("statKey","counter.focus").put("amount",2);
        engine.settle(c,s,rules(),"independent",costs,json.createArrayNode());assertEquals(4,s.getVariables().get("counter.focus"));
        engine.consumeAll(c,s,rules(),"take_off",amounts("held",1));assertEquals(2,s.getVariables().get("counter.focus"));assertEquals(1,s.getThemeRuntime().path("acquired").path("held").asInt());
    }
    @Test void spentHeldMaterialCannotPayWithTheRemovedBonus() throws Exception {
        ObjectNode c=config();TopicRouteEngine.State s=state(c);engine.grantAll(c,s,rules(),"initial",amounts("held",1));
        ObjectNode cost=costs();cost.set("resourceCosts",amounts("held",1));cost.putArray("statCosts").addObject().put("statKey","counter.focus").put("amount",5);
        String before=serialized(s);assertThrows(IllegalStateException.class,()->engine.settle(c,s,rules(),"spent_bonus",cost,json.createArrayNode()));assertEquals(before,serialized(s));
    }

    @Test void bulkKeepsLegacyMedalsUnconsumableAndUsesNumericValidation() throws Exception {
        ObjectNode c=config();c.withArray("items").addObject().put("id","medal").put("name","收藏").put("kind","MEDAL");TopicRouteEngine.State s=state(c);engine.grantAll(c,s,rules(),"award",amounts("medal",1));String before=serialized(s);
        assertEquals(0,engine.used(c,s));assertThrows(IllegalArgumentException.class,()->engine.consumeAll(c,s,rules(),"spend",amounts("medal",1)));assertEquals(before,serialized(s));
        ArrayNode bad=amounts("food",1);((ObjectNode)bad.get(0)).put("quantity",1.5);assertThrows(IllegalArgumentException.class,()->engine.grantAll(c,s,rules(),"fraction",bad));assertEquals(before,serialized(s));
        ArrayNode duplicate=amounts("food",1);duplicate.add(duplicate.get(0).deepCopy());assertThrows(IllegalArgumentException.class,()->engine.grantAll(c,s,rules(),"duplicate",duplicate));assertEquals(before,serialized(s));
    }
    @Test void allOncesAndHeldRewardEffectsUseTheOriginalLedger() throws Exception {
        ObjectNode c=config();TopicRouteEngine.State s=state(c);ArrayNode rewards=amounts("once",1);rewards.addObject().put("itemId","held").put("quantity",1);
        engine.grantAll(c,s,rules(),"bundle",rewards);assertEquals(8,s.getVariables().get("counter.focus"));engine.changeStat(s,rules(),"counter.focus",-3);engine.reconcileHeld(c,s,rules());assertEquals(5,s.getVariables().get("counter.focus"));
        TopicRouteEngine.State reload=json.readValue(serialized(s),TopicRouteEngine.State.class);engine.reconcileHeld(c,reload,rules());assertEquals(5,reload.getVariables().get("counter.focus"));
    }
}
