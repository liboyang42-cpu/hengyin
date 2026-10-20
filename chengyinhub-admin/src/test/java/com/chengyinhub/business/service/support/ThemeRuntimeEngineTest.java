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

    @Test void sceneCopyNeverReturnsUnparseableChapterOrNpcMappings() throws Exception {
        ObjectNode input=askedScene("sys.passed.1",true);
        ((ObjectNode)input.path("scenes").get(0).path("journeyStory")).putObject("call").put("npcId",7).put("opener","开场");
        String raw=input.toString();
        for(Long target:Arrays.asList(0L,-1L,Long.MIN_VALUE)) {
            assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.remapAll(raw,Collections.singletonMap(1L,101L),Collections.singletonMap(11L,target),Collections.singletonMap(7L,77L)));
            assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.remapAll(raw,Collections.singletonMap(1L,101L),Collections.singletonMap(11L,111L),Collections.singletonMap(7L,target)));
        }
    }

    @Test void sceneBindingLongMaxTargetSurvivesCopyParseAndReferenceValidation() throws Exception {
        ObjectNode input=askedScene("sys.asked.1.0_Question",false);
        String copied=ThemeRuntimeConfig.remapAll(input.toString(),Collections.singletonMap(1L,Long.MAX_VALUE),Collections.emptyMap(),Collections.emptyMap());
        ObjectNode parsed=ThemeRuntimeConfig.parse(copied);
        assertTrue(parsed.path("scenes").get(0).path("nodeConfigs").has(String.valueOf(Long.MAX_VALUE)));
        assertEquals("sys.asked.9223372036854775807.0_Question",askedVariable(parsed,false,String.valueOf(Long.MAX_VALUE)));
        ThemeRuntimeConfig.assertReferences(parsed,Collections.singleton(Long.MAX_VALUE),Collections.emptySet());
        assertEquals(parsed.toString(),ThemeRuntimeConfig.normalize(copied));
        String back=ThemeRuntimeConfig.remapNodes(copied,Collections.singletonMap(Long.MAX_VALUE,1L));
        assertNotNull(ThemeRuntimeConfig.parse(back));
        assertEquals("sys.asked.1.0_Question",askedVariable(json.readTree(back),false,"1"));
    }

    @Test void sceneBindingKeysRejectAliasesAndOutOfRangeValuesBeforeCopy() throws Exception {
        for(String key:Arrays.asList("0","01","-1","+1","1.5","1e0"," ","","9223372036854775808","18446744073709551617")) {
            ObjectNode input=askedScene("sys.passed.1",false);
            ObjectNode configs=(ObjectNode)input.path("scenes").get(0).path("nodeConfigs");
            JsonNode config=configs.remove("1");configs.set(key,config);
            assertReferenceRejectedEverywhere(input);
        }
    }

    @Test void sceneBindingCopyRejectsDuplicateTargetsInsteadOfOverwritingConfiguration() throws Exception {
        ObjectNode input=askedScene("sys.passed.1",false);
        ((ObjectNode)input.path("scenes").get(0).path("nodeConfigs")).putObject("2").put("schemaVersion",1);
        Map<Long,Long> targets=new LinkedHashMap<>();targets.put(1L,101L);targets.put(2L,101L);
        String raw=input.toString();
        assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.remapAll(raw,targets,Collections.emptyMap(),Collections.emptyMap()));
        assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.remapNodes(raw,targets));
        assertEquals(raw,input.toString());
    }

    private ObjectNode completedNodeScene(String value,boolean story) throws Exception {
        ObjectNode root=askedScene("sys.passed.1",story);
        ObjectNode condition=json.createObjectNode().put("op","NODE_COMPLETED");condition.set("nodeId",json.readTree(value));
        JsonNode scene=root.path("scenes").get(0);
        if(story)((ObjectNode)scene.path("journeyStory").path("stages").get(0).path("end").path("changed").get(0)).set("when",condition);
        else ((ObjectNode)scene.path("nodeConfigs").path("1")).putArray("requires").add(condition);
        return root;
    }

    private ObjectNode dispositionScene(String value) throws Exception {
        ObjectNode root=askedScene("sys.passed.1",false);
        ((ObjectNode)root.path("scenes").get(0)).set("journeyStory",json.readTree("{\"schemaVersion\":1,\"ending\":{\"dispositionNodeId\":"+value
                + ",\"endings\":[{\"code\":\"win\",\"title\":\"对\",\"when\":[{\"var\":\"sys.outcome.1\",\"op\":\"EQ\",\"value\":1}]},{\"code\":\"other\",\"title\":\"其他\",\"fallback\":true}]}}"));
        return root;
    }

    private void assertReferenceRejectedEverywhere(ObjectNode input) {
        String raw=input.toString();Map<Long,Long> nodes=Collections.singletonMap(1L,101L);
        assertAll(
            ()->assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.parse(raw)),
            ()->assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.parseDraft(raw)),
            ()->assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.normalize(raw)),
            ()->assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.assertReferences(input,Collections.singleton(1L),Collections.emptySet())),
            ()->assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.remapAll(raw,nodes,Collections.singletonMap(11L,111L),Collections.emptyMap())),
            ()->assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.remapNodes(raw,nodes))
        );
    }

    @Test void sceneNodeReferencesRejectNumericAliasesBeforeNormalizationAndCopy() throws Exception {
        List<org.junit.jupiter.api.function.Executable> checks=new ArrayList<>();
        for(boolean story:Arrays.asList(false,true)) {
            ObjectNode valid=completedNodeScene("1",story);
            assertNotNull(ThemeRuntimeConfig.parse(valid.toString()));
            ThemeRuntimeConfig.assertReferences(valid,Collections.singleton(1L),Collections.emptySet());
            for(String value:Arrays.asList("0","-1","1.5","1.0","\"1\"","true","null","9223372036854775808","-9223372036854775809","18446744073709551617","-18446744073709551615")) {
                ObjectNode invalid=completedNodeScene(value,story);checks.add(()->assertReferenceRejectedEverywhere(invalid));
            }
        }
        assertAll(checks);
    }

    @Test void dispositionNodeReferencesRejectNumericAliasesAtThemeBoundary() throws Exception {
        ObjectNode valid=dispositionScene("1");assertNotNull(ThemeRuntimeConfig.parse(valid.toString()));
        ThemeRuntimeConfig.assertReferences(valid,Collections.singleton(1L),Collections.emptySet());
        List<org.junit.jupiter.api.function.Executable> checks=new ArrayList<>();
        for(String value:Arrays.asList("0","-1","1.5","1.0","\"1\"","true","null","9223372036854775808","-9223372036854775809","18446744073709551617","-18446744073709551615")) {
            ObjectNode invalid=dispositionScene(value);checks.add(()->assertReferenceRejectedEverywhere(invalid));
        }
        assertAll(checks);
    }

    @Test void themeCopyRejectsNonpositiveMappedNodeIds() throws Exception {
        List<String> raws=Arrays.asList(askedScene("sys.asked.1.question",false).toString(),
                completedNodeScene("1",false).toString(),completedNodeScene("1",true).toString(),dispositionScene("1").toString());
        List<org.junit.jupiter.api.function.Executable> checks=new ArrayList<>();
        for(String raw:raws)for(Long target:Arrays.asList(0L,-1L,Long.MIN_VALUE)) {
            checks.add(()->assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.remapAll(raw,Collections.singletonMap(1L,target),Collections.singletonMap(11L,111L),Collections.emptyMap())));
            checks.add(()->assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.remapNodes(raw,Collections.singletonMap(1L,target))));
        }
        assertAll(checks);
    }

    @Test void themeCopyKeepsLongMaxNodeReferencesExact() throws Exception {
        Map<Long,Long> targets=new LinkedHashMap<>();targets.put(1L,101L);targets.put(2L,Long.MAX_VALUE);
        Set<Long> owned=new HashSet<Long>(Arrays.asList(101L,Long.MAX_VALUE));
        for(boolean story:Arrays.asList(false,true)) {
            String raw=completedNodeScene("2",story).toString();
            ObjectNode copy=(ObjectNode)json.readTree(ThemeRuntimeConfig.remapAll(raw,targets,Collections.singletonMap(11L,111L),Collections.emptyMap()));
            JsonNode scene=copy.path("scenes").get(0);
            JsonNode condition=story?scene.path("journeyStory").path("stages").get(0).path("end").path("changed").get(0).path("when")
                    :scene.path("nodeConfigs").path("101").path("requires").get(0);
            assertEquals(Long.MAX_VALUE,condition.path("nodeId").longValue());
            ThemeRuntimeConfig.assertReferences(copy,owned,Collections.emptySet());
            assertNotNull(ThemeRuntimeConfig.parse(copy.toString()));
        }
        ObjectNode disposition=dispositionScene("2");
        ((ObjectNode)disposition.path("scenes").get(0).path("journeyStory").path("ending").path("endings").get(0).path("when").get(0)).put("var","sys.outcome.2");
        ObjectNode copied=(ObjectNode)json.readTree(ThemeRuntimeConfig.remapAll(disposition.toString(),targets,Collections.emptyMap(),Collections.emptyMap()));
        assertEquals(Long.MAX_VALUE,copied.path("scenes").get(0).path("journeyStory").path("ending").path("dispositionNodeId").longValue());
        ThemeRuntimeConfig.assertReferences(copied,owned,Collections.emptySet());
        assertNotNull(ThemeRuntimeConfig.parse(copied.toString()));
    }

    private JsonNode normalizeAndFreezeNode(String raw) throws Exception {
        ObjectNode input=askedScene("sys.passed.1",false);
        ((ObjectNode)input.path("scenes").get(0).path("nodeConfigs")).set("1",json.readTree(raw));
        JsonNode expected=json.readTree(new AdvancedGameConfigValidator().validateAndNormalize(raw));
        ObjectNode normalized=(ObjectNode)json.readTree(ThemeRuntimeConfig.normalize(input.toString()));
        JsonNode frozen=ThemeRuntimeConfig.freeze(normalized,0,0).path("nodeConfigs").path("1");
        assertEquals(expected,frozen,"场景必须保留既有validator的规范化输出");
        assertEquals(normalized,ThemeRuntimeConfig.parse(normalized.toString()),"重新读取不能改变规范化结果");
        return json.readTree(com.chengyinhub.business.util.AdvancedGamePublicProjection.safeConfigJson(frozen.toString()));
    }

    @Test void sceneNormalizationMustRetainLegacyPricePairRetirement() throws Exception {
        JsonNode publicConfig=normalizeAndFreezeNode("{\"schemaVersion\":1,\"pricePair\":{\"enabled\":true,\"items\":[{\"id\":\"old\",\"name\":\"old\",\"price\":10}]}}");
        assertFalse(publicConfig.path("pricePair").path("enabled").asBoolean());
        assertFalse(publicConfig.path("pricePair").has("items"));
        assertFalse(publicConfig.toString().contains("\"price\""));
    }

    @Test void sceneNormalizationKeepsProfileQuestionsRequired() throws Exception {
        JsonNode publicConfig=normalizeAndFreezeNode("{\"schemaVersion\":1,\"profile\":{\"enabled\":true,\"questions\":[{\"key\":\"name\",\"label\":\"姓名\",\"kind\":\"text\",\"required\":false}]}}");
        assertTrue(publicConfig.path("profile").path("questions").get(0).path("required").asBoolean());
    }

    @Test void sceneNormalizationClampsFrameOpacityBeforeFreezeAndProjection() throws Exception {
        for(int opacity:Arrays.asList(-10,140)) {
            JsonNode publicConfig=normalizeAndFreezeNode("{\"schemaVersion\":1,\"photoCheck\":{\"enabled\":true,\"title\":\"拍照\",\"requirement\":\"拍下门牌\",\"maxTries\":3,\"fallback\":\"retake\",\"frameUrl\":\"https://example.test/frame.png\",\"frameOpacity\":"+opacity+"}}");
            assertEquals(opacity<0?0:100,publicConfig.path("photoCheck").path("frameOpacity").asInt());
        }
    }

    private ObjectNode askedScene(String variable, boolean story) throws Exception {
        ObjectNode root=json.createObjectNode().put("schemaVersion",1).put("currentScene","scene");
        ObjectNode scene=root.putArray("scenes").addObject().put("id","scene").put("name","sys.asked.1.question");
        scene.putObject("nodeConfigs").putObject("1").put("schemaVersion",1);
        ObjectNode condition=json.createObjectNode().put("var",variable).put("op","EQ").put("value",1);
        if(story) {
            ObjectNode chapter=scene.putObject("journeyStory").put("schemaVersion",1)
                    .putArray("stages").addObject().put("chapterId",11);
            chapter.putObject("end").putArray("changed").addObject().put("text","asked").set("when",condition);
        } else {
            ((ObjectNode)scene.path("nodeConfigs").path("1")).putArray("requires").add(condition);
        }
        return root;
    }

    private String askedVariable(JsonNode config,boolean story,String node) {
        JsonNode scene=config.path("scenes").get(0);
        return (story ? scene.path("journeyStory").path("stages").get(0).path("end").path("changed").get(0).path("when")
                : scene.path("nodeConfigs").path(node).path("requires").get(0)).path("var").asText();
    }

    @Test void sceneAskedReferenceMustRemapNodeId() throws Exception {
        for(String question:Arrays.asList("question","A","0","_","-","01_Question-a1","abcdefghijklmnopqrstuvwxyzABC_12")) {
            ObjectNode input=ThemeRuntimeConfig.parse(askedScene("sys.asked.1."+question,false).toString());
            String before=input.toString();
            JsonNode output=json.readTree(ThemeRuntimeConfig.remapAll(before,Collections.singletonMap(1L,101L),Collections.emptyMap(),Collections.emptyMap()));
            assertEquals("sys.asked.101."+question,askedVariable(output,false,"101"));
            assertEquals("sys.asked.1.question",output.path("scenes").get(0).path("name").asText(),"只重映射条件的节点段，不能全局替换文本");
            assertEquals(before,input.toString());
            ThemeRuntimeConfig.assertReferences((ObjectNode)output,Collections.singleton(101L),Collections.emptySet());
            assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.assertReferences((ObjectNode)output,Collections.singleton(1L),Collections.emptySet()));
        }
    }

    @Test void sceneAskedReferenceMustRejectForeignNode() throws Exception {
        for(boolean story:Arrays.asList(false,true)) {
            ObjectNode input=ThemeRuntimeConfig.parse(askedScene("sys.asked.999.question",story).toString());
            assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.assertReferences(input,Collections.singleton(1L),Collections.emptySet()));
        }
    }

    @Test void storyAskedReferenceMustRemapNodeId() throws Exception {
        for(String question:Arrays.asList("question","A","0","_","-","01_Question-a1","abcdefghijklmnopqrstuvwxyzABC_12")) {
            ObjectNode input=ThemeRuntimeConfig.parse(askedScene("sys.asked.1."+question,true).toString());
            JsonNode output=json.readTree(ThemeRuntimeConfig.remapAll(input.toString(),Collections.singletonMap(1L,101L),Collections.singletonMap(11L,111L),Collections.emptyMap()));
            assertEquals("sys.asked.101."+question,askedVariable(output,true,"101"));
            assertEquals(111,output.path("scenes").get(0).path("journeyStory").path("stages").get(0).path("chapterId").asInt());
            ThemeRuntimeConfig.assertReferences((ObjectNode)output,Collections.singleton(101L),Collections.emptySet());
        }
    }

    @Test void askedReferencesRejectMissingNodeMappings() throws Exception {
        for(boolean story:Arrays.asList(false,true)) {
            String input=ThemeRuntimeConfig.parse(askedScene("sys.asked.2.question",story).toString()).toString();
            assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.remapAll(input,Collections.singletonMap(1L,101L),Collections.singletonMap(11L,111L),Collections.emptyMap()));
            assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.remapNodes(input,Collections.singletonMap(1L,101L)));
        }
    }

    @Test void askedReferencesRejectMalformedOrOverflowNodesAndInvalidQuestionIds() throws Exception {
        List<String> invalid=Arrays.asList("sys.asked", "sys.asked.0.q", "sys.asked.-1.q", "sys.asked.+1.q",
                "sys.asked.01.q", "sys.asked.1.5.q", "sys.asked.9223372036854775808.q",
                "sys.asked.-9223372036854775808.q", "sys.asked.18446744073709551617.q",
                "sys.asked.1.", "sys.asked.1.abcdefghijklmnopqrstuvwxyzABC_123",
                "sys.asked.1.two.words", "sys.asked.1.a b", "sys.asked.1.问题");
        for(boolean story:Arrays.asList(false,true)) for(String variable:invalid) {
            ObjectNode input=askedScene(variable,story);String raw=input.toString();
            assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.assertReferences(input,Collections.singleton(1L),Collections.emptySet()),variable);
            assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.remapAll(raw,Collections.singletonMap(1L,101L),Collections.singletonMap(11L,111L),Collections.emptyMap()),variable);
            assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.parse(raw),variable);
        }
    }

    @Test void askedReferencesAcceptLongMaxWithoutConfusingNodeAndQuestionSegments() throws Exception {
        for(boolean story:Arrays.asList(false,true)) {
            ObjectNode input=ThemeRuntimeConfig.parse(askedScene("sys.asked.9223372036854775807.1_Question-1",story).toString());
            ThemeRuntimeConfig.assertReferences(input,new HashSet<Long>(Arrays.asList(1L,Long.MAX_VALUE)),Collections.emptySet());
            Map<Long,Long> nodes=new LinkedHashMap<>();nodes.put(1L,101L);nodes.put(Long.MAX_VALUE,102L);
            JsonNode output=json.readTree(ThemeRuntimeConfig.remapAll(input.toString(),nodes,Collections.singletonMap(11L,111L),Collections.emptyMap()));
            assertEquals("sys.asked.102.1_Question-1",askedVariable(output,story,"101"));
        }
    }

    private static String itemCondition(String op, String quantity) {
        return "{\"op\":\"" + op + "\",\"itemId\":\"food\",\"quantity\":" + quantity + "}";
    }

    private static String conditionalRoute(String condition) {
        return "{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2],\"edges\":["
                + "{\"id\":\"next\",\"fromNodeId\":1,\"toNodeId\":2,"
                + "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"DONE\"},\"conditions\":[" + condition + "]}]}";
    }

    private static String conditionalStory(String condition) {
        return "{\"schemaVersion\":1,\"stages\":[{\"chapterId\":1,\"end\":{\"changed\":["
                + "{\"when\":" + condition + ",\"text\":\"提示\"}]}}]}";
    }

    @Test void itemConditionQuantityBoundariesNeverTruncateInAnyValidator() {
        java.util.List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
        for (String op : Arrays.asList("ITEM_HELD", "ITEM_ACQUIRED")) {
            for (String quantity : Arrays.asList("1", "999")) {
                String condition = itemCondition(op, quantity);
                assertNotNull(new AdvancedGameConfigValidator().validateAndNormalize(
                        "{\"schemaVersion\":1,\"requires\":[" + condition + "]}"));
                assertNotNull(new JourneyStoryValidator().validate(conditionalStory(condition), null));
                assertNotNull(new TopicRouteConfigValidator().validateAndNormalize(conditionalRoute(condition),
                        new LinkedHashSet<Long>(Arrays.asList(1L, 2L))));
            }
            for (String quantity : Arrays.asList("0", "-1", "1000", "1.5", "\"1\"", "null", "true",
                    "9223372036854775807", "-9223372036854775808", "9223372036854775808",
                    "-9223372036854775809", "18446744073709551617", "-18446744073709551615")) {
                String condition = itemCondition(op, quantity);
                checks.add(() -> assertThrows(IllegalArgumentException.class,
                        () -> new AdvancedGameConfigValidator().validateAndNormalize(
                                "{\"schemaVersion\":1,\"requires\":[" + condition + "]}"), "advanced " + quantity));
                checks.add(() -> assertThrows(IllegalArgumentException.class,
                        () -> new JourneyStoryValidator().validate(conditionalStory(condition), null), "story " + quantity));
                checks.add(() -> assertThrows(IllegalArgumentException.class,
                        () -> new TopicRouteConfigValidator().validateAndNormalize(conditionalRoute(condition),
                                new LinkedHashSet<Long>(Arrays.asList(1L, 2L))), "route " + quantity));
                checks.add(() -> {
                    TopicRouteEngine.State state = new TopicRouteEngine.State();
                    state.getThemeRuntime().with("inventory").put("food",999);
                    state.getThemeRuntime().with("acquired").put("food",999);
                    JsonNode conditions=json.readTree("["+condition+"]");
                    assertFalse(new TopicRouteEngine().conditionsPass(conditions,state),"route runtime " + quantity);
                    assertFalse(engine.conditions(conditions,state),"theme runtime " + quantity);
                });
            }
        }
        assertAll(checks);
    }

    @Test void routeConditionsDistinguishHeldAndAcquiredAndCopyNestedState() throws Exception {
        ObjectNode c=config();TopicRouteEngine route=new TopicRouteEngine();
        String graph=conditionalRoute(itemCondition("ITEM_ACQUIRED","2"));
        TopicRouteEngine.State before=route.initial(graph);engine.initialize(c,before);node(c,before,1);
        engine.discard(c,before,rules(),"food");
        assertFalse(route.conditionsPass(json.readTree("["+itemCondition("ITEM_HELD","2")+"]"),before));
        assertTrue(route.conditionsPass(json.readTree("["+itemCondition("ITEM_ACQUIRED","2")+"]"),before));
        String frozen=json.writeValueAsString(before);
        TopicRouteEngine.State after=route.advance(graph,before,
                new com.chengyinhub.business.domain.route.NodeOutcome(1L,"DONE","receipt"),7L).getState();
        assertNotSame(before.getThemeRuntime(),after.getThemeRuntime());
        after.getThemeRuntime().with("inventory").put("food",99);
        after.getThemeRuntime().withArray("journal").addObject().put("action","changed");
        assertEquals(frozen,json.writeValueAsString(before));
    }

    @Test void repeatedInitializationNeverResetsAcquiredInventoryOrActionCount() throws Exception {
        ObjectNode c=config();TopicRouteEngine.State s=state(c);node(c,s,1);
        String frozen=json.writeValueAsString(s);engine.initialize(c,s);assertEquals(frozen,json.writeValueAsString(s));
    }

    @Test void sceneCopyRemapsNodeChapterAndNpcReferencesOrFailsClosed() throws Exception {
        ObjectNode c=config();c.put("currentScene","scene");
        ObjectNode scene=c.putArray("scenes").addObject().put("id","scene").put("name","场景");
        scene.putObject("nodeConfigs").set("1",json.readTree("{\"schemaVersion\":1,\"requires\":["
                + "{\"op\":\"NODE_COMPLETED\",\"nodeId\":2},{\"var\":\"sys.passed.1\",\"op\":\"EQ\",\"value\":1}]}"));
        scene.set("journeyStory",json.readTree("{\"schemaVersion\":1,\"openingChapterId\":11,"
                + "\"call\":{\"npcId\":7,\"opener\":\"开场\"},\"stages\":[{\"chapterId\":11}]}"));
        String raw=ThemeRuntimeConfig.parse(c.toString()).toString();
        Map<Long,Long> nodes=new LinkedHashMap<>();nodes.put(1L,101L);nodes.put(2L,102L);
        Map<Long,Long> chapters=Collections.singletonMap(11L,111L),npcs=Collections.singletonMap(7L,77L);
        ObjectNode copied=(ObjectNode)json.readTree(ThemeRuntimeConfig.remapAll(raw,nodes,chapters,npcs));
        ThemeRuntimeConfig.assertReferences(copied,new LinkedHashSet<Long>(Arrays.asList(101L,102L)),Collections.emptySet());
        JsonNode copiedScene=copied.path("scenes").get(0);
        assertFalse(copiedScene.path("nodeConfigs").has("1"));
        assertEquals(102,copiedScene.path("nodeConfigs").path("101").path("requires").get(0).path("nodeId").asInt());
        assertEquals("sys.passed.101",copiedScene.path("nodeConfigs").path("101").path("requires").get(1).path("var").asText());
        assertEquals(111,copiedScene.path("journeyStory").path("openingChapterId").asInt());
        assertEquals(111,copiedScene.path("journeyStory").path("stages").get(0).path("chapterId").asInt());
        assertEquals(77,copiedScene.path("journeyStory").path("call").path("npcId").asInt());
        assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.remapAll(raw,Collections.singletonMap(1L,101L),chapters,npcs));
        assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.remapAll(raw,nodes,Collections.emptyMap(),npcs));
        assertThrows(IllegalArgumentException.class,()->ThemeRuntimeConfig.remapAll(raw,nodes,chapters,Collections.emptyMap()));
        ObjectNode frozen=ThemeRuntimeConfig.freeze(copied,0,0);String before=frozen.toString();
        ((ObjectNode)copiedScene.path("journeyStory").path("call")).put("opener","改写");
        ((ObjectNode)copiedScene.path("nodeConfigs").path("101")).remove("requires");
        assertEquals(before,frozen.toString());
        assertEquals(raw,ThemeRuntimeConfig.parse(c.toString()).toString());
    }

    @Test void themeConditionsNeverBypassFeatureTimeWindowZoneValidation() {
        String raw="{\"schemaVersion\":1,\"requires\":["+itemCondition("ITEM_HELD","1")+"],"
                + "\"timeWindow\":{\"enabled\":true,\"openFrom\":\"09:00\",\"openTo\":\"17:00\",\"timeZone\":\"Asia/Shanghai\"}}";
        assertNotNull(new AdvancedGameConfigValidator().validateAndNormalize(raw));
        assertThrows(IllegalArgumentException.class,()->new AdvancedGameConfigValidator().validateAndNormalize(raw.replace("Asia/Shanghai","Unknown/Zone")));
    }

    @Test void noThemeHooksLeaveLegacyStateUntouched() throws Exception {
        TopicRouteEngine.State s=new TopicRouteEngine.State();String before=json.writeValueAsString(s);
        engine.initialize(null,s);engine.signal(null,s,rules(),"NODE",1L,"","",0,"none");
        engine.events(null,s,rules());engine.reconcileHeld(null,s,rules());
        engine.chapterStart(null,s,rules(),json.createObjectNode(),100);
        engine.chapterEnd(null,s,rules(),json.createObjectNode(),100);
        engine.chapterSteps(s,100);engine.chapterTick(null,s,rules(),100);
        engine.terminal(null,s,rules(),"COMPLETED",100);
        assertEquals(before,json.writeValueAsString(s));
    }

    private interface EndedMutation { void apply(ObjectNode c,TopicRouteEngine.State s,JsonNode chapter) throws Exception; }

    private void assertEndedUnchanged(String name,boolean reject,EndedMutation mutation) throws Exception {
        for (String reason : Arrays.asList("COMPLETED", "EXHAUSTED", "ABANDONED", "BUDGET")) {
        ObjectNode c=config();c.put("capacity",2);TopicRouteEngine.State s=state(c);node(c,s,1);node(c,s,2);
        JsonNode chapter=json.readTree("{\"index\":0,\"hpCost\":1,\"luckCost\":1,\"grants\":[],\"eggs\":[]}");
        engine.chapterStart(c,s,rules(),chapter,0);
        // The last route decision may already be COMPLETED before its trusted grants are frozen.
        s.setStatus("COMPLETED".equals(reason)?"COMPLETED":"ACTIVE");engine.terminal(c,s,rules(),reason,100);
        assertEquals("COMPLETED".equals(reason)?"COMPLETED":"ENDED",s.getStatus());
        assertTrue(s.getThemeRuntime().has("result"));
        // A pending item can exist at terminal. Even if capacity becomes available, it stays frozen.
        c.put("capacity",3);String before=json.writeValueAsString(s);
        if(reject)assertThrows(IllegalStateException.class,()->mutation.apply(c,s,chapter),name);
        else mutation.apply(c,s,chapter);
        assertEquals(before,json.writeValueAsString(s),name+"/"+reason);
        }
    }

    @Test void terminalSnapshotStopsEveryMutationEntryPointButKeepsReadViews() {
        assertAll(
            ()->assertEndedUnchanged("initialize",false,(c,s,ch)->engine.initialize(c,s)),
            ()->assertEndedUnchanged("signal",false,(c,s,ch)->engine.signal(c,s,rules(),"NODE",3L,"","",0,"late")),
            ()->assertEndedUnchanged("events",false,(c,s,ch)->{c.withArray("events").addObject().put("id","late").put("content","late");engine.events(c,s,rules());}),
            ()->assertEndedUnchanged("receive",true,(c,s,ch)->engine.receive(c,s,rules(),"grant:dress",true)),
            ()->assertEndedUnchanged("use",true,(c,s,ch)->engine.use(c,s,rules(),"food")),
            ()->assertEndedUnchanged("discard",true,(c,s,ch)->engine.discard(c,s,rules(),"food")),
            ()->assertEndedUnchanged("reconcileHeld",false,(c,s,ch)->engine.reconcileHeld(c,s,rules())),
            ()->assertEndedUnchanged("terminal",false,(c,s,ch)->engine.terminal(c,s,rules(),"ABANDONED",999)),
            ()->assertEndedUnchanged("chapterStart",false,(c,s,ch)->{((ObjectNode)ch).put("index",1);engine.chapterStart(c,s,rules(),ch,999);}),
            ()->assertEndedUnchanged("chapterEnd",false,(c,s,ch)->engine.chapterEnd(c,s,rules(),ch,999)),
            ()->assertEndedUnchanged("chapterSteps",false,(c,s,ch)->engine.chapterSteps(s,100)),
            ()->assertEndedUnchanged("chapterTick",false,(c,s,ch)->{((ObjectNode)ch).putArray("eggs").addObject().put("id","late").put("trigger","TIME").put("threshold",1).put("content","late");c.putArray("chapters").add(ch);engine.chapterTick(c,s,rules(),999999);}),
            ()->assertEndedUnchanged("legacyMedal",true,(c,s,ch)->engine.legacyMedal(c,s,rules(),"token")),
            ()->assertEndedUnchanged("views",false,(c,s,ch)->{assertNotNull(engine.view(c,s));assertNotNull(engine.card(s.getThemeRuntime().get("result")));engine.conditions(json.createArrayNode(),s);engine.reduceHpCost(c,s,3);})
        );
    }

    @Test void legacyNoThemeSerializationKeepsExactPreIntegrationBytes() throws Exception {
        TopicRouteEngine route=new TopicRouteEngine();
        String graph="{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2],\"variables\":{\"clues\":0},\"edges\":[{\"id\":\"next\",\"fromNodeId\":1,\"toNodeId\":2,\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"DONE\"},\"effects\":[{\"op\":\"INC\",\"var\":\"clues\",\"value\":1}]}]}";
        String[] golden={
            "{\"status\":null,\"currentNodeId\":null,\"recommendedNodeId\":null,\"nodeStates\":{},\"completedNodeIds\":[],\"visitedNodeIds\":[],\"variables\":{},\"tags\":[],\"usedEdgeIds\":[],\"edgeVisits\":{},\"decisionLog\":[],\"pull\":0}",
            "{\"status\":\"ACTIVE\",\"currentNodeId\":1,\"recommendedNodeId\":1,\"nodeStates\":{\"1\":\"PLAYABLE\",\"2\":\"HIDDEN\"},\"completedNodeIds\":[],\"visitedNodeIds\":[1],\"variables\":{\"clues\":0},\"tags\":[],\"usedEdgeIds\":[],\"edgeVisits\":{},\"decisionLog\":[],\"pull\":0}",
            "{\"status\":\"ACTIVE\",\"currentNodeId\":2,\"recommendedNodeId\":2,\"nodeStates\":{\"1\":\"COMPLETED\",\"2\":\"PLAYABLE\"},\"completedNodeIds\":[1],\"visitedNodeIds\":[1,2],\"variables\":{\"clues\":1.0},\"tags\":[],\"usedEdgeIds\":[\"next\"],\"edgeVisits\":{\"next\":1},\"decisionLog\":[{\"fromNodeId\":1,\"outcomeCode\":\"DONE\",\"edgeId\":\"next\",\"toNodeId\":2,\"reason\":\"MATCHED\",\"at\":0}],\"pull\":1}",
            "{\"status\":\"COMPLETED\",\"currentNodeId\":null,\"recommendedNodeId\":null,\"nodeStates\":{\"1\":\"COMPLETED\",\"2\":\"COMPLETED\"},\"completedNodeIds\":[1,2],\"visitedNodeIds\":[1,2],\"variables\":{\"clues\":1.0},\"tags\":[],\"usedEdgeIds\":[\"next\"],\"edgeVisits\":{\"next\":1},\"decisionLog\":[{\"fromNodeId\":1,\"outcomeCode\":\"DONE\",\"edgeId\":\"next\",\"toNodeId\":2,\"reason\":\"MATCHED\",\"at\":0},{\"fromNodeId\":2,\"outcomeCode\":\"DONE\",\"edgeId\":null,\"toNodeId\":null,\"reason\":\"TERMINAL\",\"at\":0}],\"pull\":1}",
            "{\"status\":\"COMPLETED\",\"currentNodeId\":null,\"recommendedNodeId\":null,\"nodeStates\":{\"1\":\"COMPLETED\",\"2\":\"COMPLETED\"},\"completedNodeIds\":[1,2],\"visitedNodeIds\":[1,2],\"variables\":{\"clues\":1.0},\"tags\":[],\"usedEdgeIds\":[\"next\"],\"edgeVisits\":{\"next\":1},\"decisionLog\":[{\"fromNodeId\":1,\"outcomeCode\":\"DONE\",\"edgeId\":\"next\",\"toNodeId\":2,\"reason\":\"MATCHED\",\"at\":0},{\"fromNodeId\":2,\"outcomeCode\":\"DONE\",\"edgeId\":null,\"toNodeId\":null,\"reason\":\"TERMINAL\",\"at\":0}],\"pull\":1}"
        };
        TopicRouteEngine.State empty=new TopicRouteEngine.State();
        assertEquals(golden[0],json.writeValueAsString(empty));
        empty.setThemeRuntime(null);assertEquals(golden[0],json.writeValueAsString(empty));
        TopicRouteEngine.State s=route.initial(graph);assertEquals(golden[1],json.writeValueAsString(s));
        s=route.advance(graph,s,new com.chengyinhub.business.domain.route.NodeOutcome(1L,"DONE","receipt-1"),7L).getState();
        assertEquals(golden[2],json.writeValueAsString(s));
        s=route.advance(graph,s,new com.chengyinhub.business.domain.route.NodeOutcome(2L,"DONE","receipt-2"),7L).getState();
        assertEquals(golden[3],json.writeValueAsString(s));
        assertEquals(golden[4],json.writeValueAsString(json.readValue(golden[3],TopicRouteEngine.State.class)));
    }

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
    @Test void terminalRewardCapacityFailureDoesNotFreezeOrLeavePartialEndingGrant() throws Exception {
        ObjectNode c=config();c.withArray("grants").addObject().put("id","ending-food").put("source","ENDING").put("itemId","food").put("quantity",1);
        TopicRouteEngine.State state=state(c);node(c,state,1);node(c,state,2);JsonNode before=json.valueToTree(state);
        assertThrows(IllegalStateException.class,()->engine.terminal(c,state,rules(),"COMPLETED",100));assertEquals(before,json.valueToTree(state));
        engine.discard(c,state,rules(),"food");engine.terminal(c,state,rules(),"COMPLETED",100);assertEquals("COMPLETED",state.getStatus());assertTrue(state.getThemeRuntime().path("pending").isEmpty());assertTrue(state.getThemeRuntime().path("sources").path("grant:ending-food").asBoolean());
    }

    @Test void completedClaimDiscardsOwnedAtomicallyWithoutItemOrEventEffects() throws Exception {
        ObjectNode c=config();c.put("capacity",2);TopicRouteEngine.State state=state(c);node(c,state,1);node(c,state,2);state.setStatus("COMPLETED");state.getThemeRuntime().putObject("result").put("hp",6);
        JsonNode before=json.valueToTree(state);assertThrows(IllegalStateException.class,()->engine.claimPending(c,state,"grant:dress",null));assertEquals(before,json.valueToTree(state));
        assertThrows(IllegalStateException.class,()->engine.claimPending(c,state,"grant:dress",json.readTree("[{\"itemId\":\"food\",\"quantity\":3}]")));assertEquals(before,json.valueToTree(state));
        ObjectNode receipt=engine.claimPending(c,state,"grant:dress",json.readTree("[{\"itemId\":\"food\",\"quantity\":1}]"));assertEquals("coat",receipt.path("itemId").asText());assertEquals(6,state.getVariables().get("sys.hp"));assertEquals(before.path("themeRuntime").path("result"),state.getThemeRuntime().path("result"));assertEquals(before.path("themeRuntime").path("events"),state.getThemeRuntime().path("events"));assertTrue(state.getThemeRuntime().path("pending").isEmpty());
        assertThrows(IllegalStateException.class,()->engine.claimPending(c,state,"grant:dress",null));
    }

    @Test void configuredValueNamesReadBackAndMissingNamesAreBlank() throws Exception {
        ObjectNode c=config(); c.put("hpName", "生命").put("luckName", "运气").put("actionName", "行动");
        c=ThemeRuntimeConfig.parse(c.toString());
        JsonNode view=engine.view(c,state(c));
        assertEquals("生命", view.path("hpName").asText()); assertEquals("运气", view.path("luckName").asText()); assertEquals("行动", view.path("actionName").asText());
        c.remove(Arrays.asList("hpName", "luckName", "actionName"));
        view=engine.view(c,state(c));
        assertEquals("", view.path("hpName").asText()); assertEquals("", view.path("luckName").asText()); assertEquals("", view.path("actionName").asText());
        c.put("actionName", "abcdefghijklmnopqrstu");
        ObjectNode invalid=c; assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parse(invalid.toString()));
        c.put("actionName", 3); assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parse(invalid.toString()));
    }
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
