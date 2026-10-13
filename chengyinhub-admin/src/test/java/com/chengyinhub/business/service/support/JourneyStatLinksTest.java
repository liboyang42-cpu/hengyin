package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JourneyStatLinksTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ThemeRuntimeEngine engine = new ThemeRuntimeEngine();
    private ObjectNode definition() throws Exception {
        return (ObjectNode)json.readTree("{\"schemaVersion\":1,\"stateEnabled\":true,\"attributes\":[{\"key\":\"counter.temperature\",\"label\":\"体温储备\",\"initial\":10,\"min\":0,\"max\":10,\"visible\":true}],\"statLinks\":[{\"id\":\"cold\",\"sourceKey\":\"counter.temperature\",\"targetKey\":\"sys.hp\",\"trigger\":\"PER_POINT\",\"multiplier\":2,\"frequency\":\"EVENT\",\"allowedReasons\":[\"TASK_COST\"]}]}");
    }
    private JourneyRules rules() throws Exception { return JourneyRules.parse(definition().toString()); }
    private TopicRouteEngine.State state(JourneyRules rules) {
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        JourneyInitialState.initialize(rules,state); state.getVariables().put("sys.hp",10); return state;
    }
    private TopicRouteEngine.State copy(TopicRouteEngine.State state) throws Exception {
        return json.readValue(json.writeValueAsString(state), TopicRouteEngine.State.class);
    }
    private ObjectNode rule(ObjectNode definition) { return (ObjectNode)definition.path("statLinks").get(0); }
    private void rejected(ObjectNode definition) { assertThrows(IllegalArgumentException.class, () -> JourneyRules.parse(definition.toString())); }

    @Test void actualNetDeclineUsesExistingHpAndRetriesAreIdempotent() throws Exception {
        JourneyRules rules=rules(); TopicRouteEngine.State before=state(rules), after=copy(before);
        engine.changeStat(after,rules,"counter.temperature",-4); engine.changeStat(after,rules,"counter.temperature",2);
        assertEquals(4,JourneyStatLinks.apply(rules,before,after,"TEAM:12","quest:1",JourneyStatLinks.Reason.TASK_COST).get(0).path("actualDamage").asInt());
        assertEquals(6,after.getVariables().get("sys.hp")); assertEquals(10,before.getVariables().get("sys.hp"));
        TopicRouteEngine.State reload=copy(after);
        assertTrue(JourneyStatLinks.apply(rules,before,reload,"TEAM:12","quest:1",JourneyStatLinks.Reason.TASK_COST).isEmpty());
        assertEquals(6,reload.getVariables().get("sys.hp"));
        assertThrows(IllegalArgumentException.class,()->JourneyStatLinks.apply(rules,before,reload,"TEAM:12","quest:1",JourneyStatLinks.Reason.ITEM_USE));
    }
    @Test void overdrawAtFloorOnlyCountsOneActualPoint() throws Exception {
        JourneyRules rules=rules(); TopicRouteEngine.State before=state(rules); before.getVariables().put("counter.temperature",1);
        TopicRouteEngine.State after=copy(before); assertEquals(-1,engine.changeStat(after,rules,"counter.temperature",-3));
        JourneyStatLinks.apply(rules,before,after,"MEMBER:5","chapter:2",JourneyStatLinks.Reason.TASK_COST);
        assertEquals(8,after.getVariables().get("sys.hp")); assertEquals(0,after.getVariables().get("counter.temperature"));
    }
    @Test void perEventIsFixedAndRunFrequencyOnlyFiresOnce() throws Exception {
        ObjectNode config=definition(); ObjectNode link=rule(config); link.put("trigger","PER_EVENT");link.remove("multiplier");link.put("amount",2);link.put("frequency","RUN");
        JourneyRules rules=JourneyRules.parse(config.toString()); TopicRouteEngine.State before=state(rules),after=copy(before);engine.changeStat(after,rules,"counter.temperature",-5);
        JourneyStatLinks.apply(rules,before,after,"TEAM:1","a",JourneyStatLinks.Reason.TASK_COST);assertEquals(8,after.getVariables().get("sys.hp"));
        before=copy(after);engine.changeStat(after,rules,"counter.temperature",-2);
        assertTrue(JourneyStatLinks.apply(rules,before,after,"TEAM:1","b",JourneyStatLinks.Reason.TASK_COST).isEmpty());assertEquals(8,after.getVariables().get("sys.hp"));
    }
    @Test void firstThresholdCrossingDoesNotFireAgainAfterRecovery() throws Exception {
        ObjectNode config=definition();ObjectNode link=rule(config);link.put("trigger","FIRST_THRESHOLD");link.remove("multiplier");link.put("amount",2);link.put("threshold",3);link.put("frequency","RUN");
        JourneyRules rules=JourneyRules.parse(config.toString());TopicRouteEngine.State before=state(rules),after=copy(before);engine.changeStat(after,rules,"counter.temperature",-7);
        JourneyStatLinks.apply(rules,before,after,"TEAM:1","first",JourneyStatLinks.Reason.TASK_COST);assertEquals(8,after.getVariables().get("sys.hp"));
        before=copy(after);engine.changeStat(after,rules,"counter.temperature",-1);JourneyStatLinks.apply(rules,before,after,"TEAM:1","lower",JourneyStatLinks.Reason.TASK_COST);
        engine.changeStat(after,rules,"counter.temperature",5);before=copy(after);engine.changeStat(after,rules,"counter.temperature",-6);
        JourneyStatLinks.apply(rules,before,after,"TEAM:1","crossAgain",JourneyStatLinks.Reason.TASK_COST);assertEquals(8,after.getVariables().get("sys.hp"));
    }
    @Test void recoveryNoChangeAndUnselectedReasonsDoNotDamage() throws Exception {
        JourneyRules rules=rules();TopicRouteEngine.State before=state(rules),after=copy(before);
        JourneyStatLinks.apply(rules,before,after,"TEAM:1","none",JourneyStatLinks.Reason.TASK_COST);
        engine.changeStat(after,rules,"counter.temperature",-2);JourneyStatLinks.apply(rules,before,after,"TEAM:1","use",JourneyStatLinks.Reason.ITEM_USE);
        before=copy(after);engine.changeStat(after,rules,"counter.temperature",2);JourneyStatLinks.apply(rules,before,after,"TEAM:1","restore",JourneyStatLinks.Reason.TASK_COST);
        assertEquals(10,after.getVariables().get("sys.hp"));assertFalse(after.getThemeRuntime().path("statLinks").has("audit"));
    }
    @Test void crossOwnerIsRejectedAndFailedCandidateDoesNotChangePersistedState() throws Exception {
        JourneyRules rules=rules();TopicRouteEngine.State persisted=state(rules),candidate=copy(persisted);engine.changeStat(candidate,rules,"counter.temperature",-2);
        assertThrows(IllegalArgumentException.class,()->JourneyStatLinks.apply(rules,persisted,"TEAM:1",candidate,"TEAM:2","x",JourneyStatLinks.Reason.TASK_COST));
        JourneyStatLinks.apply(rules,persisted,candidate,"TEAM:1","x",JourneyStatLinks.Reason.TASK_COST);
        // 模拟CAS/外层事务拒绝：只丢弃candidate，持久态两个数值和凭证均不变。
        assertEquals(10,persisted.getVariables().get("sys.hp"));assertEquals(10,persisted.getVariables().get("counter.temperature"));assertTrue(persisted.getThemeRuntime().isEmpty());
        assertThrows(IllegalArgumentException.class,()->JourneyStatLinks.apply(rules,persisted,candidate,"TEAM:2","y",JourneyStatLinks.Reason.TASK_COST));
    }
    @Test void fractionalOrUnclampedSourceIsRejectedBeforeAnyLinkWrites() throws Exception {
        JourneyRules rules=rules();TopicRouteEngine.State before=state(rules),after=copy(before);after.getVariables().put("counter.temperature",2.5);
        String unchanged=json.writeValueAsString(after);
        assertThrows(IllegalArgumentException.class,()->JourneyStatLinks.apply(rules,before,after,"TEAM:1","bad",JourneyStatLinks.Reason.TASK_COST));assertEquals(unchanged,json.writeValueAsString(after));
        after.getVariables().put("counter.temperature",-1);
        assertThrows(IllegalArgumentException.class,()->JourneyStatLinks.apply(rules,before,after,"TEAM:1","bad",JourneyStatLinks.Reason.TASK_COST));
    }
    @Test void invalidRulesCannotBeSilentlyNormalizedAway() throws Exception {
        ObjectNode c=definition();rule(c).put("sourceKey","sys.hp");rejected(c);
        c=definition();rule(c).put("targetKey","counter.temperature");rejected(c);
        c=definition();c.put("stateEnabled",false);rejected(c);
        c=definition();c.putObject("hp").put("enabled",false);rejected(c);
        c=definition();c.withArray("statLinks").add(rule(c).deepCopy());rejected(c);
        c=definition();rule(c).put("multiplier",1.5);rejected(c);
        c=definition();rule(c).put("multiplier",Long.MAX_VALUE);rejected(c);
        c=definition();rule(c).putArray("allowedReasons").add("READ");rejected(c);
        c=definition();rule(c).put("script","damage()");rejected(c);
        c=definition();rule(c).put("threshold",3);rejected(c);
        c=definition();rule(c).put("trigger","FIRST_THRESHOLD");rule(c).remove("multiplier");rule(c).put("amount",2);rule(c).put("threshold",3);rejected(c);
    }
    @Test void hiddenSourcesStayInInternalAuditAndPublicRulesHideLinks() throws Exception {
        ObjectNode c=definition();((ObjectNode)c.path("attributes").get(0)).put("visible",false);
        JourneyRules rules=JourneyRules.parse(c.toString());TopicRouteEngine.State before=state(rules),after=copy(before);engine.changeStat(after,rules,"counter.temperature",-2);
        JourneyStatLinks.apply(rules,before,after,"TEAM:1","x",JourneyStatLinks.Reason.TASK_COST);
        assertEquals(1,after.getThemeRuntime().path("statLinks").path("audit").size());
        assertFalse(engine.view(json.createObjectNode(),after,rules).toString().contains("temperature"));
        assertFalse(JourneyInitialState.publicRules(c.toString()).contains("temperature"));
    }
    @Test void visibleAuditCanBeReadWithoutExposingMutableStorage() throws Exception {
        JourneyRules rules=rules();TopicRouteEngine.State before=state(rules),after=copy(before);
        assertTrue(engine.view(json.createObjectNode(),before,rules).path("statLinkEvents").isArray());
        engine.changeStat(after,rules,"counter.temperature",-2);
        JourneyStatLinks.apply(rules,before,after,"TEAM:1","visible",JourneyStatLinks.Reason.TASK_COST);
        ObjectNode view=engine.view(json.createObjectNode(),after,rules);
        assertEquals(10,view.path("statLinkEvents").get(0).path("sourceBefore").asInt());
        assertEquals(8,view.path("statLinkEvents").get(0).path("sourceAfter").asInt());
        ((ObjectNode)view.path("statLinkEvents").get(0)).put("sourceBefore",999);
        assertEquals(10,after.getThemeRuntime().path("statLinks").path("audit").get(0).path("sourceBefore").asInt());
    }
    @Test void oldJournalAuditAndNestedCostProjectionRedactHiddenValuesWithoutChangingStorage() throws Exception {
        ObjectNode c=definition();((ObjectNode)c.path("attributes").get(0)).put("visible",false);JourneyRules rules=JourneyRules.parse(c.toString());
        TopicRouteEngine.State state=state(rules);ObjectNode original=state.getThemeRuntime().withArray("journal").addObject();
        original.put("sourceKey","counter.temperature").put("sourceBefore",10).put("sourceAfter",8).put("targetKey","sys.hp").put("targetBefore",10).put("targetAfter",6);
        original.putObject("statDeltas").put("counter.temperature",-2);
        String persisted=json.writeValueAsString(state);ObjectNode projected=engine.view(json.createObjectNode(),state,rules);
        assertFalse(projected.toString().contains("counter.temperature"));assertFalse(projected.path("journal").get(0).has("sourceBefore"));assertEquals(6,projected.path("journal").get(0).path("targetAfter").asInt());
        assertEquals(persisted,json.writeValueAsString(state));assertTrue(original.has("sourceBefore"));
    }

    @Test void multipleReasonsStillApplyOneNetDeclineAndEachCounterKeepsItsOwnProvenance() throws Exception {
        ObjectNode c=definition();((ObjectNode)c.path("attributes").get(0)).put("initial",6);
        c.withArray("attributes").addObject().put("key","counter.focus").put("label","专注").put("initial",5).put("min",0).put("max",10);
        rule(c).putArray("allowedReasons").add("TEMPLATE_EFFECT");
        JourneyRules rules=JourneyRules.parse(c.toString());TopicRouteEngine.State before=state(rules),after=copy(before);
        Map<String,Set<JourneyStatLinks.Reason>> sources=new HashMap<>();
        TopicRouteEngine.State stage=after.copy();engine.changeStat(after,rules,"counter.temperature",2);engine.changeStat(after,rules,"counter.focus",-1);
        JourneyStatLinks.recordDeclines(rules,stage,after,JourneyStatLinks.Reason.TEMPLATE_EFFECT,sources);
        stage=after.copy();engine.changeStat(after,rules,"counter.temperature",-3);
        JourneyStatLinks.recordDeclines(rules,stage,after,JourneyStatLinks.Reason.CHAPTER_COST,sources);
        assertEquals(Collections.singleton(JourneyStatLinks.Reason.CHAPTER_COST),sources.get("counter.temperature"));
        assertTrue(JourneyStatLinks.apply(rules,before,after,"TEAM:1","mixed",sources).isEmpty());assertEquals(10,after.getVariables().get("sys.hp"));
        before=after.copy();engine.changeStat(after,rules,"counter.temperature",-2);
        assertEquals(1,JourneyStatLinks.apply(rules,before,after,"TEAM:1","mixed2",EnumSet.of(JourneyStatLinks.Reason.TEMPLATE_EFFECT,JourneyStatLinks.Reason.CHAPTER_COST)).size());assertEquals(6,after.getVariables().get("sys.hp"));
    }

    @Test void hpFloorAndSecondContentUseTheSameContract() throws Exception {
        ObjectNode c=definition();((ObjectNode)c.path("attributes").get(0)).put("key","counter.focus").put("label","专注").put("initial",4).put("max",7);
        rule(c).put("sourceKey","counter.focus").put("multiplier",3);
        JourneyRules rules=JourneyRules.parse(c.toString());TopicRouteEngine.State before=state(rules),after=copy(before);engine.changeStat(after,rules,"counter.focus",-4);
        JourneyStatLinks.apply(rules,before,after,"MEMBER:2","answer:9",JourneyStatLinks.Reason.TASK_COST);assertEquals(0,after.getVariables().get("sys.hp"));
        assertEquals(10,after.getThemeRuntime().path("statLinks").path("audit").get(0).path("actualDamage").asInt());
    }
}
