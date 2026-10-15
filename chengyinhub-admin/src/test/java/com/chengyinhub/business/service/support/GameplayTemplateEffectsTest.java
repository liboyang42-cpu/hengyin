package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GameplayTemplateEffectsTest {
    private final ObjectMapper json = new ObjectMapper();
    private final JourneyRules rules = JourneyRules.parse("{\"schemaVersion\":1,\"stateEnabled\":true,\"hp\":{\"init\":6,\"max\":12},\"attributes\":[{\"key\":\"counter.focus\",\"label\":\"专注\",\"min\":0,\"max\":10,\"initial\":6}]}");

    private ObjectNode template(String op, int amount) {
        ObjectNode block = json.createObjectNode().put("schemaVersion", 1);
        block.putObject("interactionRules").putArray("resourceSlots").addObject().put("key", "value_resource").put("kind", "VALUE").put("label", "所需数值").put("required", true);
        block.putArray("effects").addObject().put("var", "value_resource").put("op", op).put("value", amount);
        return block;
    }
    private ObjectNode theme(ObjectNode block, String target) {
        ObjectNode theme = json.createObjectNode().put("schemaVersion", 1).put("capacity", 0);
        ObjectNode binding = theme.putObject("templateBindings").putObject("101").put("templateId", 501).put("slotDigest", GameplayTemplateRules.slotDigest(block.path("interactionRules").path("resourceSlots")));
        binding.putObject("values").put("value_resource", target); binding.putObject("items");
        return theme;
    }
    private ObjectNode resolve(ObjectNode block, String target) {
        ObjectNode nodes = json.createObjectNode(); nodes.set("101", block);
        ObjectNode resolved = GameplayTemplateRules.resolveNodeConfigs(theme(block,target),nodes,Collections.singletonMap(101L,501L));
        TemplateResourceSlots.assertEffectTargets(resolved,rules);
        return (ObjectNode) resolved.path("101");
    }
    private TopicRouteEngine.State state() {
        TopicRouteEngine.State state = new TopicRouteEngine.State(); JourneyInitialState.initialize(rules,state);
        state.getVariables().put("sys.hp",6); return state;
    }

    @Test void sameOriginalTemplateBindsToHpOrCustomIntegerAndFreezesRealEffects() {
        ObjectNode block=template("INC",2), original=block.deepCopy();
        for(String target:Arrays.asList("sys.hp","counter.focus")) {
            ObjectNode resolved=resolve(block,target); TopicRouteEngine.State state=state();
            TemplateEffects.apply(resolved.path("effects"),rules,state);
            assertEquals(8,state.getVariables().get(target).intValue());
            assertEquals(target,resolved.path("effects").path(0).path("var").asText());
            assertFalse(resolved.has("interactionRules"));
            ObjectNode journey=json.createObjectNode();NodeJourneySnapshot.copyMechanicSegments(resolved,journey);
            assertEquals(resolved.path("effects"),journey.path("effects"));
        }
        assertEquals(original,block);
    }
    @Test void floorRaisesOnlyToFloorAndIncClampsWithoutLowering() {
        for(String target:Arrays.asList("sys.hp","counter.focus")) {
            TopicRouteEngine.State state=state();JsonNode floor=resolve(template("FLOOR",9),target).path("effects");
            TemplateEffects.apply(floor,rules,state);assertEquals(9,state.getVariables().get(target).intValue());
            state.getVariables().put(target,10);TemplateEffects.apply(floor,rules,state);assertEquals(10,state.getVariables().get(target).intValue());
            TemplateEffects.apply(resolve(template("INC",999),target).path("effects"),rules,state);
            assertEquals("sys.hp".equals(target)?12:10,state.getVariables().get(target).intValue());
            state.getVariables().put(target,20);TemplateEffects.apply(floor,rules,state);assertEquals(20,state.getVariables().get(target).intValue());
        }
    }
    @Test void originalSlotEffectsSaveAndReenterWithoutDummyTargets() throws Exception {
        ObjectNode block=template("FLOOR",99);
        String normalized=new AdvancedGameConfigValidator().validateAndNormalize(block.toString());
        assertEquals("value_resource",json.readTree(normalized).path("effects").path(0).path("var").asText());
        assertEquals(99,json.readTree(normalized).path("effects").path(0).path("value").asInt());
        assertEquals(normalized,new AdvancedGameConfigValidator().validateAndNormalize(normalized));
    }
    @Test void floorBoundsAndUnsupportedTargetsFailBeforeFreeze() {
        for(String target:Arrays.asList("sys.hp","counter.focus")) {
            String message=assertThrows(IllegalArgumentException.class,()->resolve(template("FLOOR",13),target)).getMessage();
            assertTrue(message.contains("101"));assertTrue(message.contains("FLOOR"));
        }
        for(String target:Arrays.asList("sys.luck","sys.actions")) {
            String message=assertThrows(IllegalArgumentException.class,()->resolve(template("FLOOR",2),target)).getMessage();
            assertTrue(message.contains("101") && message.contains("value_resource"));
        }
        for(int value:new int[]{0,-1,1000})assertThrows(IllegalArgumentException.class,()->new AdvancedGameConfigValidator().validateAndNormalize(template("INC",value).toString()));
        assertThrows(IllegalArgumentException.class,()->new AdvancedGameConfigValidator().validateAndNormalize(template("SET",2).toString()));
        assertThrows(IllegalArgumentException.class,()->resolve(template("INC",4),"sys.luck"));
    }
    @Test void allOriginalRegisteredSourcesResolveButUnregisteredPayloadDoesNot() {
        ObjectNode block=template("INC",2);JsonNode effects=block.get("effects");
        for(String field:Arrays.asList("successEffects","failEffects","critEffects","fumbleEffects"))block.with("check").set(field,effects.deepCopy());
        block.with("branch").putArray("steps").addObject().putArray("options").addObject().set("effects",effects.deepCopy());
        block.with("qa").putArray("options").addObject().set("effects",effects.deepCopy());
        block.with("profile").putArray("questions").addObject().putArray("options").addObject().set("effects",effects.deepCopy());
        for(String kit:Arrays.asList("pricePair","hiddenObject","sort","match","classify"))block.with(kit).set("effects",effects.deepCopy());
        block.with("interactionRules").set("effects",effects.deepCopy());
        block.putObject("unregistered").set("effects",effects.deepCopy());
        ObjectNode resolved=resolve(block,"sys.hp");int[] count={0};
        TemplateEffects.visit(resolved,(path,rows)->{for(JsonNode effect:rows){assertEquals("sys.hp",effect.path("var").asText());count[0]++;}});
        assertEquals(14,count[0]);assertEquals("value_resource",resolved.path("unregistered").path("effects").path(0).path("var").asText());
    }
    @Test void legacyLiteralEffectsRemainValidAndLuckStillHonorsRaisedRuntimeMax() {
        TopicRouteEngine.State state=state();state.getVariables().put("counter.legacy",2);
        ArrayNode effects=json.createArrayNode();effects.addObject().put("var","counter.legacy").put("op","INC").put("value",2);
        TemplateEffects.apply(effects,rules,state);assertEquals(4,state.getVariables().get("counter.legacy").intValue());
        state.getVariables().put("sys.luck",rules.getLuck().getMax());state.getVariables().put("sys.luck.max",rules.getLuck().getMax()+2);
        effects.removeAll();effects.addObject().put("var","sys.luck").put("op","INC").put("value",2);
        TemplateEffects.apply(effects,rules,state);assertEquals(rules.getLuck().getMax()+2,state.getVariables().get("sys.luck").intValue());
    }
    @Test void wrongKindAndUnusedOriginalEffectSlotsStayRejected() {
        ObjectNode wrongKind=template("INC",2);((ObjectNode)wrongKind.path("interactionRules").path("resourceSlots").path(0)).put("kind","ITEM");
        assertThrows(IllegalArgumentException.class,()->new AdvancedGameConfigValidator().validateAndNormalize(wrongKind.toString()));
        ObjectNode unused=template("INC",2);unused.remove("effects");
        assertTrue(assertThrows(IllegalArgumentException.class,()->new AdvancedGameConfigValidator().validateAndNormalize(unused.toString())).getMessage().contains("资源声明未被使用"));
    }
}
