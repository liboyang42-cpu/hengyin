package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** 通用主题配置。目录、触发和场景只含受控数据，不接受脚本或系统属性写入。 */
public final class ThemeRuntimeConfig {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ROOT = set("schemaVersion", "capacity", "hpName", "luckName", "items", "grants", "events", "scenes", "rotation", "currentScene", "replayEnabled", "endings", "card", "rotationAnchor", "actionLimit", "actionSources", "rescueLimit", "allowDiscard", "chapters");
    public static ObjectNode parse(String raw) { return parse(raw,false); }
    public static ObjectNode parseDraft(String raw) { return parse(raw,true); }
    private static ObjectNode parse(String raw,boolean draft) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            JsonNode node = JSON.readTree(raw);
            if (!node.isObject() || raw.length() > 200000) fail("主题配置格式或长度不正确");
            ObjectNode config = (ObjectNode) node;
            fields(config, ROOT); integer(config, "schemaVersion", 1, 1);
            if(config.path("items").size()>0)integer(config,"capacity",1,999);
            else if(config.has("capacity"))integer(config,"capacity",0,999);else config.put("capacity",0);
            if(config.has("actionLimit"))integer(config,"actionLimit",1,99999);
            if(config.has("rescueLimit"))integer(config,"rescueLimit",0,10);
            for(JsonNode source:rows(config,"actionSources",3))if(!source.isTextual() || !set("NODE","CHOICE","DRAW").contains(source.asText()))fail("行动计数来源不支持");
            text(config, "hpName", 20, false); text(config, "luckName", 20, false);
            bool(config, "replayEnabled"); bool(config,"allowDiscard");
            Set<String> items = new HashSet<>();
            for (JsonNode item : rows(config, "items", 200)) {
                fields(item, set("id", "kind", "name", "image", "stat", "delta", "wear", "mode"));
                id(item, items); if(item.path("id").asText().startsWith("legacy_"))fail("legacy_ 为旧勋章兼容保留前缀"); text(item, "name", 40, !draft); media(item, "image", !draft);
                enumeration(item, "kind", "MEDAL", "ITEM");
                if ("MEDAL".equals(item.path("kind").asText())) {
                    if (item.has("stat") || item.has("delta") || item.has("wear") || item.has("mode")) fail("勋章不能配置物品效果");
                } else if (item.has("stat")) {
                    enumeration(item, "stat", "HP", "LUCK");
                    integer(item, "delta", -999, 999); if (item.path("delta").asInt() == 0) fail("物品效果不能为零");
                    bool(item, "wear");
                    if (item.path("wear").asBoolean()) enumeration(item, "mode", "ONCE", "HELD", "REDUCE");
                    else if (item.has("mode")) fail("普通物品不能设置穿戴方式");
                    if("REDUCE".equals(item.path("mode").asText()) && (!"HP".equals(item.path("stat").asText()) || item.path("delta").asInt()<1))fail("减耗只支持正数生命值");
                } else if(item.has("delta") || item.has("wear") || item.has("mode")) { fail("展示物品不能设置效果");
                }
            }
            chapters(config, items); grants(config, items); events(config, items); endings(config, items); card(config.path("card"));
            Set<String> scenes = new HashSet<>();
            for (JsonNode scene : rows(config, "scenes", 50)) {
                fields(scene, set("id", "name", "grants", "events", "endings", "journeyStory", "nodeConfigs"));
                id(scene, scenes); text(scene, "name", 40, true);
                grants(scene, items); events(scene, items); endings(scene, items);
                if(scene.has("nodeConfigs")) {
                    if(!scene.get("nodeConfigs").isObject() || scene.get("nodeConfigs").size()>200)fail("场景节点玩法格式不正确");
                    java.util.Iterator<String> nodeKeys=scene.get("nodeConfigs").fieldNames();
                    while(nodeKeys.hasNext()) {String key=nodeKeys.next();if(!key.matches("[1-9][0-9]{0,17}"))fail("场景节点 ID 不正确");new AdvancedGameConfigValidator().validateAndNormalize(scene.get("nodeConfigs").get(key).toString());}
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
    private static void chapters(JsonNode root, Set<String> items) {
        Set<Integer> indexes=new HashSet<>();
        for(JsonNode ch:rows(root,"chapters",200)) {
            fields(ch,set("index","hpCost","luckCost","grants","eggs")); integer(ch,"index",0,199);
            if(!indexes.add(ch.path("index").asInt()))fail("章节配置重复");
            integer(ch,"hpCost",0,999);integer(ch,"luckCost",0,999);
            Set<String> granted=new HashSet<>();
            for(JsonNode g:rows(ch,"grants",200)) {fields(g,set("itemId","quantity","timing"));ref(g,"itemId",items);integer(g,"quantity",1,99);enumeration(g,"timing","START","END");if(!granted.add(g.path("itemId").asText()))fail("章节物资重复");}
            Set<String> eggs=new HashSet<>();
            for(JsonNode e:rows(ch,"eggs",100)) {
                fields(e,set("id","name","trigger","threshold","content","rewards"));id(e,eggs);text(e,"name",40,true);text(e,"content",500,true);enumeration(e,"trigger","TIME","STEPS");integer(e,"threshold",1,99999);
                for(JsonNode r:rows(e,"rewards",20)) {fields(r,set("itemId","quantity"));ref(r,"itemId",items);integer(r,"quantity",1,99);}
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
    private static void events(JsonNode node, Set<String> items) {
        Set<String> ids = new HashSet<>();
        for (JsonNode e : rows(node, "events", 100)) {
            fields(e, set("id", "content", "when", "grants", "effects")); id(e, ids); text(e, "content", 500, true);
            conditions(e.path("when"), items);
            for (JsonNode g : rows(e, "grants", 20)) { fields(g, set("itemId", "quantity")); ref(g, "itemId", items); integer(g, "quantity", 1, 99); }
            for (JsonNode effect : rows(e, "effects", 20)) {
                fields(effect, set("var", "op", "value"));
                String var = effect.path("var").asText();
                if (!var.matches("(clue|relation|counter)\\.[a-z][a-z0-9_]{0,47}")) fail("事件只能修改已注册的剧情状态");
                enumeration(effect, "op", "SET", "INC"); integer(effect, "value", -999, 999);
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
    private static void conditions(JsonNode c, Set<String> items) {
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
        walkReferences(config, nodeIds, stateKeys);
        assertItemReferences(config,config);
        for(JsonNode scene:config.path("scenes"))scene.path("nodeConfigs").fieldNames().forEachRemaining(id->{if(!nodeIds.contains(Long.valueOf(id)))fail("场景引用外来节点");});
    }
    private static void walkReferences(JsonNode n, Set<Long> nodeIds, Set<String> stateKeys) {
        if (n.isObject()) {
            if (n.has("nodeId") && !nodeIds.contains(n.get("nodeId").asLong())) fail("主题配置引用外来节点");
            if(n.has("dispositionNodeId") && !nodeIds.contains(n.path("dispositionNodeId").asLong()))fail("剧情处置节点不属于本主题");
            if(n.has("var") && n.path("var").asText().matches("sys\\.(passed|outcome|mistakes|damage)\\.[0-9]+")) {String key=n.path("var").asText();if(!nodeIds.contains(Long.valueOf(key.substring(key.lastIndexOf('.')+1))))fail("条件引用外来节点");}
            if(n.has("relationKey") && !stateKeys.contains(n.path("relationKey").asText()))fail("人物关系状态未注册");
            if (n.has("var") && !n.path("var").asText().startsWith("sys.") && !stateKeys.contains(n.path("var").asText())) fail("剧情状态未注册");
            n.elements().forEachRemaining(child -> walkReferences(child, nodeIds, stateKeys));
        } else if (n.isArray()) n.forEach(child -> walkReferences(child, nodeIds, stateKeys));
    }
    public static String remapAll(String raw, Map<Long,Long> nodes, Map<Long,Long> chapters, Map<Long,Long> npcs) {
        ObjectNode config=parseDraft(raw); if(config==null)return raw==null?null:""; remap(config,nodes);
        for(JsonNode scene:config.path("scenes")) if(scene.has("journeyStory")) {
            List<String> warnings=new ArrayList<>();
            String mapped=com.chengyinhub.business.domain.JourneyStory.remapChapterIds(scene.path("journeyStory").toString(),chapters);
            if(npcs!=null)mapped=com.chengyinhub.business.domain.JourneyStory.remapNpcIds(mapped,npcs,warnings);
            if(!warnings.isEmpty())fail("场景人物复制没有对应目标: "+String.join(";",warnings));
            try { ((ObjectNode)scene).set("journeyStory",JSON.readTree(mapped)); } catch(Exception ex) { throw new IllegalArgumentException("场景剧情复制失败",ex); }
        }
        return config.toString();
    }
    public static String remapNodes(String raw, Map<Long, Long> map) {
        ObjectNode n = parseDraft(raw); if (n == null) return raw==null?null:""; remap(n, map); return n.toString();
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
            if(n.has("var") && n.path("var").asText().matches("sys\\.(passed|outcome|mistakes|damage)\\.[0-9]+")) {String key=n.path("var").asText();int dot=key.lastIndexOf('.');Long target=map.get(Long.valueOf(key.substring(dot+1)));if(target==null)fail("复制时条件节点没有对应目标");((ObjectNode)n).put("var",key.substring(0,dot+1)+target);}
            if(n.has("dispositionNodeId")){Long target=map.get(n.path("dispositionNodeId").asLong());if(target==null)fail("复制时处置节点没有对应目标");((ObjectNode)n).put("dispositionNodeId",target);}
            if (n.has("nodeId")) { Long target = map.get(n.get("nodeId").asLong()); if (target == null) fail("复制时节点引用没有对应目标"); ((ObjectNode)n).put("nodeId", target); }
            if(n.has("nodeConfigs")) {
                ObjectNode renamed=JSON.createObjectNode();n.get("nodeConfigs").fields().forEachRemaining(row->{Long target=map.get(Long.valueOf(row.getKey()));if(target==null)fail("复制时场景节点没有对应目标");renamed.set(String.valueOf(target),row.getValue());});((ObjectNode)n).set("nodeConfigs",renamed);
            }
            n.elements().forEachRemaining(child -> remapRuntime(child, map, stories));
        } else if (n.isArray()) n.forEach(child -> remapRuntime(child, map, stories));
    }
    public static void assertItemReferences(JsonNode content,ObjectNode config) {
        if(content==null)return;
        if(content.isObject()) {
            String op=content.path("op").asText();
            if("ITEM_HELD".equals(op)||"ITEM_ACQUIRED".equals(op)){boolean exists=false;if(config!=null)for(JsonNode item:config.path("items"))if(content.path("itemId").asText().equals(item.path("id").asText()))exists=true;if(!exists)fail("条件引用的背包内容不存在");}
            content.elements().forEachRemaining(child->assertItemReferences(child,config));
        } else if(content.isArray())content.forEach(child->assertItemReferences(child,config));
    }
    public static List<JsonNode> rows(JsonNode n, String key, int limit) {
        JsonNode array = n.path(key); if (array.isMissingNode() || array.isNull()) return Collections.emptyList();
        if (!array.isArray() || array.size() > limit) fail(key + " 格式或条数不正确");
        List<JsonNode> list = new ArrayList<>(); array.forEach(list::add); return list;
    }
    private static void id(JsonNode n, Set<String> seen) { String id = n.path("id").asText(); if (!id.matches("[a-z][a-z0-9_]{0,31}") || !seen.add(id)) fail("目录 ID 格式不正确或重复"); }
    private static void ref(JsonNode n, String key, Set<String> ids) { if (!ids.contains(n.path(key).asText())) fail("引用的物资不存在"); }
    private static void bool(JsonNode n, String key) { if (n.has(key) && !n.get(key).isBoolean()) fail(key + " 须为开关"); }
    private static void integer(JsonNode n, String key, long min, long max) { JsonNode v = n.path(key); if (!v.isIntegralNumber() || !v.canConvertToLong() || v.asLong() < min || v.asLong() > max) fail(key + " 数值不合法"); }
    private static void text(JsonNode n, String key, int max, boolean required) { JsonNode v = n.path(key); if (!v.isMissingNode() && !v.isTextual()) fail(key + " 须为文字"); if ((required && v.asText().trim().isEmpty()) || v.asText().length() > max) fail(key + " 内容不合法"); }
    private static void media(JsonNode n, String key, boolean required) { text(n,key,500,required); if (n.has(key) && !n.path(key).asText().matches("https://[^\\s]+")) fail("图片须为 HTTPS 地址"); }
    private static void enumeration(JsonNode n, String key, String... allowed) { if (!set(allowed).contains(n.path(key).asText())) fail(key + " 选项不支持"); }
    private static void fields(JsonNode n, Set<String> allowed) { if (!n.isObject()) fail("配置条目须为对象"); n.fieldNames().forEachRemaining(f -> { if (!allowed.contains(f)) fail("不支持的配置字段: " + f); }); }
    private static Set<String> set(String... values) { return new HashSet<>(Arrays.asList(values)); }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
    private ThemeRuntimeConfig() {}
}
