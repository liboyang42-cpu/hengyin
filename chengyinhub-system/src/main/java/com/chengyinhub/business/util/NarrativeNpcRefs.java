package com.chengyinhub.business.util;

import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * 节点块配置(advancedConfigJson)叙事段的 NPC 引用(R13-NPC)。
 *
 * <p>引用点只有两处:{@code enter.npcId} 与 {@code outcome.npcId}(契约 v4-A)。
 * 节点 NPC 是主题私有的(npc_profile scope_type=4 挂节点 id),节点复制 / 重开一期后
 * 节点换新 id、NPC 也会换新 id —— 副本节点模板必须把这两处按 old→new 重写,
 * 否则副本会静默指向来源主题的 NPC。
 *
 * <p>R19:叙事搬进章节故事流后,beat 块上的 npcId 是同一套引用(写侧只允许 enter / outcome
 * 段带),复制 / 重开一期时同样要重映射,见 {@link #remapChapterBlockNpcIds}。
 *
 * <p>映射缺项(门店分身不随复制等)= fail-closed:{@code npcId} 置空并往 {@code warnings}
 * 追加一句人话提示。字段缺省 / 非正整数一律不动,老配置行为不变。
 */
public final class NarrativeNpcRefs {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Object>> STORED_BLOCKS_TYPE = new TypeReference<List<Object>>() { };

    private NarrativeNpcRefs() {
    }

    /**
     * enter / outcome 是否真引用了 NPC。
     * 空配置 / 非法 JSON / 字段缺省一律按「没引用」处理(非法配置由块校验器报,不在这里抢)。
     */
    public static boolean hasNpcRefs(String advancedConfigJson) {
        if (StringUtils.isBlank(advancedConfigJson)) {
            return false;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(advancedConfigJson);
        } catch (Exception e) {
            return false;
        }
        if (root == null || !root.isObject()) {
            return false;
        }
        return hasNpcId(root.get("enter")) || hasNpcId(root.get("outcome"));
    }

    /**
     * 重写 enter.npcId / outcome.npcId;没有引用时原样返回(同一实例,调用方据此跳过回填)。
     */
    public static String remapNpcIds(String advancedConfigJson, Map<Long, Long> npcIdMap, List<String> warnings) {
        if (StringUtils.isBlank(advancedConfigJson)) {
            return advancedConfigJson;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(advancedConfigJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("高级玩法配置不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            return advancedConfigJson;
        }
        boolean touched = false;
        JsonNode enter = root.get("enter");
        if (enter != null && enter.isObject()) {
            touched |= remapNpcField((ObjectNode) enter, "enter.npcId", npcIdMap, warnings);
        }
        JsonNode outcome = root.get("outcome");
        if (outcome != null && outcome.isObject()) {
            touched |= remapNpcField((ObjectNode) outcome, "outcome.npcId", npcIdMap, warnings);
        }
        if (!touched) {
            return advancedConfigJson;
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalArgumentException("高级玩法配置无法重映射 NPC");
        }
    }

    /**
     * R19:已物化章节故事流 blocks 里 beat 块上的 {@code npcId} 按 old→new 重写 —— 与模板
     * enter / outcome 段同一口径(写侧只允许这两段带 npcId)。映射缺项置空并告警。
     *
     * <p>没有引用时原样返回(同一实例,调用方据此跳过回填);blocks 不是合法 JSON 数组时
     * fail-closed 抛 —— 复制 / 重开是新写入,不能把解不开的故事流原样搬过去。
     */
    public static String remapChapterBlockNpcIds(String blocksJson, Map<Long, Long> npcIdMap,
                                                 List<String> warnings) {
        if (StringUtils.isBlank(blocksJson)) {
            return blocksJson;
        }
        List<Object> stored;
        try {
            stored = MAPPER.readValue(blocksJson, STORED_BLOCKS_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("章节故事流不是合法 JSON");
        }
        if (stored == null) {
            throw new IllegalArgumentException("章节故事流不是数组");
        }
        boolean touched = false;
        for (Object item : stored) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> block = (Map<?, ?>) item;
            Object rawNpcId = block.get("npcId");
            // R22-SEC S13:与同文件 remapNpcField 的 canConvertToLong 口径一致 ——
            // Jackson 反序列化把 JSON 里的数字给成 Number、字符串给成 String,
            // 只认 Number 会让字符串形态的 npcId 静默漏掉重映射,副本指着来源主题的 NPC。
            Long parsedNpcId = parseNpcId(rawNpcId);
            if (parsedNpcId == null) {
                continue;
            }
            long oldId = parsedNpcId.longValue();
            Long mapped = npcIdMap == null ? null : npcIdMap.get(Long.valueOf(oldId));
            @SuppressWarnings("unchecked")
            Map<String, Object> writable = (Map<String, Object>) block;
            if (mapped != null) {
                writable.put("npcId", mapped);
            } else {
                writable.put("npcId", null);
                if (warnings != null) {
                    warnings.add("章节故事流 beat 块的 npcId=" + oldId
                            + " 没有对应的新 NPC(节点 NPC 未随复制 / 门店分身不随复制),已置空");
                }
            }
            touched = true;
        }
        if (!touched) {
            return blocksJson;
        }
        try {
            return MAPPER.writeValueAsString(stored);
        } catch (Exception e) {
            throw new IllegalArgumentException("章节故事流无法重映射 NPC");
        }
    }

    /**
     * 块上的 npcId:数字(Jackson 给 Number)与字符串(手写 / 历史 JSON)都认;
     * 非数字形态一律按「没引用」跳过 —— 与模板段 canConvertToLong 的接受面一致。
     */
    private static Long parseNpcId(Object rawNpcId) {
        if (rawNpcId == null) {
            return null;
        }
        JsonNode node = MAPPER.valueToTree(rawNpcId);
        if (node.isNumber() && node.canConvertToLong()) {
            return Long.valueOf(node.asLong());
        }
        if (node.isTextual()) {
            try {
                return Long.valueOf(node.asText().trim());
            } catch (NumberFormatException notAnId) {
                return null;
            }
        }
        return null;
    }

    private static boolean hasNpcId(JsonNode section) {
        if (section == null || !section.isObject()) {
            return false;
        }
        JsonNode npcId = section.get("npcId");
        return npcId != null && !npcId.isNull() && npcId.canConvertToLong() && npcId.asLong() > 0L;
    }

    private static boolean remapNpcField(ObjectNode section, String label, Map<Long, Long> npcIdMap,
                                         List<String> warnings) {
        JsonNode node = section.get("npcId");
        if (node == null || node.isNull() || !node.canConvertToLong()) {
            return false;
        }
        long oldId = node.asLong();
        Long mapped = npcIdMap == null ? null : npcIdMap.get(Long.valueOf(oldId));
        if (mapped != null) {
            section.put("npcId", mapped.longValue());
        } else {
            section.putNull("npcId");
            if (warnings != null) {
                warnings.add("节点模板 " + label + "=" + oldId
                        + " 没有对应的新 NPC(节点 NPC 未随复制 / 门店分身不随复制),已置空");
            }
        }
        return true;
    }
}
