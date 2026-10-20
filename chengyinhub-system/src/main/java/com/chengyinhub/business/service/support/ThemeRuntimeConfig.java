package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 通用主题配置。目录、触发和场景只含受控数据，不接受脚本或系统属性写入。 */
public final class ThemeRuntimeConfig {
    private static final ObjectMapper JSON = new ObjectMapper();
<<<<<<< port
    private static final Set<String> ROOT = set("schemaVersion", "capacity", "hpName", "luckName", "actionName", "items", "grants", "events", "scenes", "rotation", "currentScene", "replayEnabled", "endings", "card", "rotationAnchor", "actionLimit", "actionSources", "rescueLimit", "allowDiscard", "chapters", "gameplayRuntime", "templateBindings");
=======
    // 与 AdvancedGameConfigValidator / JourneyStoryValidator 的只读条件语法一致。
    private static final Pattern SYS_ASKED_REFERENCE =
            Pattern.compile("^sys\\.asked\\.([1-9][0-9]*)\\.([A-Za-z0-9_-]{1,32})$");
    private static final Set<String> ROOT = set("schemaVersion", "capacity", "hpName", "luckName", "items", "grants", "events", "scenes", "rotation", "currentScene", "replayEnabled", "endings", "card", "rotationAnchor", "actionLimit", "actionSources", "rescueLimit", "allowDiscard", "chapters");
>>>>>>> questify
    public static ObjectNode parse(String raw) { return parse(raw,false); }
    public static ObjectNode parseDraft(String raw) { return parse(raw,true); }
    public static ObjectNode parseForNodeSave(String raw) { return parse(raw, true, true); }
    private static ObjectNode parse(String raw,boolean draft) {
        return parse(raw, draft, false);
    }
    private static ObjectNode parse(String raw, boolean draft, boolean allowClientKeys) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            JsonNode node = JSON.readTree(raw);
            if (!node.isObject() || raw.length() > 200000) fail("主题配置格式或长度不正确");
            ObjectNode config = (ObjectNode) node;
            fields(config, ROOT); integer(config, "schemaVersion", 1, 1);
            validateReferenceShapes(config);
            if(config.path("items").size()>0)integer(config,"capacity",1,999);
            else if(config.has("capacity"))integer(config,"capacity",0,999);else config.put("capacity",0);
            if(config.has("actionLimit"))integer(config,"actionLimit",1,99999);
            if(config.has("rescueLimit"))integer(config,"rescueLimit",0,10);
            for(JsonNode source:rows(config,"actionSources",3))if(!source.isTextual() || !set("NODE","CHOICE","DRAW").contains(source.asText()))fail("行动计数来源不支持");
            text(config, "hpName", 20, false); text(config, "luckName", 20, false); text(config, "actionName", 20, false);
            bool(config, "replayEnabled"); bool(config,"allowDiscard");
            Set<String> items = new HashSet<>();
            for (JsonNode item : rows(config, "items", 200)) {
                fields(item, set("id", "kind", "name", "image", "stat", "statKey", "slotCost", "delta", "wear", "mode"));
                id(item, items); if(item.path("id").asText().startsWith("legacy_"))fail("legacy_ 为旧勋章兼容保留前缀"); text(item, "name", 40, !draft); media(item, "image", !draft);
                enumeration(item, "kind", "MEDAL", "ITEM");
                if (item.has("slotCost")) integer(item, "slotCost", 1, 999);
                if (item.has("stat") && item.has("statKey")) fail("物品stat与statKey不能同时配置");
                if ("MEDAL".equals(item.path("kind").asText())) {
                    if (item.has("stat") || item.has("statKey") || item.has("slotCost") || item.has("delta") || item.has("wear") || item.has("mode")) fail("勋章不能配置物品效果");
                } else if (item.has("stat") || item.has("statKey")) {
                    if (item.has("statKey")) counterKey(item, "statKey");
                    else enumeration(item, "stat", "HP", "LUCK");
                    integer(item, "delta", -999, 999); if (item.path("delta").asInt() == 0) fail("物品效果不能为零");
                    bool(item, "wear");
                    if (item.path("wear").asBoolean()) enumeration(item, "mode", "ONCE", "HELD", "REDUCE");
                    else if (item.has("mode")) fail("普通物品不能设置穿戴方式");
                    if("REDUCE".equals(item.path("mode").asText()) && ((!item.has("statKey") && !"HP".equals(item.path("stat").asText())) || item.path("delta").asInt()<1))fail("减耗只支持正数生命值");
                } else if(item.has("delta") || item.has("wear") || item.has("mode")) { fail("展示物品不能设置效果");
                }
            }
            TemplateResourceSlots.validateBindings(config, allowClientKeys);
            GameplayRuntimeConfig.validate(config, items, draft);
            chapters(config, items, allowClientKeys); grants(config, items); events(config, items); endings(config, items); card(config.path("card"));
            Set<String> scenes = new HashSet<>();
            for (JsonNode scene : rows(config, "scenes", 50)) {
                fields(scene, set("id", "name", "grants", "events", "endings", "journeyStory", "nodeConfigs"));
                id(scene, scenes); text(scene, "name", 40, true);
                grants(scene, items); events(scene, items); endings(scene, items);
                if(scene.has("nodeConfigs")) {
                    if(!scene.get("nodeConfigs").isObject() || scene.get("nodeConfigs").size()>200)fail("场景节点玩法格式不正确");
<<<<<<< port
                    java.util.Iterator<String> nodeKeys=scene.get("nodeConfigs").fieldNames();
                    while(nodeKeys.hasNext()) {
                        String key=nodeKeys.next();
                        boolean client = allowClientKeys && key.startsWith("client:") && key.length()>7 && key.length()<=71 && key.substring(7).equals(key.substring(7).trim());
                        if(!key.matches("[1-9][0-9]{0,17}") && !client)fail("场景节点 ID 不正确");
                        new AdvancedGameConfigValidator().validateAndNormalize(scene.get("nodeConfigs").get(key).toString());
=======
                    ObjectNode nodeConfigs = (ObjectNode) scene.get("nodeConfigs");
                    java.util.Iterator<String> nodeKeys=nodeConfigs.fieldNames();
                    while(nodeKeys.hasNext()) {
                        String key=nodeKeys.next();sceneNodeId(key);
                        String normalized = new AdvancedGameConfigValidator().validateAndNormalize(nodeConfigs.get(key).toString());
                        nodeConfigs.set(key, JSON.readTree(normalized));
>>>>>>> questify
                    }
                }
                if (scene.has("journeyStory")) new JourneyStoryValidator().validate(scene.get("journeyStory").toString(), JourneyStoryValidator.Context.none());
            }
            if (config.has("rotation")) enumeration(config, "rotation", "FIXED", "ROUND_ROBIN", "WEEKLY");
            if ("WEEKLY".equals(config.path("rotation").asText()))integer(config,"rotationAnchor",0,Long.MAX_VALUE);
            if (config.has("currentScene") && !scenes.contains(config.path("currentScene").asText())) fail("当前场景不在目录中");
            if (!scenes.isEmpty() && !config.has("currentScene")) fail("请选择当前场景");
            endingGrantReferences(config,config);
            for (JsonNode scene : config.path("scenes")) endingGrantReferences(scene,config);
            return config;
        } catch (IllegalArgumentException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalArgumentException("主题配置不可读取", ex); }
    }
    public static String normalize(String raw) { ObjectNode n = parseDraft(raw); return n == null ? (raw==null?null:"") : n.toString(); }
    public static String withoutClientBindings(String raw) {
        ObjectNode config = parseForNodeSave(raw);
        if (config == null) return raw == null ? null : "";
        if (config.has("templateBindings")) {
            Iterator<String> keys = config.path("templateBindings").fieldNames();
            while (keys.hasNext()) if (keys.next().startsWith("client:")) keys.remove();
        }
        for (JsonNode scene : config.path("scenes")) {
            Iterator<String> keys = scene.path("nodeConfigs").fieldNames();
            while (keys.hasNext()) if (keys.next().startsWith("client:")) keys.remove();
        }
        // 章节规则里引用同请求新建站点的 client:<key>:落库前的中间态先去掉(不足两站的互斥组整组去掉),
        // 站点建好后 remapForNodeSave 按原请求换成真实编号整段写回。
        for (JsonNode chapter : config.path("chapters")) {
            if (chapter.has("exclusiveGroups")) {
                com.fasterxml.jackson.databind.node.ArrayNode kept = JSON.createArrayNode();
                for (JsonNode group : chapter.path("exclusiveGroups")) {
                    com.fasterxml.jackson.databind.node.ArrayNode ids = JSON.createArrayNode();
                    for (JsonNode id : group) if (!clientNodeRef(id)) ids.add(id);
                    if (ids.size() >= 2) kept.add(ids);
                }
                if (kept.size() > 0) ((ObjectNode) chapter).set("exclusiveGroups", kept); else ((ObjectNode) chapter).remove("exclusiveGroups");
            }
            if (chapter.has("extraNodes")) {
                com.fasterxml.jackson.databind.node.ArrayNode kept = JSON.createArrayNode();
                for (JsonNode row : chapter.path("extraNodes")) if (!clientNodeRef(row.path("nodeId"))) kept.add(row);
                if (kept.size() > 0) ((ObjectNode) chapter).set("extraNodes", kept); else ((ObjectNode) chapter).remove("extraNodes");
            }
        }
        return config.toString();
    }
    /** 按原请求把章节互斥组/额外节点整段换成真实编号:client:<key> 走本次新建,数字编号走旧→新。 */
    private static void remapChapterRefs(ObjectNode source, ObjectNode result, Map<Long, Long> nodes, Map<String, Long> createdClients) {
        for (int i = 0; i < source.path("chapters").size() && i < result.path("chapters").size(); i++) {
            JsonNode from = source.path("chapters").get(i);
            ObjectNode to = (ObjectNode) result.path("chapters").get(i);
            java.util.function.Function<JsonNode, Long> target = ref -> {
                Long mapped = clientNodeRef(ref) ? createdClients.get(ref.asText().substring(7)) : nodes.get(ref.asLong());
                if (mapped == null || mapped <= 0L) fail("章节规则节点没有对应目标：" + ref.asText());
                return mapped;
            };
            if (from.has("exclusiveGroups")) {
                com.fasterxml.jackson.databind.node.ArrayNode groups = JSON.createArrayNode();
                for (JsonNode group : from.path("exclusiveGroups")) {
                    com.fasterxml.jackson.databind.node.ArrayNode ids = groups.addArray();
                    for (JsonNode id : group) ids.add(target.apply(id));
                }
                to.set("exclusiveGroups", groups);
            }
            if (from.has("extraNodes")) {
                com.fasterxml.jackson.databind.node.ArrayNode rows = JSON.createArrayNode();
                for (JsonNode row : from.path("extraNodes")) rows.addObject().put("nodeId", target.apply(row.path("nodeId"))).put("slot", row.path("slot").asText());
                to.set("extraNodes", rows);
            }
        }
    }
    public static String remapForNodeSave(String raw, Map<Long, Long> nodes, Map<Long, Long> chapters, Map<String, Long> createdClients) {
        ObjectNode source = parseForNodeSave(raw);
        if (source == null) return raw == null ? null : "";
        ObjectNode result = parseDraft(remapAll(withoutClientBindings(raw), nodes, chapters, null));
        remapChapterRefs(source, result, nodes, createdClients);
        source.path("templateBindings").fields().forEachRemaining(entry -> {
            if (!entry.getKey().startsWith("client:")) return;
            Long target = createdClients.get(entry.getKey().substring(7));
            if (target == null || target <= 0L) fail("资源绑定节点未在本次请求新建：" + entry.getKey());
            ObjectNode bindings = (ObjectNode) result.get("templateBindings");
            if (bindings.has(String.valueOf(target))) fail("资源绑定节点重复：" + entry.getKey());
            JsonNode binding = entry.getValue().deepCopy();
            remapBindingEndings(binding, chapters);
            bindings.set(String.valueOf(target), binding);
        });
        for (int index = 0; index < source.path("scenes").size(); index++) {
            ObjectNode scene = (ObjectNode)result.path("scenes").get(index);
            source.path("scenes").get(index).path("nodeConfigs").fields().forEachRemaining(entry -> {
                if (!entry.getKey().startsWith("client:")) return;
                Long target = createdClients.get(entry.getKey().substring(7));
                if (target == null || target <= 0L) fail("场景节点未在本次请求新建：" + entry.getKey());
                ObjectNode configs = scene.with("nodeConfigs");
                if (configs.has(String.valueOf(target))) fail("场景节点重复：" + entry.getKey());
                configs.set(String.valueOf(target), entry.getValue());
            });
        }
        return parseDraft(result.toString()).toString();
    }
    public static ObjectNode freeze(ObjectNode config, int runIndex, long epochMillis) {
        ObjectNode frozen = config.deepCopy();
        List<JsonNode> scenes = rows(config, "scenes", 50);
        if (scenes.isEmpty()) return frozen;
        int base = 0;
        for (int i = 0; i < scenes.size(); i++) if (scenes.get(i).path("id").asText().equals(config.path("currentScene").asText())) base = i;
        int offset = "ROUND_ROBIN".equals(config.path("rotation").asText()) ? runIndex : 0;
        if ("WEEKLY".equals(config.path("rotation").asText())) {
            // 明确锚定到 UTC 周一，场景配置不随手机时区变化。
            offset = (int) Math.max(0,Math.floorDiv(epochMillis / 86400000L + 3, 7)-Math.floorDiv(config.path("rotationAnchor").asLong()/86400000L+3,7));
        }
        JsonNode scene = scenes.get(Math.floorMod(base + offset, scenes.size()));
        for (String field : Arrays.asList("grants", "events", "endings", "journeyStory", "nodeConfigs")) if (scene.has(field)) frozen.set(field, scene.get(field).deepCopy());
        frozen.put("sceneId", scene.path("id").asText()); frozen.put("sceneName", scene.path("name").asText());
        frozen.remove("scenes");
        return frozen;
    }
    private static void chapters(JsonNode root, Set<String> items, boolean allowClientKeys) {
        Set<Integer> indexes=new HashSet<>();
        for(JsonNode ch:rows(root,"chapters",200)) {
            fields(ch,set("index","hpCost","luckCost","statCosts","grants","eggs","completion","exclusiveGroups","extraNodes")); integer(ch,"index",0,199);
            if(!indexes.add(ch.path("index").asInt()))fail("章节配置重复");
            integer(ch,"hpCost",0,999);integer(ch,"luckCost",0,999);
            statCosts(ch);
            chapterRequirements(ch, allowClientKeys);
            Set<String> granted=new HashSet<>();
            for(JsonNode g:rows(ch,"grants",200)) {fields(g,set("itemId","quantity","timing"));ref(g,"itemId",items);integer(g,"quantity",1,99);enumeration(g,"timing","START","END");if(!granted.add(g.path("itemId").asText()))fail("章节物资重复");}
            Set<String> eggs=new HashSet<>();
            for(JsonNode e:rows(ch,"eggs",100)) {
                fields(e,set("id","name","trigger","threshold","content","rewards"));id(e,eggs);text(e,"name",40,true);text(e,"content",500,true);enumeration(e,"trigger","TIME","STEPS");integer(e,"threshold",1,99999);
                for(JsonNode r:rows(e,"rewards",20)) {fields(r,set("itemId","quantity"));ref(r,"itemId",items);integer(r,"quantity",1,99);}
            }
        }
    }
    /** 同请求新建站点的引用(client:<clientNodeKey>),只在存站点那一步允许,落库前由 remapForNodeSave 换成真实编号。 */
    private static boolean clientNodeRef(JsonNode value) {
        if (value == null || !value.isTextual()) return false;
        String text = value.asText();
        return text.startsWith("client:") && text.length() > 7 && text.length() <= 71 && text.substring(7).equals(text.substring(7).trim());
    }
    private static String nodeRefKey(JsonNode value, boolean allowClientKeys, String label, String what) {
        if (allowClientKeys && clientNodeRef(value)) return value.asText();
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 1) fail(label + " " + what + " ID 须为正整数");
        return String.valueOf(value.asLong());
    }
    private static void chapterRequirements(JsonNode chapter, boolean allowClientKeys) {
        String label = "章节 index=" + chapter.path("index").asInt();
        if (chapter.has("completion")) {
            JsonNode completion = chapter.get("completion");
            fields(completion, set("type", "quantity", "overflowSlot"));
            enumeration(completion, "type", "ALL", "ANY_N");
            if ("ANY_N".equals(completion.path("type").asText())) integer(completion, "quantity", 1, Integer.MAX_VALUE);
            else if (completion.has("quantity")) fail(label + " ALL 不能配置 quantity");
            if (completion.has("overflowSlot")) {
                if (!"ANY_N".equals(completion.path("type").asText())) fail(label + " overflowSlot 只能用于 ANY_N");
                if (!completion.path("overflowSlot").isTextual() || !completion.path("overflowSlot").asText().matches("[a-z][a-z0-9_]{0,31}")) fail(label + " overflowSlot 额外任务槽键不合法");
            }
        }
        Set<String> grouped = new HashSet<>();
        if (chapter.has("exclusiveGroups")) {
            JsonNode groups = chapter.get("exclusiveGroups");
            if (!groups.isArray() || groups.size() > 20) fail(label + " exclusiveGroups 须为不超过20组的数组");
            for (JsonNode group : groups) {
                if (!group.isArray() || group.size() < 2) fail(label + " 互斥组至少包含2个节点");
                for (JsonNode nodeId : group) {
                    String key = nodeRefKey(nodeId, allowClientKeys, label, "互斥组节点");
                    if (!grouped.add(key)) fail(label + " 互斥组节点重复：" + key);
                }
            }
        }
        Set<String> extra = new HashSet<>();
        if (chapter.has("extraNodes")) {
            JsonNode nodes = chapter.get("extraNodes");
            if (!nodes.isArray()) fail(label + " extraNodes 须为数组");
            for (JsonNode node : nodes) {
                fields(node, set("nodeId", "slot"));
                String nodeId = nodeRefKey(node.path("nodeId"), allowClientKeys, label, "额外节点");
                if (!node.path("slot").isTextual() || !node.path("slot").asText().matches("[a-z][a-z0-9_]{0,31}")) fail(label + " 额外任务槽键不合法");
                if (!extra.add(nodeId)) fail(label + " 额外节点重复：" + nodeId);
                if (grouped.contains(nodeId)) fail(label + " 节点不能同时属于互斥组和额外节点：" + nodeId);
            }
        }
    }
    private static void grants(JsonNode node, Set<String> items) {
        Set<String> ids = new HashSet<>();
        for (JsonNode g : rows(node, "grants", 300)) {
            fields(g, set("id", "source", "nodeId", "step", "option", "outcome", "itemId", "quantity", "endingId", "reasons", "success", "when")); id(g, ids);
            enumeration(g, "source", "NODE", "CHOICE", "DRAW", "ENDING"); ref(g, "itemId", items); integer(g, "quantity", 1, 99);
            conditions(g.path("when"),items);
            String source = g.path("source").asText();
            if(g.has("endingId"))text(g,"endingId",32,true); bool(g,"success");
            for(JsonNode reason:rows(g,"reasons",4))if(!reason.isTextual() || !set("COMPLETED","EXHAUSTED","ABANDONED","BUDGET").contains(reason.asText()))fail("发放结束原因不支持");
            if (!"ENDING".equals(source)) integer(g, "nodeId", 1, Long.MAX_VALUE);
            if ("CHOICE".equals(source)) { text(g, "step", 64, true); text(g, "option", 64, true); }
            if ("DRAW".equals(source)) integer(g, "outcome", 1, 1000);
        }
    }
    private static void endingGrantReferences(JsonNode node, JsonNode root) {
        Set<String> ids = new HashSet<>();
        for (JsonNode ending : (node.has("endings") ? node : root).path("endings")) ids.add(ending.path("id").asText());
        for (JsonNode grant : (node.has("grants") ? node : root).path("grants")) {
            if (!"ENDING".equals(grant.path("source").asText()) && (grant.has("endingId") || grant.has("reasons") || grant.has("success"))) fail("结束筛选只能用于终局发放");
            if (grant.has("endingId") && !ids.contains(grant.path("endingId").asText())) fail("发放引用未知结局");
        }
    }
    static void events(JsonNode node, Set<String> items) {
        Set<String> ids = new HashSet<>();
        for (JsonNode e : rows(node, "events", 100)) {
            fields(e, set("id", "content", "when", "grants", "effects")); id(e, ids); text(e, "content", 500, true);
            conditions(e.path("when"), items);
            for (JsonNode g : rows(e, "grants", 20)) { fields(g, set("itemId", "quantity")); ref(g, "itemId", items); integer(g, "quantity", 1, 99); }
            for (JsonNode effect : rows(e, "effects", 20)) {
                fields(effect, set("var", "op", "value"));
                String var = effect.path("var").asText();
                if (!var.matches("(clue|relation|counter)\\.[a-z][a-z0-9_]{0,47}")) fail("事件只能修改已注册的剧情状态");
                enumeration(effect, "op", "SET", "INC", "FLOOR"); integer(effect, "value", -999, 999);
                if ("FLOOR".equals(effect.path("op").asText()) && (!var.startsWith("counter.") || effect.path("value").asInt() < 1)) fail("FLOOR 仅支持正整数数值域");
            }
        }
    }
    private static void endings(JsonNode n, Set<String> items) {
        Set<String> ids = new HashSet<>(); int fallback = 0;
        for (JsonNode e : rows(n, "endings", 30)) {
            fields(e, set("id", "title", "summary", "when", "priority", "fallback", "success", "reasons")); id(e, ids);
            text(e, "title", 80, true); text(e, "summary", 2000, false);
            for(JsonNode reason:rows(e,"reasons",4))if(!reason.isTextual() || !set("COMPLETED","EXHAUSTED","ABANDONED","BUDGET").contains(reason.asText()))fail("结局结束原因不支持");
            conditions(e.path("when"), items); bool(e, "fallback"); bool(e, "success");
            if (e.has("priority")) integer(e, "priority", -999, 999);
            if (e.path("fallback").asBoolean()) fallback++;
        }
        if (n.path("endings").size() > 0 && fallback != 1) fail("结局须有且仅有一个默认收尾");
    }
    static void conditions(JsonNode c, Set<String> items) {
        if (c.isMissingNode() || c.isNull()) return;
        if (!c.isArray() || c.size() > 30) fail("条件格式或条数不正确");
        for (JsonNode row : c) {
            fields(row, set("op", "itemId", "quantity", "nodeId", "var", "value"));
            String op = row.path("op").asText();
            if ("ITEM_HELD".equals(op) || "ITEM_ACQUIRED".equals(op)) { ref(row, "itemId", items); integer(row, "quantity", 1, 999); }
            else if ("NODE_COMPLETED".equals(op)) integer(row, "nodeId", 1, Long.MAX_VALUE);
            else {
                enumeration(row, "op", "EQ", "NE", "GT", "GTE", "LT", "LTE");
                String var = row.path("var").asText();
                if (!var.matches("(clue|relation|counter)\\.[a-z][a-z0-9_]{0,47}") && !set("sys.hp", "sys.luck", "sys.actions").contains(var)) fail("条件状态不受支持");
                integer(row, "value", -999999, 999999);
            }
        }
    }
    private static void card(JsonNode c) {
        if (c.isMissingNode() || c.isNull()) return;
        fields(c, set("cover", "fields", "showUnlocks")); media(c, "cover", false); bool(c, "showUnlocks");
        for (JsonNode f : rows(c, "fields", 10)) if (!f.isTextual() || !set("scene", "ending", "hp", "luck", "actions", "medals", "items", "story", "rescue").contains(f.asText())) fail("结果卡公开字段不支持");
    }
    public static void assertReferences(ObjectNode config, Set<Long> nodeIds, Set<String> stateKeys) {
        if (config == null) return;
<<<<<<< port
        walkReferences(config, nodeIds, stateKeys, Collections.emptyMap());
        TemplateResourceSlots.assertReferences(config, nodeIds, stateKeys);
        GameplayRuntimeConfig.assertChallengeNodes(config.path("gameplayRuntime").path("survival"), nodeIds);
        assertPlanEndings(config);
=======
        validateReferenceShapes(config);
        walkReferences(config, nodeIds, stateKeys);
>>>>>>> questify
        assertItemReferences(config,config);
        for(JsonNode scene:config.path("scenes"))scene.path("nodeConfigs").fieldNames().forEachRemaining(id->{if(!nodeIds.contains(sceneNodeId(id)))fail("场景引用外来节点");});
    }
    /** Registered state references in a parsed config, including inactive scene overrides. No facts are created. */
    public static Set<String> referencedStateKeys(ObjectNode config) {
        Set<String> keys = new LinkedHashSet<String>();
        collectStateKeys(config, keys);
        return Collections.unmodifiableSet(keys);
    }
    private static void collectStateKeys(JsonNode node, Set<String> keys) {
        if (node == null) return;
        if (node.isObject()) {
            for (String field : Arrays.asList("relationKey", "var")) {
                String key = registeredStateReference(node, field);
                if (key != null) keys.add(key);
            }
            node.elements().forEachRemaining(child -> collectStateKeys(child, keys));
        } else if (node.isArray()) node.forEach(child -> collectStateKeys(child, keys));
    }
    /** The read collector and publication validator must agree on which fields require registration. */
    private static String registeredStateReference(JsonNode node, String field) {
        if (!node.has(field)) return null;
        String key = node.path(field).asText();
        return "var".equals(field) && key.startsWith("sys.") ? null : key;
    }
    static void assertPlanEndings(JsonNode config) {
        for (JsonNode plan : config.path("gameplayRuntime").path("plans")) if (plan.path("terminal").asBoolean(true)) {
            if (plan.has("storyEndingId")) {
                boolean matched = false; int fallback = 0;
                for (JsonNode ending : config.path("gameplayRuntime").path("storyEndings")) {
                    if (ending.path("fallback").asBoolean()) fallback++;
                    matched |= plan.path("storyEndingId").asText().equals(ending.path("code").asText()) && plan.path("endingChapterId").asLong() == ending.path("chapterId").asLong();
                }
                if (!matched || fallback != 1) fail("方案 " + plan.path("endingKey").asText() + " 须链接实际剧情结局和唯一默认收尾");
                continue;
            }
            String key = plan.path("endingKey").asText();
            boolean matched = false; int fallback = 0;
            for (JsonNode ending : config.path("endings")) {
                matched |= key.equals(ending.path("id").asText());
                if (ending.path("fallback").asBoolean()) fallback++;
            }
            if (!matched || fallback != 1) fail("方案 " + key + " 须有对应结局和唯一默认收尾");
        }
    }
    private static void walkReferences(JsonNode n, Set<Long> nodeIds, Set<String> stateKeys, Map<String, String> inheritedSlots) {
        if (n.isObject()) {
<<<<<<< port
            Map<String, String> slots = TemplateResourceSlots.referenceSlots(n, inheritedSlots);
            boolean variableSlot = n.has("var") && TemplateResourceSlots.isSlot(n, "var", "VALUE", slots);
            if (n.has("nodeId") && !nodeIds.contains(n.get("nodeId").asLong())) fail("主题配置引用外来节点");
            if(n.has("approvedFallbackNodeIds"))for(JsonNode id:n.path("approvedFallbackNodeIds"))if(!nodeIds.contains(id.asLong()))fail("备用点不属于本主题");
            if(n.has("operationNodeId") && !nodeIds.contains(n.path("operationNodeId").asLong()))fail("方案操作节点不属于本主题");
            if(n.has("dispositionNodeId") && !nodeIds.contains(n.path("dispositionNodeId").asLong()))fail("剧情处置节点不属于本主题");
            if(n.has("var") && !variableSlot && n.path("var").asText().matches("sys\\.(asked|passed|outcome|mistakes|damage)\\.[0-9]+(?:\\..+)?")) {String[] parts=n.path("var").asText().split("\\.",4);if(!nodeIds.contains(Long.valueOf(parts[2])))fail("条件引用外来节点");}
            if(n.has("statKey") && !TemplateResourceSlots.isSlot(n, "statKey", "VALUE", slots) && !stateKeys.contains(n.path("statKey").asText()))fail("数值状态未注册");
            if(n.has("relationKey") && !stateKeys.contains(n.path("relationKey").asText()))fail("人物关系状态未注册");
            if (n.has("var") && !variableSlot && !n.path("var").asText().startsWith("sys.") && !stateKeys.contains(n.path("var").asText())) fail("剧情状态未注册");
            n.fields().forEachRemaining(entry -> { if (!"templateBindings".equals(entry.getKey())) walkReferences(entry.getValue(), nodeIds, stateKeys, slots); });
        } else if (n.isArray()) n.forEach(child -> walkReferences(child, nodeIds, stateKeys, inheritedSlots));
=======
            if (n.has("nodeId") && !nodeIds.contains(referenceNodeId(n,"nodeId"))) fail("主题配置引用外来节点");
            if(n.has("dispositionNodeId") && !nodeIds.contains(referenceNodeId(n,"dispositionNodeId")))fail("剧情处置节点不属于本主题");
            if(n.has("var") && n.path("var").asText().matches("sys\\.(passed|outcome|mistakes|damage)\\.[0-9]+")) {String key=n.path("var").asText();if(!nodeIds.contains(Long.valueOf(key.substring(key.lastIndexOf('.')+1))))fail("条件引用外来节点");}
            Matcher asked = askedReference(n.path("var").asText());
            if (asked != null && !nodeIds.contains(Long.valueOf(asked.group(1)))) fail("问答条件引用外来节点");
            String relationKey = registeredStateReference(n, "relationKey");
            if (relationKey != null && !stateKeys.contains(relationKey)) fail("人物关系状态未注册");
            String variable = registeredStateReference(n, "var");
            if (variable != null && !stateKeys.contains(variable)) fail("剧情状态未注册");
            n.elements().forEachRemaining(child -> walkReferences(child, nodeIds, stateKeys));
        } else if (n.isArray()) n.forEach(child -> walkReferences(child, nodeIds, stateKeys));
>>>>>>> questify
    }
    public static String remapAll(String raw, Map<Long,Long> nodes, Map<Long,Long> chapters, Map<Long,Long> npcs) {
        ObjectNode config=parseDraft(raw); if(config==null)return raw==null?null:""; remap(config,nodes);
        config.path("templateBindings").forEach(binding -> remapBindingEndings(binding, chapters));
        for(JsonNode scene:config.path("scenes")) if(scene.has("journeyStory")) {
            List<String> warnings=new ArrayList<>();
            String mapped=com.chengyinhub.business.domain.JourneyStory.remapChapterIds(scene.path("journeyStory").toString(),chapters);
            if(npcs!=null)mapped=com.chengyinhub.business.domain.JourneyStory.remapNpcIds(mapped,npcs,warnings);
            if(!warnings.isEmpty())fail("场景人物复制没有对应目标: "+String.join(";",warnings));
            try { ((ObjectNode)scene).set("journeyStory",JSON.readTree(mapped)); } catch(Exception ex) { throw new IllegalArgumentException("场景剧情复制失败",ex); }
        }
        return parseDraft(config.toString()).toString();
    }
    private static void remapBindingEndings(JsonNode binding, Map<Long,Long> chapters) {
        binding.path("planEndings").fields().forEachRemaining(plan -> {
            long source = TemplateResourceSlots.nodeId(plan.getValue().asText());
            Long target = chapters == null ? null : chapters.get(source);
            if (target == null || target <= 0L) fail("方案 " + plan.getKey() + " 结局章 " + source + " 没有对应目标");
            ((ObjectNode)binding.path("planEndings")).put(plan.getKey(), String.valueOf(target));
        });
    }
    public static String remapNodes(String raw, Map<Long, Long> map) {
        ObjectNode n = parseDraft(raw); if (n == null) return raw==null?null:""; remap(n, map); return parseDraft(n.toString()).toString();
    }
    private static void remap(JsonNode n, Map<Long, Long> map) {
        Set<JsonNode> stories = Collections.newSetFromMap(new IdentityHashMap<JsonNode, Boolean>());
        for (JsonNode scene : n.path("scenes")) if (scene.has("journeyStory")) {
            String mapped = com.chengyinhub.business.domain.JourneyStory.remapNodeIds(
                    scene.get("journeyStory").toString(), map);
            try {
                JsonNode story = JSON.readTree(mapped);
                ((ObjectNode) scene).set("journeyStory", story);
                stories.add(story);
            } catch (Exception ex) { throw new IllegalArgumentException("场景剧情节点复制失败", ex); }
        }
        remapRuntime(n, map, stories);
    }
    private static void remapRuntime(JsonNode n, Map<Long, Long> map, Set<JsonNode> stories) {
        if (stories.contains(n)) return;
        if (n.isObject()) {
<<<<<<< port
            if(n.has("var") && n.path("var").asText().matches("sys\\.(asked|passed|outcome|mistakes|damage)\\.[0-9]+(?:\\..+)?")) {String[] parts=n.path("var").asText().split("\\.",4);Long target=map.get(Long.valueOf(parts[2]));if(target==null)fail("复制时条件节点没有对应目标");((ObjectNode)n).put("var",parts[0]+"."+parts[1]+"."+target+(parts.length==4?"."+parts[3]:""));}
            if(n.has("operationNodeId")){Long target=map.get(n.path("operationNodeId").asLong());if(target==null)fail("复制时方案操作节点没有对应目标");((ObjectNode)n).put("operationNodeId",target);}
            if(n.has("challengeNodeId")){Long target=map.get(n.path("challengeNodeId").asLong());if(target==null)fail("复制时挑战节点没有对应目标");((ObjectNode)n).put("challengeNodeId",target);}
            if(n.has("alternativeNodeIds")){com.fasterxml.jackson.databind.node.ArrayNode ids=JSON.createArrayNode();for(JsonNode id:n.path("alternativeNodeIds")){Long target=map.get(id.asLong());if(target==null)fail("复制时备选挑战节点没有对应目标");ids.add(target);}((ObjectNode)n).set("alternativeNodeIds",ids);}
            if(n.has("approvedFallbackNodeIds")) {com.fasterxml.jackson.databind.node.ArrayNode ids=JSON.createArrayNode();for(JsonNode id:n.path("approvedFallbackNodeIds")){Long target=map.get(id.asLong());if(target==null)fail("复制时批准备用点没有对应目标");ids.add(target);}((ObjectNode)n).set("approvedFallbackNodeIds",ids);}
            if (n.has("exclusiveGroups")) {
                com.fasterxml.jackson.databind.node.ArrayNode groups = JSON.createArrayNode();
                for (JsonNode group : n.path("exclusiveGroups")) {
                    com.fasterxml.jackson.databind.node.ArrayNode ids = groups.addArray();
                    for (JsonNode id : group) {
                        Long target = map.get(id.asLong());
                        if (target == null) fail("复制时互斥组节点没有对应目标：" + id.asLong());
                        ids.add(target);
                    }
                }
                ((ObjectNode)n).set("exclusiveGroups", groups);
            }
            if(n.has("dispositionNodeId")){Long target=map.get(n.path("dispositionNodeId").asLong());if(target==null)fail("复制时处置节点没有对应目标");((ObjectNode)n).put("dispositionNodeId",target);}
            if (n.has("nodeId")) { Long target = map.get(n.get("nodeId").asLong()); if (target == null) fail("复制时节点引用没有对应目标"); ((ObjectNode)n).put("nodeId", target); }
=======
            if(n.has("var") && n.path("var").asText().matches("sys\\.(passed|outcome|mistakes|damage)\\.[0-9]+")) {String key=n.path("var").asText();int dot=key.lastIndexOf('.');Long target=remappedNodeId(map,Long.valueOf(key.substring(dot+1)));((ObjectNode)n).put("var",key.substring(0,dot+1)+target);}
            Matcher asked = askedReference(n.path("var").asText());
            if (asked != null) {
                Long target = remappedNodeId(map,Long.valueOf(asked.group(1)));
                ((ObjectNode)n).put("var", "sys.asked." + target + "." + asked.group(2));
            }
            if(n.has("dispositionNodeId")){Long target=remappedNodeId(map,referenceNodeId(n,"dispositionNodeId"));((ObjectNode)n).put("dispositionNodeId",target);}
            if (n.has("nodeId")) { Long target = remappedNodeId(map,referenceNodeId(n,"nodeId")); ((ObjectNode)n).put("nodeId", target); }
>>>>>>> questify
            if(n.has("nodeConfigs")) {
                ObjectNode renamed=JSON.createObjectNode();n.get("nodeConfigs").fields().forEachRemaining(row->{Long target=remappedNodeId(map,sceneNodeId(row.getKey()));String key=String.valueOf(target);if(renamed.has(key))fail("复制时场景节点目标重复");renamed.set(key,row.getValue());});((ObjectNode)n).set("nodeConfigs",renamed);
            }
            if (n.has("templateBindings")) {
                ObjectNode renamed = JSON.createObjectNode();
                n.path("templateBindings").fields().forEachRemaining(entry -> {
                    Long target = map.get(TemplateResourceSlots.nodeId(entry.getKey()));
                    if (target == null || renamed.has(String.valueOf(target))) fail("复制时资源绑定节点没有唯一目标：" + entry.getKey());
                    renamed.set(String.valueOf(target), entry.getValue());
                });
                ((ObjectNode)n).set("templateBindings", renamed);
            }
            n.fields().forEachRemaining(entry -> { if (!"templateBindings".equals(entry.getKey())) remapRuntime(entry.getValue(), map, stories); });
        } else if (n.isArray()) n.forEach(child -> remapRuntime(child, map, stories));
    }
    public static void assertItemReferences(JsonNode content,ObjectNode config) {
        assertItemReferences(content, config, Collections.emptyMap());
    }
    private static void assertItemReferences(JsonNode content, ObjectNode config, Map<String, String> inheritedSlots) {
        if(content==null)return;
        if(content.isObject()) {
            Map<String, String> slots = TemplateResourceSlots.referenceSlots(content, inheritedSlots);
            String op=content.path("op").asText();
            if("ITEM_HELD".equals(op)||"ITEM_ACQUIRED".equals(op)){boolean exists=TemplateResourceSlots.isSlot(content,"itemId","ITEM",slots);if(config!=null)for(JsonNode item:config.path("items"))if(content.path("itemId").asText().equals(item.path("id").asText()))exists=true;if(!exists)fail("条件引用的背包内容不存在");}
            content.fields().forEachRemaining(entry -> { if (!"templateBindings".equals(entry.getKey())) assertItemReferences(entry.getValue(), config, slots); });
        } else if(content.isArray())content.forEach(child->assertItemReferences(child,config,inheritedSlots));
    }
    static void counterKey(JsonNode node, String field) {
        if (!node.path(field).isTextual() || !node.path(field).asText().matches("counter\\.[a-z][a-z0-9_]{0,47}")) fail(field + " 只允许独立counter数值属性");
    }
    static void statCosts(JsonNode node) {
        Set<String> keys = new HashSet<>();
        for (JsonNode cost : rows(node, "statCosts", 32)) {
            fields(cost, set("statKey", "amount")); counterKey(cost, "statKey"); integer(cost, "amount", 0, 999);
            if (!keys.add(cost.path("statKey").asText())) fail("数值费用重复");
        }
    }
    public static void assertReferences(ObjectNode config, Set<Long> nodeIds, Set<String> stateKeys, JourneyRules rules) {
        Set<String> keys = new HashSet<>(stateKeys);
        for (JsonNode attribute : rules.getAttributes()) keys.add(attribute.path("key").asText());
        assertReferences(config, nodeIds, keys);
        assertJourneyReferences(config, rules);
    }
    public static void assertJourneyReferences(ObjectNode config, JourneyRules rules) {
        if (config == null) return;
        TemplateResourceSlots.assertEnabled(config, rules);
        assertJourneyReferences((JsonNode) config, rules, config.has("gameplayRuntime"), Collections.emptyMap());
        JsonNode survival = config.path("gameplayRuntime").path("survival");
        if (set("CHALLENGE", "THEME_FAIL", "WAIT", "STEPS").contains(survival.path("zeroHpPolicy").asText())) {
            if (!rules.hasRuntimeState() || !rules.getHp().isEnabled()) fail("归零挑战需要启用生命值");
            for (String field : Arrays.asList("zeroChallenge", "zeroWait", "zeroSteps"))
                if (survival.path(field).path("restoreTo").asInt() > rules.getHp().getMax()) fail(field + ".restoreTo 超出 hp.max");
        }
        JourneyStatLinks.normalize(rules.getStatLinks(), rules);
    }
    private static void assertJourneyReferences(JsonNode node, JourneyRules rules, boolean gameplay, Map<String, String> inheritedSlots) {
        if (node.isObject()) {
            Map<String, String> slots = TemplateResourceSlots.referenceSlots(node, inheritedSlots);
            if (node.has("statKey") && !TemplateResourceSlots.isSlot(node, "statKey", "VALUE", slots)) {
                String target = node.path("statKey").asText();
                if ("sys.hp".equals(target) || "sys.luck".equals(target)) {
                    if (!rules.hasRuntimeState() || ("sys.hp".equals(target) ? !rules.getHp().isEnabled() : !rules.getLuck().isEnabled())) fail("结果费用目标未启用");
                } else JourneyInitialState.numericAttribute(rules, target);
            }
            if (node.has("stat")) {
                String stat = node.path("stat").asText();
                if (!(gameplay ? rules.hasRuntimeState() : rules.isCheckEnabled()) || ("HP".equals(stat) ? !rules.getHp().isEnabled() : !rules.getLuck().isEnabled())) fail("物品数值目标未启用");
            }
            if (node.has("hpDelta") && node.path("hpDelta").asInt() != 0 && (!rules.hasRuntimeState() || !rules.getHp().isEnabled())) fail("HP变化目标未启用");
            if (node.path("hpCost").asInt() > 0 && (!rules.hasRuntimeState() || !rules.getHp().isEnabled())) fail("HP费用目标未启用");
            if (node.path("luckCost").asInt() > 0 && (!rules.hasRuntimeState() || !rules.getLuck().isEnabled())) fail("幸运费用目标未启用");
            node.fields().forEachRemaining(entry -> { if (!"templateBindings".equals(entry.getKey())) assertJourneyReferences(entry.getValue(), rules, gameplay, slots); });
        } else if (node.isArray()) node.forEach(child -> assertJourneyReferences(child, rules, gameplay, inheritedSlots));
    }
    private static Matcher askedReference(String key) {
        if (!key.equals("sys.asked") && !key.startsWith("sys.asked.")) return null;
        Matcher asked = SYS_ASKED_REFERENCE.matcher(key);
        if (!asked.matches()) fail("问答条件状态引用格式不正确");
        // 超出 Long 的节点 ID 必须拒绝，不能截断后核对或重映射。
        Long.valueOf(asked.group(1));
        return asked;
    }
    // 场景不得继承旧条件读取中的数字别名；先验原始 JSON，再调用既有规范化器。
    private static void validateReferenceShapes(JsonNode node) {
        if(node.isObject()) {
            if(node.has("nodeId"))referenceNodeId(node,"nodeId");
            if(node.has("dispositionNodeId"))referenceNodeId(node,"dispositionNodeId");
            if(node.has("nodeConfigs")) {
                if(!node.get("nodeConfigs").isObject())fail("场景节点玩法格式不正确");
                node.get("nodeConfigs").fieldNames().forEachRemaining(ThemeRuntimeConfig::sceneNodeId);
            }
            askedReference(node.path("var").asText());
            node.elements().forEachRemaining(ThemeRuntimeConfig::validateReferenceShapes);
        } else if(node.isArray())node.forEach(ThemeRuntimeConfig::validateReferenceShapes);
    }
    private static Long referenceNodeId(JsonNode node,String field) {
        JsonNode value=node.path(field);
        if(!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong()<=0)fail(field+" 须为正整数节点 ID");
        return value.asLong();
    }
    private static Long remappedNodeId(Map<Long,Long> nodes,Long source) {
        Long target=nodes==null?null:nodes.get(source);
        if(target==null || target<=0)fail("复制时节点没有合法对应目标");
        return target;
    }
    private static Long sceneNodeId(String key) {
        if(!key.matches("[1-9][0-9]*"))fail("场景节点 ID 须为规范正整数");
        return Long.valueOf(key);
    }
    public static List<JsonNode> rows(JsonNode n, String key, int limit) {
        JsonNode array = n.path(key); if (array.isMissingNode() || array.isNull()) return Collections.emptyList();
        if (!array.isArray() || array.size() > limit) fail(key + " 格式或条数不正确");
        List<JsonNode> list = new ArrayList<>(); array.forEach(list::add); return list;
    }
    private static void id(JsonNode n, Set<String> seen) { String id = n.path("id").asText(); if (!id.matches("[a-z][a-z0-9_]{0,31}") || !seen.add(id)) fail("目录 ID 格式不正确或重复"); }
    private static void ref(JsonNode n, String key, Set<String> ids) { if (!ids.contains(n.path(key).asText())) fail("引用的物资不存在"); }
    private static void bool(JsonNode n, String key) { if (n.has(key) && !n.get(key).isBoolean()) fail(key + " 须为开关"); }
    static void integer(JsonNode n, String key, long min, long max) { JsonNode v = n.path(key); if (!v.isIntegralNumber() || !v.canConvertToLong() || v.asLong() < min || v.asLong() > max) fail(key + " 数值不合法"); }
    private static void text(JsonNode n, String key, int max, boolean required) { JsonNode v = n.path(key); if (!v.isMissingNode() && !v.isTextual()) fail(key + " 须为文字"); if ((required && v.asText().trim().isEmpty()) || v.asText().length() > max) fail(key + " 内容不合法"); }
    private static void media(JsonNode n, String key, boolean required) { text(n,key,500,required); if (n.has(key) && !n.path(key).asText().matches("(https://[^\\s]+|media-asset:[1-9][0-9]{0,18})")) fail("图片须为 HTTPS 地址"); }
    static void enumeration(JsonNode n, String key, String... allowed) { if (!set(allowed).contains(n.path(key).asText())) fail(key + " 选项不支持"); }
    static void fields(JsonNode n, Set<String> allowed) { if (!n.isObject()) fail("配置条目须为对象"); n.fieldNames().forEachRemaining(f -> { if (!allowed.contains(f)) fail("不支持的配置字段: " + f); }); }
    static Set<String> set(String... values) { return new HashSet<>(Arrays.asList(values)); }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
    private ThemeRuntimeConfig() {}
}
