package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Arrays;
import java.util.Set;
import java.util.function.BiConsumer;

public final class TemplateEffects {
    private TemplateEffects() { }

    public static void visit(JsonNode block, BiConsumer<String, JsonNode> visitor) {
        visitor.accept("effects", block.path("effects"));
        for (String field : Arrays.asList("successEffects", "failEffects", "critEffects", "fumbleEffects"))
            visitor.accept("check." + field, block.path("check").path(field));
        for (JsonNode step : block.path("branch").path("steps")) for (JsonNode option : step.path("options"))
            visitor.accept("branch.steps[].options[].effects", option.path("effects"));
        for (JsonNode option : block.path("qa").path("options")) visitor.accept("qa.options[].effects", option.path("effects"));
        for (JsonNode question : block.path("profile").path("questions")) for (JsonNode option : question.path("options"))
            visitor.accept("profile.questions[].options[].effects", option.path("effects"));
        for (String kit : Arrays.asList("pricePair", "hiddenObject", "sort", "match", "classify"))
            visitor.accept(kit + ".effects", block.path(kit).path("effects"));
        visitor.accept("interactionRules.effects", block.path("interactionRules").path("effects"));
        GameplayOutcomeRules.visit(block, (path, after) -> visitor.accept(path + ".effects", after.path("effects")));
    }

    public static void apply(JsonNode effects, JourneyRules rules, TopicRouteEngine.State state) {
        if (!effects.isArray()) return;
        ThemeRuntimeEngine numeric = new ThemeRuntimeEngine();
        TopicRouteEngine route = new TopicRouteEngine();
        for (JsonNode effect : effects) {
            String key = effect.path("var").asText(), op = effect.path("op").asText();
            if (("INC".equals(op) || "FLOOR".equals(op)) && (key.startsWith("counter.") || "sys.hp".equals(key) || "sys.luck".equals(key))) {
                if ("sys.luck".equals(key) && !rules.getLuck().isEnabled()) continue;
                int amount = effect.path("value").asInt();
                if ("sys.luck".equals(key)) {
                    int maximum = Math.max(rules.getLuck().getMax(), state.getVariables().getOrDefault("sys.luck.max", rules.getLuck().getMax()).intValue());
                    state.getVariables().put(key, Math.min(maximum, state.getVariables().getOrDefault(key, 0).intValue() + amount));
                    continue;
                }
                if ("FLOOR".equals(op)) amount = Math.max(0, amount - (key.startsWith("counter.")
                        ? JourneyInitialState.numericValue(state, key) : state.getVariables().getOrDefault(key, 0).intValue()));
                if ("FLOOR".equals(op) && amount == 0) continue;
                if (key.startsWith("counter.") && "INC".equals(op) && !registered(rules,key)) {
                    ArrayNode one=JsonNodeFactory.instance.arrayNode();one.add(effect);route.applyEffects(one,state);
                } else numeric.changeStat(state, rules, key, amount);
            } else {
                ArrayNode one = JsonNodeFactory.instance.arrayNode(); one.add(effect);
                route.applyEffects(one, state);
                JourneyInitialState.clamp(rules, state);
            }
        }
    }
    private static boolean registered(JourneyRules rules,String key) {
        if(!rules.isStateEnabled())return false;
        for(JsonNode attribute:rules.getAttributes())if(key.equals(attribute.path("key").asText()))return true;
        return false;
    }

    public static void validateToolEffects(JsonNode effects, Set<String> items) {
        com.fasterxml.jackson.databind.node.ObjectNode wrapper = JsonNodeFactory.instance.objectNode();
        com.fasterxml.jackson.databind.node.ObjectNode event = wrapper.putArray("events").addObject().put("id", "tool").put("content", "tool");
        ArrayNode ordinary = event.putArray("effects");
        if (!effects.isMissingNode() && (!effects.isArray() || effects.size() > 20)) throw new IllegalArgumentException("工具效果须为不超过20项的数组");
        for (JsonNode effect : effects) {
            String key = effect.path("var").asText();
            if (!"sys.hp".equals(key) && !"sys.luck".equals(key)) { ordinary.add(effect); continue; }
            ThemeRuntimeConfig.fields(effect, ThemeRuntimeConfig.set("var", "op", "value"));
            ThemeRuntimeConfig.integer(effect, "value", 1, "sys.luck".equals(key) ? 3 : 999);
            ThemeRuntimeConfig.enumeration(effect, "op", "INC", "FLOOR");
            if ("sys.luck".equals(key) && !"INC".equals(effect.path("op").asText())) throw new IllegalArgumentException("幸运效果仅支持 INC 1至3");
        }
        ThemeRuntimeConfig.events(wrapper, items);
    }

    public static boolean pureRecovery(JsonNode effects) {
        if (!effects.isArray() || effects.isEmpty()) return false;
        for (JsonNode effect : effects) {
            String key = effect.path("var").asText(), op = effect.path("op").asText();
            if (!(key.startsWith("counter.") || "sys.hp".equals(key) || "sys.luck".equals(key))
                    || !("INC".equals(op) || "FLOOR".equals(op)) || effect.path("value").asInt() <= 0) return false;
        }
        return true;
    }

}
