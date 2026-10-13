package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** 模板局部资源参数；只解析已注册字段，不接受路径或脚本。 */
public final class TemplateResourceSlots {
    private TemplateResourceSlots() { }

    static Map<String, String> declarations(JsonNode slots, String node) {
        if (!slots.isArray() || slots.size() > 20) fail(node, "resourceSlots", "资源声明须为不超过20项的数组");
        Map<String, String> kinds = new TreeMap<>();
        for (JsonNode slot : slots) {
            String key = slot.path("key").asText();
            fields(slot, node, key, "key", "kind", "label", "required");
            if (!slot.path("key").isTextual() || !key.matches("[a-z][a-z0-9_.]{0,47}")) fail(node, key, "资源标识无效");
            String kind = slot.path("kind").asText();
            if (!Arrays.asList("VALUE", "ITEM").contains(kind) || kinds.put(key, kind) != null) fail(node, key, "资源类型无效或声明重复");
            if (!slot.path("label").isTextual() || slot.path("label").asText().trim().isEmpty() || slot.path("label").asText().length() > 20) fail(node, key, "名称须为1至20字");
            if (!slot.path("required").isBoolean() || !slot.path("required").asBoolean()) fail(node, key, "required 必须为 true");
        }
        return kinds;
    }

    static Map<String, String> referenceSlots(JsonNode block, Map<String, String> inherited) {
        JsonNode slots = block.path("interactionRules").get("resourceSlots");
        return slots == null ? inherited : declarations(slots, "模板");
    }

    static boolean isSlot(JsonNode row, String field, String kind, Map<String, String> slots) {
        String key = row.path(field).asText();
        if (!slots.containsKey(key)) return false;
        if (!kind.equals(slots.get(key))) fail("模板", key, "资源引用类型不匹配");
        return true;
    }

    /** 按 key 排序，字段顺序 key/kind/label/required，紧凑 JSON 的 UTF-8 SHA-256 小写十六进制。 */
    public static String slotDigest(JsonNode slots) {
        Map<String, String> kinds = declarations(slots, "模板");
        ArrayNode canonical = JsonNodeFactory.instance.arrayNode();
        for (String key : kinds.keySet()) for (JsonNode slot : slots) if (key.equals(slot.path("key").asText()))
            canonical.addObject().put("key", key).put("kind", kinds.get(key)).put("label", slot.path("label").asText()).put("required", true);
        return digest(canonical.toString());
    }
    static String digest(String canonical) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder digest = new StringBuilder();
            for (byte value : hash) digest.append(String.format(Locale.ROOT, "%02x", value & 255));
            return digest.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 不可用", impossible); }
    }

    static ObjectNode validationCopy(JsonNode rules, JsonNode tools, String node) {
        return validationCopy(rules, tools, node, true);
    }
    static ObjectNode validationCopy(JsonNode rules, JsonNode tools, String node, boolean requireAll) {
        Map<String, String> kinds = declarations(rules.path("resourceSlots"), node);
        Map<String, String> targets = new HashMap<>();
        int index = 0;
        String content = rules.toString() + (tools == null ? "" : tools.toString());
        for (String key : kinds.keySet()) {
            String target;
            do { target = ("VALUE".equals(kinds.get(key)) ? "counter.slot_" : "slot_") + index++; } while (content.contains(target));
            targets.put(key, target);
        }
        ObjectNode wrapper = JsonNodeFactory.instance.objectNode();
        ObjectNode ruleCopy = ((ObjectNode) rules).deepCopy(); ruleCopy.remove("resourceSlots"); wrapper.set("rules", ruleCopy);
        ObjectNode toolCopy = tools != null && tools.isObject() ? ((ObjectNode) tools).deepCopy() : null;
        Set<String> used = new HashSet<>();
        replace(ruleCopy, kinds, targets, used, node, false);
        if (toolCopy != null) { replace(toolCopy, kinds, targets, used, node, false); wrapper.set("tools", toolCopy); }
        if (requireAll) for (String key : kinds.keySet()) if (!used.contains(key)) fail(node, key, "资源声明未被使用");
        return wrapper;
    }

    static ObjectNode validationBlock(JsonNode block, String node) {
        JsonNode rules = block.path("interactionRules");
        Map<String, String> kinds = declarations(rules.path("resourceSlots"), node);
        Map<String, String> targets = new HashMap<>();
        int index = 0;
        for (String key : kinds.keySet()) {
            String target;
            do { target = ("VALUE".equals(kinds.get(key)) ? "counter.slot_" : "slot_") + index++; } while (block.toString().contains(target));
            targets.put(key, target);
        }
        ObjectNode result = ((ObjectNode) block).deepCopy();
        ((ObjectNode) result.get("interactionRules")).remove("resourceSlots");
        Set<String> used = new HashSet<>();
        replace((ObjectNode) result.get("interactionRules"), kinds, targets, used, node, false);
        if (result.path("interactionTools").isObject()) replace((ObjectNode) result.get("interactionTools"), kinds, targets, used, node, false);
        replaceOutcomeCosts(result, kinds, targets, used, node);
        TemplateEffects.visit(result, (path, effects) -> {
            for (JsonNode effect : effects) {
                String slot = effect.path("var").asText();
                if (!kinds.containsKey(slot)) continue;
                if (!"VALUE".equals(kinds.get(slot))) fail(node, slot, path + " 须使用 VALUE 资源");
                validateResourceEffect(effect, node, slot, "counter.slot");
                used.add(slot); ((ObjectNode) effect).put("var", targets.get(slot));
                if (!"interactionRules.effects".equals(path)) ((ObjectNode) effect).put("value", Math.min(99, effect.path("value").asInt()));
            }
        });
        for (String key : kinds.keySet()) if (!used.contains(key)) fail(node, key, "资源声明未被使用");
        if (result.path("interactionRules").isEmpty()) result.remove("interactionRules");
        return result;
    }

    static ObjectNode restoreBlock(ObjectNode authored, ObjectNode normalized) {
        List<JsonNode> originals = new ArrayList<>(); TemplateEffects.visit(authored, (path, effects) -> originals.add(effects));
        int[] index = {0};
        TemplateEffects.visit(normalized, (path, effects) -> {
            JsonNode original = originals.get(index[0]++);
            if (effects.isArray() && original.isArray()) { ((ArrayNode) effects).removeAll(); ((ArrayNode) effects).addAll((ArrayNode) original.deepCopy()); }
        });
        ObjectNode rules = ((ObjectNode) authored.get("interactionRules")).deepCopy();
        if (rules.size() > 1) for (String field : Arrays.asList("limit", "scope", "targetScope")) if (!rules.has(field)) rules.set(field, normalized.path("interactionRules").path(field));
        normalized.set("interactionRules", rules);
        if (authored.has("interactionTools")) normalized.set("interactionTools", authored.get("interactionTools").deepCopy());
        GameplayOutcomeRules.restore(authored, normalized);
        return normalized;
    }

    private static void validateResourceEffect(JsonNode effect, String node, String slot, String target) {
        String op = effect.path("op").asText(); JsonNode value = effect.path("value");
        if (!("INC".equals(op) || "FLOOR".equals(op)) || value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.asInt() < 1 || value.asInt() > 999) fail(node, slot, "数值资源效果仅支持 INC/FLOOR 与1至999整数");
        if ("sys.luck".equals(target) && (!"INC".equals(op) || value.asInt() > 3)) fail(node, slot, "幸运仅支持 INC 1至3");
        if (target.startsWith("sys.") && !Arrays.asList("sys.hp", "sys.luck").contains(target)) fail(node, slot, "绑定目标不支持该效果操作");
    }

    public static void assertEffectTargets(JsonNode configs, JourneyRules rules) {
        assertEffectTargets(configs,rules,JsonNodeFactory.instance.objectNode());
    }
    public static void assertEffectTargets(JsonNode configs, JourneyRules rules, JsonNode theme) {
        configs.fields().forEachRemaining(node -> TemplateEffects.visit(node.getValue(), (path, effects) -> {
            for (JsonNode effect : effects) {
                String target = effect.path("var").asText();
                if (!"FLOOR".equals(effect.path("op").asText())) continue;
                int maximum = "sys.hp".equals(target) ? rules.getHp().getMax() : JourneyInitialState.numericAttribute(rules, target).path("max").asInt();
                String slot=target;
                Iterator<Map.Entry<String,JsonNode>> mappings=theme.path("templateBindings").path(node.getKey()).path("values").fields();
                while(mappings.hasNext()) {Map.Entry<String,JsonNode> mapping=mappings.next();if(target.equals(mapping.getValue().asText()))slot=mapping.getKey();}
                if (effect.path("value").asInt() > maximum) fail(node.getKey(), slot, path + " FLOOR 超过值域上限 " + maximum);
            }
        }));
    }

    static void validateBindings(JsonNode theme) {
        validateBindings(theme, false);
    }

    static void validateBindings(JsonNode theme, boolean allowClientKeys) {
        if (!theme.has("templateBindings")) return;
        JsonNode bindings = theme.path("templateBindings");
        if (!bindings.isObject() || bindings.size() > 200) fail("主题", "templateBindings", "绑定须为不超过200项的对象");
        bindings.fields().forEachRemaining(entry -> {
            String node = entry.getKey();
            if (allowClientKeys && node.startsWith("client:")) {
                String key = node.substring(7);
                if (key.trim().isEmpty() || !key.equals(key.trim()) || key.length() > 64) fail(node, "binding", "clientNodeKey 须为1至64位稳定键");
            } else nodeId(node);
            JsonNode binding = entry.getValue();
            fields(binding, node, "binding", "templateId", "values", "items", "slotDigest", "planEndings", "planDigest");
            if (binding.has("planDigest") && (!binding.path("planDigest").isTextual() || !binding.path("planDigest").asText().matches("[0-9a-f]{64}"))) fail(node, "planDigest", "方案摘要无效");
            if (binding.has("planEndings")) {
                if (!binding.path("planEndings").isObject() || binding.path("planEndings").isEmpty() || binding.path("planEndings").size() > 30) fail(node, "planEndings", "结局链接须为1至30项对象");
                binding.path("planEndings").fields().forEachRemaining(plan -> {
                    if (!plan.getKey().matches("(?:[a-z][a-z0-9_]{0,31}|[1-9]|[12][0-9]|30)") || !plan.getValue().isTextual()) fail(node, plan.getKey(), "方案或结局章标识无效");
                    nodeId(plan.getValue().asText());
                });
            }
            if (!binding.path("templateId").isIntegralNumber() || !binding.path("templateId").canConvertToLong() || binding.path("templateId").asLong() < 1) fail(node, "templateId", "模板身份无效");
            if (!binding.path("slotDigest").isTextual() || !binding.path("slotDigest").asText().matches("[0-9a-f]{64}")) fail(node, "slotDigest", "声明摘要无效");
            Set<String> slots = new HashSet<>(), targets = new HashSet<>();
            for (String kind : Arrays.asList("values", "items")) {
                JsonNode mappings = binding.path(kind);
                if (!mappings.isObject()) fail(node, kind, "绑定须为对象");
                mappings.fields().forEachRemaining(mapping -> {
                    String slot = mapping.getKey(), target = mapping.getValue().asText();
                    if (!slot.matches("[a-z][a-z0-9_.]{0,47}") || !slots.add(slot) || slots.size() > 20) fail(node, slot, "资源槽无效或类型重复");
                    if (!mapping.getValue().isTextual()) fail(node, slot, "绑定目标须为文字");
                    if ("values".equals(kind)) {
                        if (!target.matches("counter\\.[a-z][a-z0-9_]{0,47}") && !Arrays.asList("sys.hp", "sys.luck", "sys.actions").contains(target)) fail(node, slot, "数值绑定类型无效");
                    } else {
                        boolean found = false;
                        for (JsonNode item : theme.path("items")) if (target.equals(item.path("id").asText())) found = true;
                        if (!found) fail(node, slot, "绑定物资不存在");
                    }
                    if (!targets.add(kind + ":" + target)) fail(node, slot, "不同资源槽不能绑定同一实际资源");
                });
            }
        });
    }

    static void assertReferences(JsonNode theme, Set<Long> nodes, Set<String> stateKeys) {
        validateBindings(theme);
        theme.path("templateBindings").fields().forEachRemaining(entry -> {
            String node = entry.getKey();
            if (!nodes.contains(nodeId(node))) fail(node, "binding", "绑定节点不属于本主题");
            entry.getValue().path("values").fields().forEachRemaining(mapping -> {
                String target = mapping.getValue().asText();
                if (target.startsWith("counter.") && !stateKeys.contains(target)) fail(node, mapping.getKey(), "绑定数值未注册");
            });
        });
    }

    static void assertEnabled(JsonNode theme, JourneyRules rules) {
        theme.path("templateBindings").fields().forEachRemaining(entry -> entry.getValue().path("values").fields().forEachRemaining(mapping -> {
            String target = mapping.getValue().asText(), node = entry.getKey(), slot = mapping.getKey();
            if (target.startsWith("counter.")) {
                try { JourneyInitialState.numericAttribute(rules, target); }
                catch (IllegalArgumentException invalid) { fail(node, slot, "绑定数值未启用或未注册"); }
            } else if ("sys.actions".equals(target)) {
                if (!theme.has("actionLimit")) fail(node, slot, "行动值域未启用");
            } else if (!rules.hasRuntimeState() || ("sys.hp".equals(target) ? !rules.getHp().isEnabled() : !rules.getLuck().isEnabled())) fail(node, slot, "绑定数值未启用");
        }));
    }

    static ObjectNode resolve(JsonNode block, JsonNode theme, Long templateId, String node) {
        JsonNode rules = block.path("interactionRules");
        if (!rules.has("resourceSlots")) return ((ObjectNode) block).deepCopy();
        Map<String, String> kinds = declarations(rules.path("resourceSlots"), node);
        validationBlock(block, node);
        String first = kinds.isEmpty() ? "resourceSlots" : kinds.keySet().iterator().next();
        if (templateId == null) fail(node, first, "缺少可信模板身份");
        JsonNode binding = theme == null ? JsonNodeFactory.instance.missingNode() : theme.path("templateBindings").path(node);
        if (!binding.isObject()) fail(node, first, "资源未绑定");
        if (binding.path("templateId").asLong() != templateId) fail(node, first, "绑定模板身份不匹配");
        if (!slotDigest(rules.path("resourceSlots")).equals(binding.path("slotDigest").asText())) fail(node, first, "声明已变化，需要重新确认绑定");
        Map<String, String> targets = new HashMap<>();
        for (String key : kinds.keySet()) {
            String field = "VALUE".equals(kinds.get(key)) ? "values" : "items";
            if (binding.path("VALUE".equals(kinds.get(key)) ? "items" : "values").has(key)) fail(node, key, "绑定资源类型不匹配");
            if (!binding.path(field).has(key)) fail(node, key, "资源未绑定");
            targets.put(key, binding.path(field).path(key).asText());
            if ("ITEM".equals(kinds.get(key))) for (JsonNode item : theme.path("items")) if (targets.get(key).equals(item.path("id").asText())) {
                assertItemOperations(rules, key, item, node);
                assertItemOperations(block.path("interactionTools"), key, item, node);
            }
        }
        for (String field : Arrays.asList("values", "items")) binding.path(field).fieldNames().forEachRemaining(key -> {
            if (!kinds.containsKey(key)) fail(node, key, "绑定了未声明的资源槽");
        });
        ObjectNode result = ((ObjectNode) block).deepCopy();
        ObjectNode resolved = (ObjectNode) result.get("interactionRules"); resolved.remove("resourceSlots");
        replace(resolved, kinds, targets, new HashSet<>(), node, true);
        if (result.path("interactionTools").isObject()) replace((ObjectNode) result.get("interactionTools"), kinds, targets, new HashSet<>(), node, true);
        replaceOutcomeCosts(result, kinds, targets, new HashSet<>(), node);
        TemplateEffects.visit(result, (path, effects) -> {
            for (JsonNode effect : effects) {
                String slot = effect.path("var").asText();
                if (!kinds.containsKey(slot)) continue;
                if (!"VALUE".equals(kinds.get(slot))) fail(node, slot, path + " 须使用 VALUE 资源");
                validateResourceEffect(effect, node, slot, targets.get(slot));
                ((ObjectNode) effect).put("var", targets.get(slot));
            }
        });
        if (resolved.size() == 0) result.remove("interactionRules");
        return result;
    }

    private static void replaceOutcomeCosts(ObjectNode block, Map<String, String> kinds, Map<String, String> targets, Set<String> used, String node) {
        GameplayOutcomeRules.visit(block, (path, after) -> {
            for (JsonNode cost : after.path("costs")) {
                String slot = cost.path("statKey").asText();
                if (!kinds.containsKey(slot)) continue;
                if (!"VALUE".equals(kinds.get(slot))) fail(node, slot, path + " costs 须使用 VALUE 资源");
                used.add(slot); ((ObjectNode) cost).put("statKey", targets.get(slot));
            }
        });
    }

    private static void assertItemOperations(JsonNode owner, String slot, JsonNode item, String node) {
        for (String field : Arrays.asList("resourceCosts", "materialCost")) for (JsonNode cost : owner.path(field))
            if (slot.equals(cost.path("itemId").asText()) && (!"ITEM".equals(item.path("kind").asText()) || "materialCost".equals(field) && item.path("wear").asBoolean()))
                fail(node, slot, "绑定物资不支持该费用操作");
        for (String field : Arrays.asList("costOptions", "grantOptions", "plans")) for (JsonNode child : owner.path(field)) assertItemOperations(child, slot, item, node);
    }

    private static void replace(ObjectNode owner, Map<String, String> kinds, Map<String, String> targets, Set<String> used, String node, boolean resolve) {
        for (String field : Arrays.asList("statCosts", "statChanges", "resourceCosts", "grants", "materialCost", "when", "effects", "worldEffects")) {
            for (JsonNode row : owner.path(field)) {
                if (!row.isObject()) continue;
                for (String ref : Arrays.asList("statKey", "itemId", "var")) {
                    if (!("statKey".equals(ref) ? Arrays.asList("statCosts", "statChanges").contains(field) : "itemId".equals(ref) ? Arrays.asList("resourceCosts", "grants", "materialCost", "when").contains(field) : Arrays.asList("when", "effects", "worldEffects").contains(field))) continue;
                    String slot = row.path(ref).asText(); if (!kinds.containsKey(slot)) continue;
                    String kind = "itemId".equals(ref) ? "ITEM" : "VALUE";
                    if (!kind.equals(kinds.get(slot))) fail(node, slot, "资源引用类型不匹配");
                    if ("worldEffects".equals(field)) fail(node, slot, "公共事实仅支持 clue，不能绑定数值资源槽");
                    used.add(slot); String target = targets.get(slot);
                    if (resolve && "effects".equals(field) && !"SET".equals(row.path("op").asText())) validateResourceEffect(row, node, slot, target);
                    if (resolve && target.startsWith("sys.") && Arrays.asList("statChanges", "worldEffects").contains(field)) {
                        if (!"statChanges".equals(field) || !"sys.hp".equals(target)) fail(node, slot, "绑定目标不支持该效果操作");
                        if (row != owner.path(field).get(0) || owner.has("hpDelta")) fail(node, slot, "该效果顺序无法通过 hpDelta 保留");
                    }
                    if (resolve && "effects".equals(field) && target.startsWith("sys.") && "SET".equals(row.path("op").asText())) fail(node, slot, "绑定目标不支持 SET 效果操作");
                    ((ObjectNode) row).put(ref, target);
                }
            }
        }
        if (resolve) {
            if ("sys.hp".equals(owner.path("statChanges").path(0).path("statKey").asText())) {
                owner.set("hpDelta", owner.path("statChanges").get(0).get("delta"));
                ((ArrayNode) owner.get("statChanges")).remove(0);
                if (owner.path("statChanges").size() == 0) owner.remove("statChanges");
            }
            Map<String, Integer> costs = new LinkedHashMap<>();
            for (JsonNode cost : owner.path("statCosts")) {
                String target = cost.path("statKey").asText();
                String builtin = "sys.hp".equals(target) ? "hpCost" : "sys.luck".equals(target) ? "luckCost" : "sys.actions".equals(target) ? "actionCost" : null;
                if (builtin != null) owner.put(builtin, sum(owner.path(builtin).asInt(), cost.path("amount").asInt(), node, slotFor(targets, target)));
                else costs.put(target, sum(costs.getOrDefault(target, 0), cost.path("amount").asInt(), node, slotFor(targets, target)));
            }
            if (owner.has("statCosts")) {
                owner.remove("statCosts");
                if (!costs.isEmpty()) { ArrayNode rows = owner.putArray("statCosts"); costs.forEach((key, amount) -> rows.addObject().put("statKey", key).put("amount", amount)); }
            }
            for (String field : Arrays.asList("resourceCosts", "materialCost")) if (owner.path(field).isArray()) {
                Map<String, Integer> amounts = new LinkedHashMap<>();
                for (JsonNode cost : owner.path(field)) { String target = cost.path("itemId").asText(); amounts.put(target, sum(amounts.getOrDefault(target, 0), cost.path("quantity").asInt(), node, slotFor(targets, target))); }
                ArrayNode rows = owner.putArray(field); amounts.forEach((key, amount) -> rows.addObject().put("itemId", key).put("quantity", amount));
            }
        }
        for (String field : Arrays.asList("costOptions", "grantOptions", "plans")) for (JsonNode child : owner.path(field)) if (child.isObject()) replace((ObjectNode) child, kinds, targets, used, node, resolve);
    }

    private static int sum(int left, int right, String node, String slot) {
        if ((long) left + right > 999) fail(node, slot, "合计费用超过999");
        return left + right;
    }
    private static String slotFor(Map<String, String> targets, String target) {
        for (Map.Entry<String, String> entry : targets.entrySet()) if (target.equals(entry.getValue())) return entry.getKey();
        return target;
    }
    static long nodeId(String node) {
        if (!node.matches("[1-9][0-9]{0,18}")) fail(node, "binding", "节点标识无效");
        try { return Long.parseLong(node); } catch (NumberFormatException invalid) { throw new IllegalArgumentException("节点 " + node + " 资源槽 binding：节点标识越界", invalid); }
    }
    private static void fields(JsonNode object, String node, String slot, String... names) {
        if (!object.isObject()) fail(node, slot, "配置须为对象");
        Set<String> allowed = new HashSet<>(Arrays.asList(names));
        object.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) fail(node, slot, "不支持字段 " + field); });
    }
    private static void fail(String node, String slot, String reason) { throw new IllegalArgumentException("节点 " + node + " 资源槽 " + slot + "：" + reason); }
}
