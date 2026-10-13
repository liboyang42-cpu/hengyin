package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.dto.ChapterBlockDTO;
import com.chengyinhub.business.exception.ChapterFlowValidationException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 章节故事流里的节点叙事块(R18):写侧校验与读侧组装。
 *
 * <p>用户 2026-09-16 裁决:旅程叙事就是故事流,按站点存。带 {@code beat} 的 text 块承载
 * 七个叙事段(brief / route / enter / outcome / deliver / unlock / revisit)的一句正文,
 * 归属站点用块上的 nodeIndex(写入)/ nodeId(物化后)—— 与 node 块同一套机器,
 * 不按前后位置猜归属。位置只决定阅读与投影顺序:到店前的段(brief / route / enter /
 * unlock)必须排在站点 node 块之前,到店后的段(outcome / deliver / revisit)之后。
 *
 * <p>读侧把某站的全部 beat 块组装成 R8/R9 的旧契约 JSON 形状,运行期读代码不变:
 * 玩法判定段(requires / effects / check)仍留在模板,只有正文与人物归属搬进故事流。
 */
public final class ChapterNarrativeBlocks {

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_NODE = "node";
    public static final String KEY_TYPE = "type";
    public static final String KEY_CONTENT = "content";
    public static final String KEY_NODE_ID = "nodeId";
    public static final String KEY_BEAT = "beat";
    public static final String KEY_FIELD = "field";
    public static final String KEY_QID = "qid";
    public static final String KEY_NPC_ID = "npcId";
    public static final String KEY_WHEN = "when";
    public static final String KEY_CLAIM_LATER = "claimLater";

    public static final String BEAT_BRIEF = "brief";
    public static final String BEAT_ROUTE = "route";
    public static final String BEAT_ENTER = "enter";
    public static final String BEAT_OUTCOME = "outcome";
    public static final String BEAT_DELIVER = "deliver";
    public static final String BEAT_UNLOCK = "unlock";
    public static final String BEAT_REVISIT = "revisit";

    /** 到店后就演的段;其余都在到店前。 */
    private static final Set<String> POST_ARRIVAL_BEATS = setOf(BEAT_OUTCOME, BEAT_DELIVER, BEAT_REVISIT);
    /** 段 → 允许的正文位。位名沿用旧契约 JSON 的字段名。 */
    private static final Map<String, Set<String>> FIELDS_BY_BEAT = fieldsByBeat();
    /** 一站一段只允许一块的位(beat + "." + field)。 */
    private static final Set<String> SINGLETON_FIELDS = setOf(
            BEAT_BRIEF + ".question", BEAT_BRIEF + ".body",
            BEAT_ROUTE + ".walk", BEAT_ROUTE + ".todo",
            BEAT_ENTER + ".opener", BEAT_ENTER + ".aside",
            BEAT_OUTCOME + ".title", BEAT_OUTCOME + ".line", BEAT_OUTCOME + ".lineIfFail",
            BEAT_OUTCOME + ".open",
            BEAT_DELIVER + ".reward", BEAT_DELIVER + ".line", BEAT_UNLOCK + ".hook");
    /** 允许 when 条件的位(与 requires 同一套 cond)。 */
    private static final Set<String> WHEN_FIELDS = setOf(
            BEAT_BRIEF + ".carry", BEAT_OUTCOME + ".fact", BEAT_REVISIT + ".change");
    /** 允许 npcId 的段(旧 R11-A 口径:enter / outcome 两段才问「谁在说话」)。 */
    private static final Set<String> NPC_BEATS = setOf(BEAT_ENTER, BEAT_OUTCOME);

    static final int MAX_TEXT = 200;
    static final int MAX_CARRY = 8;
    static final int MAX_FACTS = 8;
    static final int MAX_CHANGES = 6;
    static final int MAX_QUESTIONS = 6;

    private static final Pattern QID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Object>> STORED_BLOCKS_TYPE = new TypeReference<List<Object>>() { };

    private ChapterNarrativeBlocks() {
    }

    /** 带 beat 的块 = 叙事块;beat 为空串/空白按没带处理(老块照旧)。 */
    public static boolean isNarrativeBlock(ChapterBlockDTO block) {
        return block != null && block.getBeat() != null && !block.getBeat().trim().isEmpty();
    }

    /**
     * 写侧校验:逐块形状 + 逐站的位唯一/条数/qid 成对/npcId 一致/deliver 必带 reward。
     * 与 {@code ChapterFlowCompiler} 的块形状校验同一处调用,失败即整个保存被打回。
     */
    public static void validateChapter(List<ChapterBlockDTO> blocks, int chapterNumber, int nodeCount) {
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        Map<Integer, Integer> nodeBlockAt = new HashMap<Integer, Integer>();
        for (int index = 0; index < blocks.size(); index++) {
            ChapterBlockDTO block = blocks.get(index);
            if (block != null && TYPE_NODE.equals(block.getType()) && block.getNodeIndex() != null) {
                nodeBlockAt.put(block.getNodeIndex(), Integer.valueOf(index));
            }
        }
        Map<Integer, NodeBeats> beatsByNode = new LinkedHashMap<Integer, NodeBeats>();
        for (int index = 0; index < blocks.size(); index++) {
            ChapterBlockDTO block = blocks.get(index);
            if (!isNarrativeBlock(block)) {
                continue;
            }
            validateBeatBlock(block, chapterNumber, index, nodeCount, nodeBlockAt, beatsByNode);
        }
        for (Map.Entry<Integer, NodeBeats> entry : beatsByNode.entrySet()) {
            validatePerNode(entry.getKey(), entry.getValue(), chapterNumber);
        }
    }

    private static void validateBeatBlock(ChapterBlockDTO block, int chapterNumber, int index, int nodeCount,
            Map<Integer, Integer> nodeBlockAt, Map<Integer, NodeBeats> beatsByNode) {
        String label = "第 " + (index + 1) + " 个块";
        String beat = block.getBeat().trim();
        if (!FIELDS_BY_BEAT.containsKey(beat)) {
            throw invalid(chapterNumber, label + "的 beat 不支持: " + block.getBeat());
        }
        if (!TYPE_TEXT.equals(block.getType())) {
            throw invalid(chapterNumber, label + "带 beat 时必须是 text 块");
        }
        String field = block.getField() == null ? "" : block.getField().trim();
        if (!FIELDS_BY_BEAT.get(beat).contains(field)) {
            throw invalid(chapterNumber, label + "的 beat=" + beat + " 不支持 field: " + block.getField());
        }
        String content = block.getContent() == null ? "" : block.getContent().trim();
        if (content.isEmpty()) {
            throw invalid(chapterNumber, label + "的叙事正文不能为空");
        }
        if (content.length() > MAX_TEXT) {
            throw invalid(chapterNumber, label + "的叙事正文不能超过 " + MAX_TEXT + " 字");
        }
        Integer nodeIndex = block.getNodeIndex();
        if (nodeIndex == null || nodeIndex < 0 || nodeIndex >= nodeCount) {
            throw invalid(chapterNumber, label + "的 nodeIndex 越界: " + nodeIndex);
        }
        Integer nodeBlockIndex = nodeBlockAt.get(nodeIndex);
        if (nodeBlockIndex == null) {
            throw invalid(chapterNumber, label + "引用的节点没有 node 块: nodeIndex=" + nodeIndex);
        }
        boolean postArrival = POST_ARRIVAL_BEATS.contains(beat);
        if (postArrival ? index < nodeBlockIndex.intValue() : index > nodeBlockIndex.intValue()) {
            throw invalid(chapterNumber, label + (postArrival ? "是到店后的叙事段,必须排在站点 node 块之后"
                    : "是到店前的叙事段,必须排在站点 node 块之前") + ": beat=" + beat);
        }

        NodeBeats node = beatsByNode.get(nodeIndex);
        if (node == null) {
            node = new NodeBeats();
            beatsByNode.put(nodeIndex, node);
        }
        String slot = beat + "." + field;
        if (SINGLETON_FIELDS.contains(slot) && node.singletonAt.put(slot, Integer.valueOf(index)) != null) {
            throw invalid(chapterNumber, label + "重复了本站 " + beat + " 的 " + field);
        }
        validateQid(block, chapterNumber, label, beat, field, node);
        validateNpcId(block, chapterNumber, label, beat, node);
        if (block.getWhen() != null) {
            if (!WHEN_FIELDS.contains(slot)) {
                throw invalid(chapterNumber, label + "的 beat=" + beat + " 的 " + field + " 不支持 when");
            }
            try {
                AdvancedGameConfigValidator.validateWhenCondition(block.getWhen(),
                        "第 " + chapterNumber + " 章" + label + "的 when");
            } catch (IllegalArgumentException invalidWhen) {
                throw invalid(chapterNumber, label + "的 when 不合法: " + invalidWhen.getMessage());
            }
        }
        if (block.getClaimLater() != null && !BEAT_DELIVER.equals(beat)) {
            throw invalid(chapterNumber, label + "只有 beat=deliver 能带 claimLater");
        }
        countRows(chapterNumber, label, beat, field, node);
    }

    private static void validateQid(ChapterBlockDTO block, int chapterNumber, String label, String beat,
            String field, NodeBeats node) {
        boolean qaField = BEAT_ENTER.equals(beat) && ("q".equals(field) || "a".equals(field));
        if (!qaField) {
            if (block.getQid() != null) {
                throw invalid(chapterNumber, label + "只有 beat=enter 的 q / a 能带 qid");
            }
            return;
        }
        String qid = block.getQid() == null ? "" : block.getQid().trim();
        if (!QID.matcher(qid).matches()) {
            throw invalid(chapterNumber, label + "的 qid 只能使用 1 至 32 位字母、数字、下划线或短横线");
        }
        Map<String, Integer> byQid = "q".equals(field) ? node.qAt : node.aAt;
        if (byQid.put(qid, Integer.valueOf(0)) != null) {
            throw invalid(chapterNumber, label + "的 qid 重复: " + qid);
        }
        if ("q".equals(field) && (++node.questions) > MAX_QUESTIONS) {
            throw invalid(chapterNumber, label + "所在站的问题超过 " + MAX_QUESTIONS + " 条");
        }
    }

    private static void validateNpcId(ChapterBlockDTO block, int chapterNumber, String label, String beat,
            NodeBeats node) {
        if (block.getNpcId() == null) {
            return;
        }
        if (block.getNpcId().longValue() <= 0L) {
            throw invalid(chapterNumber, label + "的 npcId 必须是正整数");
        }
        if (!NPC_BEATS.contains(beat)) {
            throw invalid(chapterNumber, label + "的 beat=" + beat + " 不支持 npcId");
        }
        Long previous = node.npcByBeat.get(beat);
        if (previous != null && !previous.equals(block.getNpcId())) {
            throw invalid(chapterNumber, label + "的 npcId 与本站 " + beat + " 段已有的 npcId 不一致");
        }
        node.npcByBeat.put(beat, block.getNpcId());
    }

    private static void countRows(int chapterNumber, String label, String beat, String field, NodeBeats node) {
        if (BEAT_BRIEF.equals(beat) && "carry".equals(field) && (++node.carry) > MAX_CARRY) {
            throw invalid(chapterNumber, label + "所在站的 brief 随身携带物超过 " + MAX_CARRY + " 条");
        }
        if (BEAT_OUTCOME.equals(beat) && "fact".equals(field) && (++node.facts) > MAX_FACTS) {
            throw invalid(chapterNumber, label + "所在站的 outcome 事实超过 " + MAX_FACTS + " 条");
        }
        if (BEAT_REVISIT.equals(beat) && "change".equals(field) && (++node.changes) > MAX_CHANGES) {
            throw invalid(chapterNumber, label + "所在站的 revisit 变化超过 " + MAX_CHANGES + " 条");
        }
        if (BEAT_DELIVER.equals(beat)) {
            node.hasDeliver = true;
            if ("reward".equals(field)) {
                node.hasDeliverReward = true;
            }
        }
    }

    private static void validatePerNode(int nodeIndex, NodeBeats node, int chapterNumber) {
        if (node.hasDeliver && !node.hasDeliverReward) {
            throw invalid(chapterNumber, "站点 nodeIndex=" + nodeIndex + " 配了 deliver 但没有 reward 块");
        }
        if (!node.qAt.keySet().equals(node.aAt.keySet())) {
            Set<String> paired = new LinkedHashSet<String>(node.qAt.keySet());
            paired.retainAll(node.aAt.keySet());
            Set<String> missing = new LinkedHashSet<String>(node.qAt.keySet());
            missing.addAll(node.aAt.keySet());
            missing.removeAll(paired);
            throw invalid(chapterNumber, "站点 nodeIndex=" + nodeIndex + " 的 enter 问答必须按 qid 成对,缺: " + missing);
        }
    }

    /**
     * 物化时把叙事块的可选字段写进存量行;正文与 type 由编译器写,nodeId 由编译器翻译后传入。
     * 缺省字段不写,老块结构不变。
     */
    public static void materializeRow(ChapterBlockDTO block, Long nodeId, Map<String, Object> row) {
        row.put(KEY_BEAT, block.getBeat().trim());
        row.put(KEY_FIELD, block.getField().trim());
        if (block.getQid() != null) {
            row.put(KEY_QID, block.getQid().trim());
        }
        if (block.getNpcId() != null) {
            row.put(KEY_NPC_ID, block.getNpcId());
        }
        if (block.getWhen() != null) {
            row.put(KEY_WHEN, block.getWhen());
        }
        if (block.getClaimLater() != null) {
            row.put(KEY_CLAIM_LATER, block.getClaimLater());
        }
        row.put(KEY_NODE_ID, nodeId);
    }

    /**
     * 读侧组装:存量 blocks → 每站一份旧契约形状的叙事段(brief / route / enter / outcome /
     * deliver / unlock / revisit),空段不写。块由写侧校验过,这里对坏数据只跳过、不抛
     * —— 冻结不能因为一段脏数据把整局建不出来。
     */
    public static Map<Long, ObjectNode> assembleByNode(String blocksJson) {
        Map<Long, ObjectNode> byNode = new LinkedHashMap<Long, ObjectNode>();
        List<Object> stored = parse(blocksJson);
        if (stored == null) {
            return byNode;
        }
        Map<Long, Map<String, ObjectNode>> questionsByNode = new LinkedHashMap<Long, Map<String, ObjectNode>>();
        for (Object item : stored) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> block = (Map<?, ?>) item;
            if (!TYPE_TEXT.equals(text(block.get(KEY_TYPE)))) {
                continue;
            }
            String beat = text(block.get(KEY_BEAT));
            if (!FIELDS_BY_BEAT.containsKey(beat)) {
                continue;
            }
            String field = text(block.get(KEY_FIELD));
            if (!FIELDS_BY_BEAT.get(beat).contains(field)) {
                continue;
            }
            Long nodeId = toLong(block.get(KEY_NODE_ID));
            if (nodeId == null || nodeId.longValue() <= 0L) {
                continue;
            }
            String content = text(block.get(KEY_CONTENT));
            if (content.isEmpty()) {
                continue;
            }
            ObjectNode node = byNode.get(nodeId);
            if (node == null) {
                node = JSON.createObjectNode();
                byNode.put(nodeId, node);
            }
            ObjectNode segment = node.has(beat) ? (ObjectNode) node.get(beat) : node.putObject(beat);
            applyRow(segment, beat, field, content, block, nodeId, questionsByNode);
            applySegmentExtras(segment, block);
        }
        return byNode;
    }

    private static void applyRow(ObjectNode segment, String beat, String field, String content, Map<?, ?> block,
            Long nodeId, Map<Long, Map<String, ObjectNode>> questionsByNode) {
        if (BEAT_BRIEF.equals(beat) && "carry".equals(field)) {
            conditionRow(sectionArray(segment, "carry"), block, content);
            return;
        }
        if (BEAT_OUTCOME.equals(beat) && "fact".equals(field)) {
            conditionRow(sectionArray(segment, "facts"), block, content);
            return;
        }
        if (BEAT_REVISIT.equals(beat) && "change".equals(field)) {
            conditionRow(sectionArray(segment, "changes"), block, content);
            return;
        }
        if (BEAT_ENTER.equals(beat) && ("q".equals(field) || "a".equals(field))) {
            String qid = text(block.get(KEY_QID));
            Map<String, ObjectNode> questions = questionsByNode.get(nodeId);
            if (questions == null) {
                questions = new LinkedHashMap<String, ObjectNode>();
                questionsByNode.put(nodeId, questions);
            }
            ObjectNode question = questions.get(qid);
            if (question == null) {
                question = JSON.createObjectNode();
                question.put("id", qid);
                questions.put(qid, question);
                sectionArray(segment, "questions").add(question);
            }
            question.put(field, content);
            return;
        }
        segment.put(field, content);
    }

    /** npcId 先出现者为准;claimLater 同。写侧已保证同段 npcId 一致。 */
    private static void applySegmentExtras(ObjectNode segment, Map<?, ?> block) {
        Long npcId = toLong(block.get(KEY_NPC_ID));
        if (npcId != null && npcId.longValue() > 0L && !segment.has(KEY_NPC_ID)) {
            segment.put(KEY_NPC_ID, npcId.longValue());
        }
        Object claimLater = block.get(KEY_CLAIM_LATER);
        if (claimLater instanceof Boolean && !segment.has(KEY_CLAIM_LATER)) {
            segment.put(KEY_CLAIM_LATER, ((Boolean) claimLater).booleanValue());
        }
    }

    /** 带 when 的条件行:when 缺省不写,保持旧契约「无条件」的读法。 */
    private static void conditionRow(ArrayNode rows, Map<?, ?> block, String content) {
        Object when = block.get(KEY_WHEN);
        ObjectNode entry = JSON.createObjectNode();
        entry.put("text", content);
        if (when != null) {
            entry.set("when", JSON.valueToTree(when));
        }
        rows.add(entry);
    }

    private static ArrayNode sectionArray(ObjectNode segment, String field) {
        if (segment.has(field) && segment.get(field).isArray()) {
            return (ArrayNode) segment.get(field);
        }
        return segment.putArray(field);
    }

    private static List<Object> parse(String blocksJson) {
        if (blocksJson == null || blocksJson.trim().isEmpty()) {
            return null;
        }
        try {
            return JSON.readValue(blocksJson, STORED_BLOCKS_TYPE);
        } catch (Exception unusableBlocks) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Long toLong(Object value) {
        if (value instanceof Number) {
            return Long.valueOf(((Number) value).longValue());
        }
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static ChapterFlowValidationException invalid(int chapterNumber, String detail) {
        return new ChapterFlowValidationException("第 " + chapterNumber + " 章故事流不合法: " + detail);
    }

    private static Map<String, Set<String>> fieldsByBeat() {
        Map<String, Set<String>> fields = new LinkedHashMap<String, Set<String>>();
        fields.put(BEAT_BRIEF, setOf("question", "body", "carry"));
        fields.put(BEAT_ROUTE, setOf("walk", "todo"));
        fields.put(BEAT_ENTER, setOf("opener", "aside", "q", "a"));
        fields.put(BEAT_OUTCOME, setOf("title", "line", "lineIfFail", "fact", "open"));
        fields.put(BEAT_DELIVER, setOf("reward", "line"));
        fields.put(BEAT_UNLOCK, setOf("hook"));
        fields.put(BEAT_REVISIT, setOf("change"));
        return Collections.unmodifiableMap(fields);
    }

    private static Set<String> setOf(String... values) {
        return new HashSet<String>(Arrays.asList(values));
    }

    /** 逐站的叙事累积:位唯一、条数、问答配对与 npcId 一致性都在这里记。 */
    private static final class NodeBeats {
        private final Map<String, Integer> singletonAt = new HashMap<String, Integer>();
        private final Map<String, Integer> qAt = new LinkedHashMap<String, Integer>();
        private final Map<String, Integer> aAt = new LinkedHashMap<String, Integer>();
        private final Map<String, Long> npcByBeat = new HashMap<String, Long>();
        private int carry;
        private int facts;
        private int changes;
        private int questions;
        private boolean hasDeliver;
        private boolean hasDeliverReward;
    }
}
