package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ThemeRuntimeContentPackTest {
    @Test void frostDraftUsesGenericConfigurationAndReferenceMapping() throws Exception {
        String raw=new String(Files.readAllBytes(Paths.get("../docs/theme-runtime/frost-solo.draft.json")),StandardCharsets.UTF_8);
        ObjectNode config=ThemeRuntimeConfig.parseDraft(raw);
        assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.parse(raw),"尚未上传图片，草稿不能被误当成已发布内容");
        Set<Long> ids=new HashSet<>();Map<Long,Long> mapped=new HashMap<>();for(long i=1;i<=7;i++){ids.add(i);mapped.put(i,100+i);}
        ThemeRuntimeConfig.assertReferences(config,ids,Collections.singleton("clue.repair"));
        JsonNode copied=new ObjectMapper().readTree(ThemeRuntimeConfig.remapAll(raw,mapped,Collections.emptyMap(),null));assertTrue(copied.path("scenes").get(0).path("nodeConfigs").has("101"));
        JourneyRules rules=new JourneyRules();rules.setCheckEnabled(true);
        for(int i=0;i<3;i++) {
            ObjectNode scene=ThemeRuntimeConfig.freeze(config,i,0);TopicRouteEngine.State state=new TopicRouteEngine.State();state.setStatus("ACTIVE");state.getVariables().put("sys.hp",8);state.getVariables().put("sys.luck",3);ThemeRuntimeEngine engine=new ThemeRuntimeEngine();engine.initialize(scene,state);engine.events(scene,state,rules);
            choice(engine,scene,state,rules,1,"choose",i==2?"coat":"food");
            for(long node=1;node<=7;node++) {
                if(node==4)choice(engine,scene,state,rules,4,"choose","fuel");
                if(node==5)choice(engine,scene,state,rules,5,"choose",i==0?"leave":"take");
                if((i==0 && node==3)||(i==1 && node==4)||(i==2 && node==2))choice(engine,scene,state,rules,node,"help_engineer",i==1?"medical":"food");
                if((i!=2 && node==6)||(i==2 && node==5))choice(engine,scene,state,rules,node,"help_resident",i==2?"coat":"food");
                state.getCompletedNodeIds().add(node);engine.signal(scene,state,rules,"NODE",node,"","",0,"node:"+node);
            }
            List<String> pending=new ArrayList<>();state.getThemeRuntime().path("pending").fieldNames().forEachRemaining(pending::add);
            if(!pending.isEmpty())assertThrows(IllegalStateException.class,()->engine.terminal(scene,state,rules,"COMPLETED",12));
            for(String source:pending) {
                JsonNode reward=state.getThemeRuntime().path("pending").path(source);
                // 玩家保留结局需要的燃料，显式放弃口粮、医疗包或多余衣物来领取。
                while(engine.used(scene,state)+reward.path("quantity").asInt()>scene.path("capacity").asInt()) {
                    String discarded=null;for(String id:Arrays.asList("food","medical","coat"))if(state.getThemeRuntime().path("inventory").path(id).asInt()>0){discarded=id;break;}
                    assertNotNull(discarded,"草稿脚本须有可主动放弃的物资");engine.discard(scene,state,rules,discarded);
                }
                engine.receive(scene,state,rules,source,true);
            }
            assertTrue(state.getThemeRuntime().path("pending").isEmpty());
            engine.terminal(scene,state,rules,"COMPLETED",12);assertEquals("together",state.getThemeRuntime().path("result").path("endingId").asText());assertEquals(7,state.getThemeRuntime().path("result").path("actions").asInt());
        }
    }
    @Test void copiedSceneAskedConditionKeepsQuestionTailAndRejectsForeignNode() throws Exception {
        ObjectMapper json=new ObjectMapper();
        String raw=new String(Files.readAllBytes(Paths.get("../docs/theme-runtime/frost-solo.draft.json")),StandardCharsets.UTF_8);
        ObjectNode config=(ObjectNode)json.readTree(raw);
        ObjectNode node=(ObjectNode)config.path("scenes").get(0).path("nodeConfigs").path("1");
        node.putArray("requires").addObject().put("var","sys.asked.2.direction").put("op","EQ").put("value",1);
        Set<Long> ids=new HashSet<>();Map<Long,Long> mapped=new HashMap<>();for(long i=1;i<=7;i++){ids.add(i);mapped.put(i,100+i);}
        ThemeRuntimeConfig.assertReferences(config,ids,Collections.singleton("clue.repair"));
        JsonNode copied=json.readTree(ThemeRuntimeConfig.remapAll(config.toString(),mapped,Collections.emptyMap(),null));
        assertEquals("sys.asked.102.direction",copied.path("scenes").get(0).path("nodeConfigs").path("101").path("requires").get(0).path("var").asText());
        ((ObjectNode)node.path("requires").get(0)).put("var","sys.asked.900.direction");
        assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.assertReferences(config,ids,Collections.singleton("clue.repair")));
        assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.remapAll(config.toString(),mapped,Collections.emptyMap(),null));
    }
    private void choice(ThemeRuntimeEngine e,JsonNode c,TopicRouteEngine.State s,JourneyRules r,long n,String step,String option){e.signal(c,s,r,"CHOICE",n,step,option,0,"choice:"+n+":"+step+":"+option);}
}
