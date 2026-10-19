package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ThemeRuntimeDraftPreviewTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ThemeRuntimeDraftPreview preview = new ThemeRuntimeDraftPreview();
    private static final String RULES = "{\"schemaVersion\":1,\"checkEnabled\":true,\"hp\":{\"enabled\":true,\"max\":10,\"init\":5},\"luck\":{\"enabled\":true,\"max\":3,\"init\":2}}";
    private ObjectNode config() {
        ObjectNode c=JSON.createObjectNode().put("schemaVersion",1).put("capacity",2);
        c.putArray("items").addObject().put("id","water").put("kind","ITEM").put("name","水");
        return c;
    }
    private ObjectNode chapter(ObjectNode c,int index,int quantity) {
        ObjectNode ch=c.withArray("chapters").addObject().put("index",index).put("hpCost",2).put("luckCost",1);
        ch.putArray("grants").addObject().put("itemId","water").put("quantity",quantity).put("timing","START");
        return ch;
    }
    private ObjectNode event(ObjectNode c,String id,String content) {
        return c.withArray("events").addObject().put("id",id).put("content",content);
    }
    private ThemeRuntimeDraftPreview.Result entry(ObjectNode c,Integer ch) {
        return preview.evaluate(c.toString(),RULES,Collections.<Long>emptySet(),Collections.<String>emptySet(),0,1000L,ch);
    }
    private int quantity(JsonNode result) {return result.path("projection").path("contents").size()==0?0:result.path("projection").path("contents").get(0).path("quantity").asInt();}
    private String repeat(String s,int count) {StringBuilder b=new StringBuilder();for(int i=0;i<count;i++)b.append(s);return b.toString();}

    @Test void deterministicFixedInputsMatchTheActualCoreEngineProjection() {
        ObjectNode c=config();chapter(c,3,1);
        ObjectNode e=event(c,"held","已拿到水");e.putArray("when").addObject().put("op","ITEM_HELD").put("itemId","water").put("quantity",1);
        JsonNode actual=entry(c,3).toJson();
        ObjectNode frozen=ThemeRuntimeConfig.freeze(ThemeRuntimeConfig.parseDraft(c.toString()),0,1000L);
        JourneyRules rules=JourneyRules.parse(RULES);TopicRouteEngine.State state=new TopicRouteEngine.State();state.setStatus("ACTIVE");
        JourneyInitialState.initialize(rules,state);state.getVariables().put("sys.hp",5);state.getVariables().put("sys.luck",2);
        ThemeRuntimeEngine engine=new ThemeRuntimeEngine();engine.initialize(frozen,state);engine.chapterStart(frozen,state,rules,frozen.path("chapters").get(0),1000L);engine.reconcileHeld(frozen,state,rules);engine.events(frozen,state,rules);
        assertEquals(engine.view(frozen,state),actual.path("projection"));assertEquals(actual,entry(c,3).toJson());
        assertTrue(actual.path("previewOnly").asBoolean());assertEquals("ENTRY_ONLY",actual.path("scope").asText());assertEquals(1,quantity(actual));
    }

    @Test void omittedChapterDoesNotStartAnyConfiguredChapterOrIssueStartGrants() {
        ObjectNode c=config();chapter(c,0,1);chapter(c,8,1);
        JsonNode absent=entry(c,null).toJson();assertEquals(0,quantity(absent));assertFalse(absent.has("chapterIndex"));
        JsonNode selected=entry(c,8).toJson();assertEquals(8,selected.path("chapterIndex").asInt());assertEquals(1,quantity(selected));assertEquals(1,selected.path("projection").path("journal").size());
        assertEquals(5,selected.path("entryValues").path("sys.hp").asInt());assertEquals(2,selected.path("entryValues").path("sys.luck").asInt());
    }

    @Test void chapterStartPreservesCapacityPendingAndRealHeldEffects() {
        ObjectNode c=config();chapter(c,0,3);JsonNode result=entry(c,0).toJson();assertEquals(0,quantity(result));assertEquals(3,result.path("projection").path("pending").path("chapter:0:START:water").path("quantity").asInt());
        ObjectNode second=config();ObjectNode water=(ObjectNode)second.path("items").get(0);water.put("stat","HP").put("delta",2).put("wear",true).put("mode","HELD");chapter(second,0,1);
        JsonNode worn=entry(second,0).toJson();assertEquals(7,worn.path("entryValues").path("sys.hp").asInt());assertEquals(1,quantity(worn));
    }

    @Test void eventsReachTheExistingOnceOnlyFixedPointInMemory() {
        ObjectNode c=config();ObjectNode dependent=event(c,"dependent","下一条");dependent.putArray("when").addObject().put("op","GTE").put("var","counter.ready").put("value",1);
        dependent.putArray("grants").addObject().put("itemId","water").put("quantity",1);
        ObjectNode seed=event(c,"seed","初始提示");seed.putArray("effects").addObject().put("var","counter.ready").put("op","SET").put("value",1);
        JsonNode first=preview.evaluate(c.toString(),RULES,Collections.<Long>emptySet(),Collections.singleton("counter.ready"),0,0,null).toJson();
        assertEquals(2,first.path("projection").path("events").size());assertEquals(1,quantity(first));assertEquals(1,first.path("projection").path("journal").size());
        assertEquals(first,preview.evaluate(c.toString(),RULES,Collections.<Long>emptySet(),Collections.singleton("counter.ready"),0,0,null).toJson());
    }

    @Test void currentSceneAndRoundRobinUseTheRealFrozenOverride() {
        ObjectNode c=config();event(c,"root","主配置");c.put("currentScene","b").put("rotation","ROUND_ROBIN");
        c.putArray("scenes").addObject().put("id","a").put("name","第一幕");
        c.withArray("scenes").addObject().put("id","b").put("name","第二幕").putArray("events").addObject().put("id","own").put("content","独立提示");
        JsonNode first=entry(c,null).toJson();assertEquals("b",first.path("sceneId").asText());assertEquals("own",first.path("projection").path("events").get(0).path("id").asText());
        JsonNode next=preview.evaluate(c.toString(),RULES,Collections.<Long>emptySet(),Collections.<String>emptySet(),1,1000,null).toJson();assertEquals("a",next.path("sceneId").asText());assertEquals("root",next.path("projection").path("events").get(0).path("id").asText());
        c.put("rotation","FIXED");assertEquals("b",preview.evaluate(c.toString(),RULES,Collections.<Long>emptySet(),Collections.<String>emptySet(),1,1000,null).toJson().path("sceneId").asText());
    }

    @Test void explicitEmptyAndNullSceneEventsOverrideWhileMissingEventsInherit() {
        for(int mode=0;mode<3;mode++) {
            ObjectNode c=config();event(c,"root","主配置");c.put("currentScene","scene");ObjectNode s=c.putArray("scenes").addObject().put("id","scene").put("name","一幕");
            if(mode==1)s.putArray("events");if(mode==2)s.putNull("events");
            assertEquals(mode==0?1:0,entry(c,null).toJson().path("projection").path("events").size());
        }
    }

    @Test void weeklySelectionPreservesExistingUtcMondayBoundaryWithExplicitClock() {
        ObjectNode c=config();c.put("currentScene","a").put("rotation","WEEKLY").put("rotationAnchor",0);
        c.putArray("scenes").addObject().put("id","a").put("name","一");c.withArray("scenes").addObject().put("id","b").put("name","二");
        long boundary=4L*86400000L;
        JsonNode before=preview.evaluate(c.toString(),RULES,Collections.<Long>emptySet(),Collections.<String>emptySet(),0,boundary-1,null).toJson();
        JsonNode after=preview.evaluate(c.toString(),RULES,Collections.<Long>emptySet(),Collections.<String>emptySet(),0,boundary,null).toJson();
        assertEquals("a",before.path("sceneId").asText());assertEquals("b",after.path("sceneId").asText());assertEquals(0,c.path("rotationAnchor").asLong());
    }

    @Test void resultCopiesInputsAndRepeatedCallsNeverShareState() {
        ObjectNode c=config();chapter(c,0,1);String raw=c.toString();Set<Long> nodes=new HashSet<Long>();Set<String> keys=new HashSet<String>();
        ThemeRuntimeDraftPreview.Result result=preview.evaluate(raw,RULES,nodes,keys,0,0,0);JsonNode expected=result.toJson();
        ObjectNode exposed=result.toJson();((ObjectNode)exposed.path("projection")).putArray("contents");exposed.put("scope","FORGED");
        nodes.add(9L);keys.add("counter.later");c.put("capacity",999);
        assertEquals(expected,result.toJson());assertEquals(expected,preview.evaluate(raw,RULES,Collections.<Long>emptySet(),Collections.<String>emptySet(),0,0,0).toJson());
    }

    @Test void declaredReferencesAreValidatedButDoNotBecomeCompletedNodesOrRuntimeFacts() {
        ObjectNode c=config();ObjectNode e=event(c,"node","尚未达成");e.putArray("when").addObject().put("op","NODE_COMPLETED").put("nodeId",Long.MAX_VALUE);
        assertThrows(IllegalArgumentException.class,()->entry(c,null));
        JsonNode result=preview.evaluate(c.toString(),RULES,Collections.singleton(Long.MAX_VALUE),Collections.<String>emptySet(),0,0,null).toJson();assertEquals(0,result.path("projection").path("events").size());
        ObjectNode other=config();event(other,"state","也未达成").putArray("when").addObject().put("op","GTE").put("var","counter.hidden").put("value",0);
        assertThrows(IllegalArgumentException.class,()->entry(other,null));
        assertEquals(0,preview.evaluate(other.toString(),RULES,Collections.<Long>emptySet(),Collections.singleton("counter.hidden"),0,0,null).toJson().path("projection").path("events").size());
    }

    @Test void normalizedInitialAttributesAndDisabledVitalsKeepExistingSemantics() {
        ObjectNode c=config();event(c,"seeded","初始属性达成").putArray("when").addObject().put("var","counter.ready").put("op","EQ").put("value",2);
        String rules="{\"schemaVersion\":1,\"stateEnabled\":true,\"hp\":{\"enabled\":false},\"luck\":{\"enabled\":false},\"attributes\":[{\"key\":\"counter.ready\",\"label\":\"进度\",\"initial\":2,\"min\":0,\"max\":3}]}";
        JsonNode result=preview.evaluate(c.toString(),rules,Collections.<Long>emptySet(),Collections.singleton("counter.ready"),0,0,null).toJson();assertEquals(1,result.path("projection").path("events").size());assertFalse(result.path("entryValues").has("sys.hp"));assertFalse(result.path("entryValues").has("sys.luck"));
        JsonNode none=preview.evaluate(config().toString(),null,Collections.<Long>emptySet(),Collections.<String>emptySet(),0,0,null).toJson();assertFalse(none.path("entryValues").has("sys.hp"));assertEquals(0,none.path("entryValues").path("sys.actions").asInt());
    }

    @Test void entryNeverSettlesCompletionBudgetRescueOrChapterEndAndDoesNotExposePrivateConfig() {
        ObjectNode c=config();c.put("actionLimit",1).put("rescueLimit",10);ObjectNode ch=chapter(c,0,1);
        c.withArray("items").addObject().put("id","later_item").put("kind","ITEM").put("name","章末物资");
        ch.withArray("grants").addObject().put("itemId","later_item").put("quantity",1).put("timing","END");
        ch.putArray("eggs").addObject().put("id","later").put("name","后续").put("content","不要提前触发").put("trigger","TIME").put("threshold",1);
        c.putArray("endings").addObject().put("id","success").put("title","不在入口判结局").put("fallback",true).put("success",true);
        JsonNode result=entry(c,0).toJson();assertEquals(1,quantity(result));assertFalse(result.path("projection").has("result"));assertEquals(0,result.path("projection").path("events").size());assertFalse(result.has("sessionId"));assertFalse(result.has("receipt"));assertFalse(result.has("config"));assertFalse(result.has("state"));assertFalse(result.toString().contains("不在入口判结局"));assertEquals(6,result.path("excluded").size());
    }

    @Test void malformedMissingAndOversizedJsonFailsBeforeEvaluation() {
        for(String value:Arrays.asList("", " ", "null", "[]", "{", "{} {}", repeat(" ",ThemeRuntimeDraftPreview.MAX_CONFIG_CHARS+1)))
            assertThrows(IllegalArgumentException.class,()->preview.evaluate(value,null,Collections.<Long>emptySet(),Collections.<String>emptySet(),0,0,null));
        assertThrows(IllegalArgumentException.class,()->preview.evaluate(null,null,Collections.<Long>emptySet(),Collections.<String>emptySet(),0,0,null));
        String tooDeep=repeat("[",65)+"0"+repeat("]",65);assertThrows(IllegalArgumentException.class,()->preview.evaluate(tooDeep,null,Collections.<Long>emptySet(),Collections.<String>emptySet(),0,0,null));
        String tooLargeRules=repeat("汉",6000);assertThrows(IllegalArgumentException.class,()->preview.evaluate(config().toString(),tooLargeRules,Collections.<Long>emptySet(),Collections.<String>emptySet(),0,0,null));
        assertThrows(IllegalArgumentException.class,()->preview.evaluate(config().toString(),"{",Collections.<Long>emptySet(),Collections.<String>emptySet(),0,0,null));
    }

    @Test void declarationShapesSizesRunClockAndChapterBoundsFailClosed() {
        String raw=config().toString();Set<Long> nodes=new HashSet<Long>();for(long i=1;i<=201;i++)nodes.add(i);Set<String> keys=new HashSet<String>();for(int i=0;i<257;i++)keys.add("counter.k"+i);
        assertThrows(IllegalArgumentException.class,()->preview.evaluate(raw,null,nodes,Collections.<String>emptySet(),0,0,null));assertThrows(IllegalArgumentException.class,()->preview.evaluate(raw,null,Collections.<Long>emptySet(),keys,0,0,null));
        for(Long node:Arrays.asList(null,0L,-1L))assertThrows(IllegalArgumentException.class,()->preview.evaluate(raw,null,Collections.singleton(node),Collections.<String>emptySet(),0,0,null));
        for(String key:Arrays.asList(null,"sys.hp","counter.A","counter."+repeat("a",49)))assertThrows(IllegalArgumentException.class,()->preview.evaluate(raw,null,Collections.<Long>emptySet(),Collections.singleton(key),0,0,null));
        assertThrows(IllegalArgumentException.class,()->preview.evaluate(raw,null,null,Collections.<String>emptySet(),0,0,null));assertThrows(IllegalArgumentException.class,()->preview.evaluate(raw,null,Collections.<Long>emptySet(),null,0,0,null));
        assertThrows(IllegalArgumentException.class,()->preview.evaluate(raw,null,Collections.<Long>emptySet(),Collections.<String>emptySet(),-1,0,null));assertThrows(IllegalArgumentException.class,()->preview.evaluate(raw,null,Collections.<Long>emptySet(),Collections.<String>emptySet(),Integer.MAX_VALUE,0,null));
        for(long time:new long[]{-1,ThemeRuntimeDraftPreview.MAX_EPOCH_MILLIS+1})assertThrows(IllegalArgumentException.class,()->preview.evaluate(raw,null,Collections.<Long>emptySet(),Collections.<String>emptySet(),0,time,null));
        for(Integer chapter:Arrays.asList(-1,0,200))assertThrows(IllegalArgumentException.class,()->preview.evaluate(raw,null,Collections.<Long>emptySet(),Collections.<String>emptySet(),0,0,chapter));
    }

    @Test void exactResourceBoundariesAndDraftOnlyMissingMediaAreAccepted() {
        ObjectNode c=config();chapter(c,199,1);Set<Long> nodes=new HashSet<Long>();for(long i=1;i<=200;i++)nodes.add(i);Set<String> keys=new HashSet<String>();for(int i=0;i<256;i++)keys.add("counter.k"+i);
        String raw=c.toString();raw+=repeat(" ",ThemeRuntimeDraftPreview.MAX_CONFIG_CHARS-raw.length());
        JsonNode result=preview.evaluate(raw,repeat(" ",ThemeRuntimeDraftPreview.MAX_RULES_BYTES),nodes,keys,ThemeRuntimeDraftPreview.MAX_RUN_INDEX,ThemeRuntimeDraftPreview.MAX_EPOCH_MILLIS,199).toJson();assertEquals(1,quantity(result));assertTrue(result.toString().length()<=ThemeRuntimeDraftPreview.MAX_OUTPUT_CHARS);
    }

    @Test void runtimeStatRejectionDoesNotProducePartialResultOrPolluteLaterCalls() {
        ObjectNode c=config();ObjectNode item=(ObjectNode)c.path("items").get(0);item.put("stat","HP").put("delta",1);chapter(c,0,1);
        String off="{\"schemaVersion\":1,\"checkEnabled\":false,\"hp\":{\"enabled\":false}}";
        assertThrows(IllegalStateException.class,()->preview.evaluate(c.toString(),off,Collections.<Long>emptySet(),Collections.<String>emptySet(),0,0,0));
        assertEquals(0,quantity(entry(config(),null).toJson()));
    }

    @Test void productionClassHasNoPersistenceMessagingSpringClockOrNetworkDependencies() throws Exception {
        assertEquals(0,ThemeRuntimeDraftPreview.class.getAnnotations().length);
        for(Class<?> type:new Class<?>[]{ThemeRuntimeDraftPreview.class,ThemeRuntimeDraftPreview.Result.class}) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream in=type.getResourceAsStream(type.getSimpleName().equals("Result")?"ThemeRuntimeDraftPreview$Result.class":"ThemeRuntimeDraftPreview.class")) {assertNotNull(in);byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);}
            String pool=new String(bytes.toByteArray(),StandardCharsets.ISO_8859_1);
            for(String forbidden:Arrays.asList("org/springframework","business/mapper","business/service/impl","ThemeBroadcastService","java/net/","java/nio/file/","java/lang/System","java/time/Clock","java/util/Random","java/util/UUID","TopicRouteSession")) assertFalse(pool.contains(forbidden),forbidden);
        }
    }
}
