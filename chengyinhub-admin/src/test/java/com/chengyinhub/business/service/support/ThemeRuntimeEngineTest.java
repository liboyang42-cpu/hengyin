package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ThemeRuntimeEngineTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ThemeRuntimeEngine engine = new ThemeRuntimeEngine();
    private ObjectNode config() throws Exception {
        return ThemeRuntimeConfig.parse("{\"schemaVersion\":1,\"capacity\":3,\"hpName\":\"保暖值\",\"items\":["+
            "{\"id\":\"food\",\"kind\":\"ITEM\",\"name\":\"口粮\",\"image\":\"https://example.test/food.png\",\"stat\":\"HP\",\"delta\":2},"+
            "{\"id\":\"coat\",\"kind\":\"ITEM\",\"name\":\"外套\",\"image\":\"https://example.test/coat.png\",\"stat\":\"HP\",\"delta\":2,\"wear\":true,\"mode\":\"HELD\"},"+
            "{\"id\":\"token\",\"kind\":\"MEDAL\",\"name\":\"凭证\",\"image\":\"https://example.test/token.png\"}],"+
            "\"grants\":[{\"id\":\"first\",\"source\":\"NODE\",\"nodeId\":1,\"itemId\":\"food\",\"quantity\":2},{\"id\":\"dress\",\"source\":\"NODE\",\"nodeId\":2,\"itemId\":\"coat\",\"quantity\":1}],"+
            "\"events\":[{\"id\":\"notice\",\"content\":\"广播内容\",\"when\":[{\"op\":\"GTE\",\"var\":\"sys.actions\",\"value\":2}],\"grants\":[{\"itemId\":\"token\",\"quantity\":1}]}],"+
            "\"endings\":[{\"id\":\"win\",\"title\":\"完成\",\"when\":[{\"op\":\"ITEM_ACQUIRED\",\"itemId\":\"token\",\"quantity\":1}],\"success\":true},{\"id\":\"other\",\"title\":\"结束\",\"fallback\":true,\"success\":false}]}");
    }
    private JourneyRules rules() { JourneyRules r = new JourneyRules();r.setCheckEnabled(true);r.getLuck().setMax(5);return r; }
    private TopicRouteEngine.State state(ObjectNode c) {TopicRouteEngine.State s=new TopicRouteEngine.State();s.setStatus("ACTIVE");s.getVariables().put("sys.hp",6);s.getVariables().put("sys.luck",2);engine.initialize(c,s);return s;}
    private void node(ObjectNode c,TopicRouteEngine.State s,long node) {engine.signal(c,s,rules(),"NODE",node,"","",0,"node:"+node);}
    @Test void actualInventoryAndOneSourceGrantAreNotConfigurationCards() throws Exception {
        ObjectNode c=config();TopicRouteEngine.State s=state(c);assertEquals(0,engine.view(c,s).path("contents").size());
        node(c,s,1);node(c,s,1);assertEquals(2,s.getThemeRuntime().path("inventory").path("food").asInt());assertEquals(1,s.getVariables().get("sys.actions"));
        engine.use(c,s,rules(),"food");assertEquals(8,s.getVariables().get("sys.hp"));assertEquals(1,s.getThemeRuntime().path("inventory").path("food").asInt());
    }
    @Test void discardFreesSpaceWithoutResettingAcquisition() throws Exception {
        ObjectNode c=config();TopicRouteEngine.State s=state(c);node(c,s,1);engine.discard(c,s,rules(),"food");node(c,s,1);
        assertEquals(1,s.getThemeRuntime().path("inventory").path("food").asInt());assertEquals(2,s.getThemeRuntime().path("acquired").path("food").asInt());assertEquals(6,s.getVariables().get("sys.hp"));
        assertTrue(engine.conditions(json.readTree("[{\"op\":\"ITEM_ACQUIRED\",\"itemId\":\"food\",\"quantity\":2}]"),s));
        assertFalse(engine.conditions(json.readTree("[{\"op\":\"ITEM_HELD\",\"itemId\":\"food\",\"quantity\":2}]"),s));
    }
    @Test void fullBagKeepsOnePendingResultAndDelaysAutomaticWear() throws Exception {
        ObjectNode c=config();c.put("capacity",2);TopicRouteEngine.State s=state(c);node(c,s,1);node(c,s,2);
        assertEquals(6,s.getVariables().get("sys.hp"));assertEquals(1,s.getThemeRuntime().path("pending").size());
        engine.discard(c,s,rules(),"food");engine.receive(c,s,rules(),"grant:dress",true);assertEquals(8,s.getVariables().get("sys.hp"));
        assertThrows(IllegalStateException.class,()->engine.receive(c,s,rules(),"grant:dress",true));
    }
    @Test void heldModifierSurvivesReloadAndRemovesOnlyItsOwnEffect() throws Exception {
        ObjectNode c=config();TopicRouteEngine.State s=state(c);node(c,s,2);assertEquals(8,s.getVariables().get("sys.hp"));
        s=json.readValue(json.writeValueAsString(s),TopicRouteEngine.State.class);engine.reconcileHeld(c,s,rules());assertEquals(8,s.getVariables().get("sys.hp"));
        s.getVariables().put("sys.hp",5);engine.reconcileHeld(c,s,rules());assertEquals(5,s.getVariables().get("sys.hp"));
        engine.discard(c,s,rules(),"coat");assertEquals(3,s.getVariables().get("sys.hp"));
    }
    @Test void onceWearDoesNotReverseAfterDiscard() throws Exception {
        ObjectNode c=config();((ObjectNode)c.path("items").get(1)).put("mode","ONCE");TopicRouteEngine.State s=state(c);node(c,s,2);
        engine.discard(c,s,rules(),"coat");assertEquals(8,s.getVariables().get("sys.hp"));
    }
    @Test void eventAndItsGrantFireOnceAcrossRepeatedSignals() throws Exception {
        ObjectNode c=config();TopicRouteEngine.State s=state(c);node(c,s,1);node(c,s,2);node(c,s,3);engine.events(c,s,rules());
        assertEquals(1,s.getThemeRuntime().path("events").size());assertEquals(1,s.getThemeRuntime().path("inventory").path("token").asInt());assertEquals(3,engine.view(c,s).path("used").asInt());
        assertThrows(IllegalStateException.class,()->engine.use(c,s,rules(),"token"));
    }
    @Test void endedSnapshotDoesNotChangeWhenCatalogChanges() throws Exception {
        ObjectNode c=config();TopicRouteEngine.State s=state(c);node(c,s,1);node(c,s,2);engine.terminal(c,s,rules(),"COMPLETED",1234);
        String result=s.getThemeRuntime().path("result").toString();c.put("hpName","改名");((ObjectNode)c.path("endings").get(0)).put("title","新标题");
        engine.terminal(c,s,rules(),"ABANDONED",9999);assertEquals(result,s.getThemeRuntime().path("result").toString());assertTrue(s.getThemeRuntime().path("result").path("success").asBoolean());
    }
    @Test void failureDoesNotBecomeSuccessEvenIfEndingConditionsPass() throws Exception {
        ObjectNode c=config();TopicRouteEngine.State s=state(c);node(c,s,1);node(c,s,2);engine.terminal(c,s,rules(),"EXHAUSTED",1);
        assertFalse(s.getThemeRuntime().path("result").path("success").asBoolean());assertEquals("ENDED",s.getStatus());
    }
    @Test void zeroEffectAtCapDoesNotConsume() throws Exception {
        ObjectNode c=config();TopicRouteEngine.State s=state(c);node(c,s,1);s.getVariables().put("sys.hp",10);
        assertThrows(IllegalStateException.class,()->engine.use(c,s,rules(),"food"));assertEquals(2,s.getThemeRuntime().path("inventory").path("food").asInt());
    }
    @Test void invalidAmountsAndInjectedSystemEffectsAreRejected() throws Exception {
        ObjectNode c=config();((ObjectNode)c.path("items").get(0)).put("delta",0);String invalidAmount=c.toString();assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.parse(invalidAmount));
        c=config();ObjectNode event=(ObjectNode)c.path("events").get(0);event.putArray("effects").addObject().put("var","sys.hp").put("op","INC").put("value",99);
        String raw=c.toString();assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.parse(raw));
    }
    @Test void referencesAndCopyDoNotLeakIntoAnotherTheme() throws Exception {
        ObjectNode c=config();assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.assertReferences(c,Collections.singleton(1L),Collections.emptySet()));
        Map<Long,Long> mapping=new HashMap<>();mapping.put(1L,11L);mapping.put(2L,12L);JsonNode copy=json.readTree(ThemeRuntimeConfig.remapNodes(c.toString(),mapping));assertEquals(11,copy.path("grants").get(0).path("nodeId").asInt());
        assertEquals(1,c.path("grants").get(0).path("nodeId").asInt());
    }
    @Test void sceneRotationFreezesSelectedContent() throws Exception {
        ObjectNode c=config();c.put("rotation","ROUND_ROBIN");c.put("currentScene","s1");c.putArray("scenes").addObject().put("id","s1").put("name","第一场");c.withArray("scenes").addObject().put("id","s2").put("name","第二场");
        c=ThemeRuntimeConfig.parse(c.toString());ObjectNode first=ThemeRuntimeConfig.freeze(c,0,0);ObjectNode second=ThemeRuntimeConfig.freeze(c,1,0);
        assertEquals("s1",first.path("sceneId").asText());assertEquals("s2",second.path("sceneId").asText());c.put("currentScene","s2");assertEquals("s1",first.path("sceneId").asText());
    }
    @Test void clippedHeldBonusDoesNotHealAgainAfterDamage() throws Exception {
        ObjectNode c=config();TopicRouteEngine.State s=state(c);s.getVariables().put("sys.hp",10);node(c,s,2);
        s.getVariables().put("sys.hp",7);engine.reconcileHeld(c,s,rules());assertEquals(7,s.getVariables().get("sys.hp"));
        engine.reconcileHeld(c,s,rules());assertEquals(7,s.getVariables().get("sys.hp"));engine.discard(c,s,rules(),"coat");assertEquals(7,s.getVariables().get("sys.hp"));
    }
    @Test void cardContainsOnlySelectedActualFacts() throws Exception {
        ObjectNode c=config();c.putObject("card").putArray("fields").add("ending").add("medals").add("hp");TopicRouteEngine.State s=state(c);node(c,s,1);node(c,s,2);engine.terminal(c,s,rules(),"COMPLETED",12);
        JsonNode card=engine.card(s.getThemeRuntime().get("result"));assertEquals(1,card.path("medals").size());assertEquals(8,card.path("hp").asInt());
        assertFalse(card.has("inventory"));assertFalse(card.has("items"));assertFalse(card.has("catalog"));assertFalse(card.has("sessionId"));assertFalse(card.has("events"));
    }
    @Test void endingRewardCanBeConfiguredForFailure() throws Exception {
        ObjectNode c=config();c.withArray("grants").addObject().put("id","farewell").put("source","ENDING").put("itemId","token").put("quantity",1).put("success",false).putArray("reasons").add("EXHAUSTED");
        c=ThemeRuntimeConfig.parse(c.toString());TopicRouteEngine.State s=state(c);engine.terminal(c,s,rules(),"EXHAUSTED",1);assertEquals(1,s.getThemeRuntime().path("inventory").path("token").asInt());assertFalse(s.getThemeRuntime().path("result").path("success").asBoolean());
    }
    @Test void draftAllowsUnfinishedMediaButPublishRejectsIt() throws Exception {
        ObjectNode c=config();((ObjectNode)c.path("items").get(0)).remove("image");String raw=c.toString();assertNotNull(ThemeRuntimeConfig.parseDraft(raw));assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.parse(raw));
    }
    @Test void weeklyRotationStartsFromExplicitAnchorAndNoBagThemeWorks() throws Exception {
        ObjectNode c=config();c.put("rotation","WEEKLY");c.put("rotationAnchor",1767571200000L);c.put("currentScene","s1");c.putArray("scenes").addObject().put("id","s1").put("name","第一场");c.withArray("scenes").addObject().put("id","s2").put("name","第二场");
        c=ThemeRuntimeConfig.parse(c.toString());assertEquals("s1",ThemeRuntimeConfig.freeze(c,0,1767571200000L).path("sceneId").asText());assertEquals("s2",ThemeRuntimeConfig.freeze(c,0,1768176000000L).path("sceneId").asText());
        assertEquals(0,ThemeRuntimeConfig.parse("{\"schemaVersion\":1}").path("capacity").asInt());
    }
    @Test void differentSourcesAccumulateAndChoiceRequiresExactTrustedReference() throws Exception {
        ObjectNode c=config();c.put("capacity",8);
        c.withArray("grants").addObject().put("id","choice_food").put("source","CHOICE").put("nodeId",3).put("step","ask").put("option","help").put("itemId","food").put("quantity",1);
        TopicRouteEngine.State s=state(c);node(c,s,1);
        engine.signal(c,s,rules(),"CHOICE",3L,"ask","leave",0,"choice:leave");
        assertEquals(2,s.getThemeRuntime().path("inventory").path("food").asInt());
        engine.signal(c,s,rules(),"CHOICE",3L,"ask","help",0,"choice:help");
        engine.signal(c,s,rules(),"CHOICE",3L,"ask","help",0,"choice:help");
        assertEquals(3,s.getThemeRuntime().path("inventory").path("food").asInt());
    }
    @Test void fixedDrawResultSurvivesFullBagAndClaimWithoutRedraw() throws Exception {
        ObjectNode c=config();c.put("capacity",2);
        c.withArray("grants").addObject().put("id","draw_coat").put("source","DRAW").put("nodeId",4).put("outcome",2).put("itemId","coat").put("quantity",1);
        TopicRouteEngine.State s=state(c);node(c,s,1);
        engine.signal(c,s,rules(),"DRAW",4L,"","",2,"draw:receipt");
        assertEquals(0,s.getThemeRuntime().path("inventory").path("coat").asInt());
        engine.discard(c,s,rules(),"food");engine.receive(c,s,rules(),"grant:draw_coat",true);
        engine.signal(c,s,rules(),"DRAW",4L,"","",2,"draw:receipt");
        assertEquals(1,s.getThemeRuntime().path("inventory").path("coat").asInt());
        assertEquals(8,s.getVariables().get("sys.hp"));
    }
    @Test void mixedOnceHeldAndNegativeLuckReverseOnlyHeldContribution() throws Exception {
        ObjectNode c=config();c.put("capacity",8);
        ObjectNode once=((ObjectNode)c.path("items").get(1)).deepCopy();once.put("id","once_coat");once.put("mode","ONCE");c.withArray("items").add(once);
        ObjectNode curse=once.deepCopy();curse.put("id","curse");curse.put("mode","HELD");curse.put("stat","LUCK");curse.put("delta",-3);c.withArray("items").add(curse);
        c.withArray("grants").addObject().put("id","once").put("source","NODE").put("nodeId",5).put("itemId","once_coat").put("quantity",1);
        c.withArray("grants").addObject().put("id","held").put("source","NODE").put("nodeId",6).put("itemId","coat").put("quantity",2);
        c.withArray("grants").addObject().put("id","curse_grant").put("source","NODE").put("nodeId",7).put("itemId","curse").put("quantity",1);
        TopicRouteEngine.State s=state(c);node(c,s,5);node(c,s,6);
        assertEquals(10,s.getVariables().get("sys.hp"));
        engine.discard(c,s,rules(),"coat");assertEquals(10,s.getVariables().get("sys.hp"));
        engine.discard(c,s,rules(),"coat");assertEquals(8,s.getVariables().get("sys.hp"));
        engine.discard(c,s,rules(),"once_coat");assertEquals(8,s.getVariables().get("sys.hp"));
        node(c,s,7);assertEquals(0,s.getVariables().get("sys.luck"));
        engine.discard(c,s,rules(),"curse");assertEquals(2,s.getVariables().get("sys.luck"));
    }
    @Test void consumableLuckSupportsNegativeAmountAndRejectsDisabledStat() throws Exception {
        ObjectNode c=config();ObjectNode food=(ObjectNode)c.path("items").get(0);food.put("stat","LUCK");food.put("delta",-2);
        TopicRouteEngine.State s=state(c);node(c,s,1);engine.use(c,s,rules(),"food");assertEquals(0,s.getVariables().get("sys.luck"));
        assertThrows(IllegalStateException.class,()->engine.use(c,s,rules(),"food"));
        JourneyRules disabled=rules();disabled.getLuck().setEnabled(false);
        assertThrows(IllegalStateException.class,()->engine.use(c,s,disabled,"food"));
        assertEquals(1,s.getThemeRuntime().path("inventory").path("food").asInt());
    }
    @Test void unknownEndingGrantCannotPublish() throws Exception {
        ObjectNode c=config();c.withArray("grants").addObject().put("id","reward").put("source","ENDING").put("endingId","missing").put("itemId","token").put("quantity",1);
        assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.parse(c.toString()));
    }


    private ObjectNode authoringConfig() throws Exception {
        return ThemeRuntimeConfig.parse("{\"schemaVersion\":1,\"capacity\":6,\"allowDiscard\":true,\"items\":["+
            "{\"id\":\"weather\",\"kind\":\"ITEM\",\"name\":\"预报\",\"image\":\"https://example.test/a.png\"},"+
            "{\"id\":\"drink\",\"kind\":\"ITEM\",\"name\":\"饮品\",\"image\":\"https://example.test/b.png\",\"stat\":\"HP\",\"delta\":2,\"wear\":false}],"+
            "\"chapters\":[{\"index\":0,\"hpCost\":1,\"luckCost\":0,\"grants\":[{\"itemId\":\"weather\",\"quantity\":1,\"timing\":\"START\"},{\"itemId\":\"drink\",\"quantity\":1,\"timing\":\"END\"}],\"eggs\":["+
            "{\"id\":\"clock\",\"name\":\"消息\",\"content\":\"内容\",\"trigger\":\"TIME\",\"threshold\":2,\"rewards\":[]},"+
            "{\"id\":\"walk\",\"name\":\"脚印\",\"content\":\"内容\",\"trigger\":\"STEPS\",\"threshold\":100,\"rewards\":[{\"itemId\":\"drink\",\"quantity\":1}]}]}]}");
    }
    @Test void authoringRoundTripStartEndAndDisplayInventory() throws Exception {
        ObjectNode c=authoringConfig();TopicRouteEngine.State s=state(c);JsonNode ch=c.path("chapters").get(0);
        engine.chapterStart(c,s,rules(),ch,1000);engine.chapterStart(c,s,rules(),ch,9000);
        assertEquals(1,s.getThemeRuntime().path("inventory").path("weather").asInt());
        assertThrows(IllegalStateException.class,()->engine.use(c,s,rules(),"weather"));
        engine.chapterEnd(c,s,rules(),ch,60000);engine.chapterEnd(c,s,rules(),ch,120000);
        assertEquals(5,s.getVariables().get("sys.hp"));assertEquals(1,s.getThemeRuntime().path("inventory").path("drink").asInt());
        engine.use(c,s,rules(),"drink");assertEquals(7,s.getVariables().get("sys.hp"));
        assertEquals(c,ThemeRuntimeConfig.parse(c.toString()));
    }
    @Test void chapterElapsedAndStepsDoNotReplayOrFireAfterEnd() throws Exception {
        ObjectNode c=authoringConfig();TopicRouteEngine.State s=state(c);JsonNode ch=c.path("chapters").get(0);
        engine.chapterStart(c,s,rules(),ch,1000);engine.chapterTick(c,s,rules(),120999);assertEquals(0,s.getThemeRuntime().path("events").size());
        engine.chapterTick(c,s,rules(),121000);assertEquals(1,s.getThemeRuntime().path("events").size());
        engine.chapterSteps(s,99);engine.chapterTick(c,s,rules(),121001);assertEquals(1,s.getThemeRuntime().path("events").size());
        s=json.readValue(json.writeValueAsString(s),TopicRouteEngine.State.class);
        engine.chapterSteps(s,1);engine.chapterTick(c,s,rules(),121002);engine.chapterTick(c,s,rules(),999999);
        assertEquals(2,s.getThemeRuntime().path("events").size());assertEquals(1,s.getThemeRuntime().path("inventory").path("drink").asInt());
        engine.chapterEnd(c,s,rules(),ch,121003);engine.chapterSteps(s,500);assertEquals(100,s.getThemeRuntime().path("chapters").path("0").path("steps").asInt());
    }
    @Test void globalDiscardAndReduceCostAreAuthoritative() throws Exception {
        ObjectNode c=authoringConfig();ObjectNode drink=(ObjectNode)c.path("items").get(1);drink.put("wear",true);drink.put("mode","REDUCE");
        TopicRouteEngine.State s=state(c);JsonNode ch=c.path("chapters").get(0);engine.chapterStart(c,s,rules(),ch,0);engine.chapterSteps(s,100);engine.chapterTick(c,s,rules(),0);
        assertEquals(6,s.getVariables().get("sys.hp"));assertEquals(1,engine.reduceHpCost(c,s,3));
        c.put("allowDiscard",false);assertThrows(IllegalStateException.class,()->engine.discard(c,s,rules(),"drink"));
        c.put("allowDiscard",true);engine.discard(c,s,rules(),"drink");assertEquals(3,engine.reduceHpCost(c,s,3));
        drink.put("stat","LUCK");String invalid=c.toString();assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.parse(invalid));
    }
}
