package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class ThemeRuntimeNumericExtensionTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ThemeRuntimeEngine engine = new ThemeRuntimeEngine();
    @Test void genericValuesUseExistingHpItemsWithoutEnablingSkillChecks() {
        JourneyRules rules=rules();rules.setCheckEnabled(false);rules.setStateEnabled(true);
        ObjectNode config=config();config.putObject("gameplayRuntime").put("runtimeScope","PLAYER");
        assertDoesNotThrow(()->ThemeRuntimeConfig.assertJourneyReferences(config,rules));
        TopicRouteEngine.State state=state(config);
        ArrayNode grants=json.createArrayNode();grants.addObject().put("itemId","ration").put("quantity",1);
        engine.grantAll(config,state,rules,"start",grants);
        engine.use(config,state,rules,"ration");
        assertEquals(11,state.getVariables().get("sys.hp"));
        assertEquals(0,state.getThemeRuntime().path("inventory").path("ration").asInt());
        ObjectNode legacy=config.deepCopy();legacy.remove("gameplayRuntime");
        assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.assertJourneyReferences(legacy,rules));
        assertThrows(IllegalStateException.class,()->engine.grantAll(legacy,state(legacy),rules,"start",grants));
    }
    private JourneyRules rules() {
        return JourneyRules.parse("{\"schemaVersion\":1,\"checkEnabled\":true,\"stateEnabled\":true,\"hp\":{\"init\":8,\"max\":12},\"attributes\":[{\"key\":\"counter.heat\",\"label\":\"热能\",\"initial\":6,\"min\":0,\"max\":10},{\"key\":\"counter.secret\",\"label\":\"隐秘\",\"initial\":2,\"min\":0,\"max\":4,\"visible\":false}]}");
    }
    private ObjectNode config() {
        ObjectNode config=json.createObjectNode().put("schemaVersion",1).put("capacity",14).put("hpName","保温储备");
        ArrayNode items=config.putArray("items");
        items.addObject().put("id","ration").put("name","口粮").put("kind","ITEM").put("stat","HP").put("delta",3);
        items.addObject().put("id","fuel").put("name","燃料").put("kind","ITEM").put("slotCost",2);
        items.addObject().put("id","warmer").put("name","暖包").put("kind","ITEM").put("statKey","counter.heat").put("delta",3);
        items.addObject().put("id","coat").put("name","装备").put("kind","ITEM").put("statKey","counter.heat").put("delta",2).put("wear",true).put("mode","HELD");
        return ThemeRuntimeConfig.parseDraft(config.toString());
    }
    private TopicRouteEngine.State state(ObjectNode config) {
        TopicRouteEngine.State state=new TopicRouteEngine.State();engine.initialize(config,state);JourneyInitialState.initialize(rules(),state);
        state.getVariables().put("sys.hp",8);state.getVariables().put("sys.luck",2);return state;
    }
    private ArrayNode amounts(String id,int qty) {ArrayNode rows=json.createArrayNode();rows.addObject().put("itemId",id).put("quantity",qty);return rows;}
    private ObjectNode item(ObjectNode c,int index){return (ObjectNode)c.path("items").get(index);}
    private void give(ObjectNode c,TopicRouteEngine.State s,String id,int qty){engine.grantAll(c,s,rules(),"give:"+id,amounts(id,qty));}

    @Test void winterNameAndParametersReuseHpAndWeightedSlots() {
        ObjectNode c=config();TopicRouteEngine.State s=state(c);give(c,s,"fuel",1);give(c,s,"ration",1);
        assertEquals(3,engine.used(c,s));assertEquals(2,engine.view(c,s).path("contents").get(1).path("slotCost").asInt());
        engine.use(c,s,rules(),"ration");assertEquals(11,s.getVariables().get("sys.hp"));assertEquals(2,engine.used(c,s));assertEquals("保温储备",engine.view(c,s).path("hpName").asText());
        assertEquals(1,s.getThemeRuntime().path("acquired").path("ration").asInt());
    }
    @Test void independentNumericUseClampsAndAtCapKeepsInventory() {
        ObjectNode c=config();TopicRouteEngine.State s=state(c);give(c,s,"warmer",3);
        engine.use(c,s,rules(),"warmer");assertEquals(9,s.getVariables().get("counter.heat"));engine.use(c,s,rules(),"warmer");assertEquals(10,s.getVariables().get("counter.heat"));
        String before=s.getThemeRuntime().toString();assertThrows(IllegalStateException.class,()->engine.use(c,s,rules(),"warmer"));assertEquals(before,s.getThemeRuntime().toString());assertEquals(8,s.getVariables().get("sys.hp"));
    }
    @Test void heldReloadAndClippedBonusNeverHealAgain() throws Exception {
        ObjectNode c=config();TopicRouteEngine.State s=state(c);s.getVariables().put("counter.heat",10);give(c,s,"coat",1);
        engine.changeStat(s,rules(),"counter.heat",-3);engine.reconcileHeld(c,s,rules());assertEquals(7,s.getVariables().get("counter.heat"));
        s=json.readValue(json.writeValueAsString(s),TopicRouteEngine.State.class);engine.reconcileHeld(c,s,rules());assertEquals(7,s.getVariables().get("counter.heat"));
        engine.discard(c,s,rules(),"coat");assertEquals(7,s.getVariables().get("counter.heat"));assertFalse(s.getThemeRuntime().has("statLinks"));
    }
    @Test void onceEffectDoesNotReverseAndReduceTargetsDoNotLeakIntoHp() {
        ObjectNode once=config();item(once,3).put("mode","ONCE");TopicRouteEngine.State first=state(once);give(once,first,"coat",1);engine.discard(once,first,rules(),"coat");assertEquals(8,first.getVariables().get("counter.heat"));
        ObjectNode reduce=config();item(reduce,3).put("mode","REDUCE");TopicRouteEngine.State s=state(reduce);give(reduce,s,"coat",1);
        assertEquals(0,engine.reduceStatCost(reduce,s,"counter.heat",1));assertEquals(3,engine.reduceHpCost(reduce,s,3));assertEquals(6,s.getVariables().get("counter.heat"));
    }
    @Test void weightedPendingRetainsWholeQuantityAndUsesSameSlotsInView() {
        ObjectNode c=config();c.put("capacity",3);c.putArray("grants").addObject().put("id","fuel_first").put("source","NODE").put("nodeId",1).put("itemId","fuel").put("quantity",2);
        TopicRouteEngine.State s=state(c);engine.signal(c,s,rules(),"NODE",1L,"","",0,"node:1");
        assertEquals(0,engine.used(c,s));assertEquals(4,engine.view(c,s).path("pending").path("grant:fuel_first").path("slots").asInt());
        assertThrows(IllegalStateException.class,()->engine.receive(c,s,rules(),"grant:fuel_first",true));assertEquals(2,s.getThemeRuntime().path("pending").path("grant:fuel_first").path("quantity").asInt());
    }
    @Test void chaptersHaveOneLegacyCostAndOneIndependentCostEach() {
        ObjectNode c=config();ObjectNode chapter=c.putArray("chapters").addObject().put("index",0).put("hpCost",1).put("luckCost",0);chapter.putArray("statCosts").addObject().put("statKey","counter.heat").put("amount",2);
        c=ThemeRuntimeConfig.parseDraft(c.toString());TopicRouteEngine.State s=state(c);engine.chapterEnd(c,s,rules(),chapter,1);engine.chapterEnd(c,s,rules(),chapter,2);
        assertEquals(7,s.getVariables().get("sys.hp"));assertEquals(4,s.getVariables().get("counter.heat"));
    }
    @Test void illegalTargetsDuplicatesAndFractionalSlotCostsAreRejected() {
        ObjectNode c=config();item(c,2).put("stat","HP");String both=c.toString();assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.parseDraft(both));
        c=config();item(c,2).put("statKey","sys.hp");String hp=c.toString();assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.parseDraft(hp));
        c=config();item(c,1).put("slotCost",1.5);String fraction=c.toString();assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.parseDraft(fraction));
        c=config();item(c,1).put("slotCost",0);String zero=c.toString();assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.parseDraft(zero));
        c=config();item(c,2).put("statKey","counter.missing");ObjectNode missing=c;assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.assertJourneyReferences(missing,rules()));
        c=config();ObjectNode chapter=c.putArray("chapters").addObject().put("index",0).put("hpCost",0).put("luckCost",0);chapter.putArray("statCosts").addObject().put("statKey","sys.hp").put("amount",1);String repeated=c.toString();assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.parseDraft(repeated));
        JourneyRules disabled=rules();disabled.setStateEnabled(false);ObjectNode valid=config();assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.assertJourneyReferences(valid,disabled));
    }
    @Test void materialsNeedNoStatAndNegativeLowerBoundWorks() {
        ObjectNode c=config();TopicRouteEngine.State s=state(c);give(c,s,"fuel",1);assertThrows(IllegalStateException.class,()->engine.use(c,s,rules(),"fuel"));
        JourneyRules rules=rules();((ObjectNode)rules.getAttributes().get(0)).put("min",-5);s.getVariables().put("counter.heat",-4);
        assertEquals(-1,engine.changeStat(s,rules,"counter.heat",-3));assertEquals(-5,s.getVariables().get("counter.heat"));assertEquals(15,engine.changeStat(s,rules,"counter.heat",100));
    }
    @Test void referenceOverloadIncludesAttributesAndVisibleResultIsFrozen() {
        ObjectNode c=config();ThemeRuntimeConfig.assertReferences(c,Collections.emptySet(),Collections.emptySet(),rules());
        TopicRouteEngine.State s=state(c);assertEquals(1,engine.view(c,s,rules()).path("attributes").size());engine.terminal(c,s,rules(),"COMPLETED",1);
        assertEquals(1,s.getThemeRuntime().path("result").path("attributes").size());assertFalse(s.getThemeRuntime().path("result").toString().contains("counter.secret"));
    }
}
