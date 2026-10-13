package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.dto.ChapterBlockDTO;
import com.chengyinhub.business.exception.ChapterFlowValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R18 契约层:故事流节点叙事块的写侧校验与读侧组装。
 *
 * <p>核心口径:叙事按站点(nodeId)存,同一段正文不因为模板/章节共用而串到别站;
 * 组装结果必须是 R8/R9 旧契约形状(运行期读代码零改动)。
 */
class ChapterNarrativeBlocksTest {

    private final ObjectMapper json = new ObjectMapper();

    // ==================== 读侧组装 ====================

    @Test
    void assemblesPerStationSegmentsInLegacyContractShape() throws Exception {
        String blocks = "["
                + "{\"type\":\"node\",\"nodeId\":101},"
                + "{\"type\":\"text\",\"content\":\"章节正文\"},"
                + "{\"type\":\"text\",\"beat\":\"brief\",\"field\":\"question\",\"content\":\"甲站委托?\",\"nodeId\":101},"
                + "{\"type\":\"text\",\"beat\":\"brief\",\"field\":\"carry\",\"content\":\"带伞\","
                + "\"when\":{\"var\":\"clue.rain\",\"op\":\"EQ\",\"value\":1},\"nodeId\":101},"
                + "{\"type\":\"text\",\"beat\":\"route\",\"field\":\"walk\",\"content\":\"约六分钟\",\"nodeId\":101},"
                + "{\"type\":\"text\",\"beat\":\"enter\",\"field\":\"opener\",\"content\":\"甲开场\",\"npcId\":9,\"nodeId\":101},"
                + "{\"type\":\"text\",\"beat\":\"enter\",\"field\":\"q\",\"qid\":\"night\",\"content\":\"来过吗?\",\"nodeId\":101},"
                + "{\"type\":\"text\",\"beat\":\"enter\",\"field\":\"a\",\"qid\":\"night\",\"content\":\"来过。\",\"nodeId\":101},"
                + "{\"type\":\"text\",\"beat\":\"outcome\",\"field\":\"line\",\"content\":\"甲结论\",\"npcId\":9,\"nodeId\":101},"
                + "{\"type\":\"text\",\"beat\":\"outcome\",\"field\":\"fact\",\"content\":\"事实一\",\"nodeId\":101},"
                + "{\"type\":\"text\",\"beat\":\"deliver\",\"field\":\"reward\",\"content\":\"纪念章\","
                + "\"claimLater\":true,\"nodeId\":101},"
                + "{\"type\":\"text\",\"beat\":\"revisit\",\"field\":\"change\",\"content\":\"灯换了\",\"nodeId\":101},"
                + "{\"type\":\"text\",\"beat\":\"unlock\",\"field\":\"hook\",\"content\":\"还有下文\",\"nodeId\":101},"
                + "{\"type\":\"image\",\"url\":\"/a.png\"},"
                + "{\"type\":\"text\",\"beat\":\"enter\",\"field\":\"opener\",\"content\":\"乙开场\",\"nodeId\":202}"
                + "]";

        Map<Long, ObjectNode> byNode = ChapterNarrativeBlocks.assembleByNode(blocks);

        assertEquals(2, byNode.size(), "只应有站的节点有叙事,node 块/图片/章节正文都不是叙事");
        JsonNode first = byNode.get(101L);
        assertEquals("甲站委托?", first.path("brief").path("question").asText());
        assertEquals("带伞", first.path("brief").path("carry").get(0).path("text").asText());
        assertEquals("clue.rain", first.path("brief").path("carry").get(0).path("when").path("var").asText());
        assertEquals("约六分钟", first.path("route").path("walk").asText());
        assertEquals(9L, first.path("enter").path("npcId").asLong());
        assertEquals("night", first.path("enter").path("questions").get(0).path("id").asText());
        assertEquals("来过吗?", first.path("enter").path("questions").get(0).path("q").asText());
        assertEquals("来过。", first.path("enter").path("questions").get(0).path("a").asText());
        assertEquals("甲结论", first.path("outcome").path("line").asText());
        assertEquals(9L, first.path("outcome").path("npcId").asLong());
        assertEquals("事实一", first.path("outcome").path("facts").get(0).path("text").asText());
        assertFalse(first.path("outcome").path("facts").get(0).has("when"), "when 缺省不写,读侧按无条件");
        assertEquals("纪念章", first.path("deliver").path("reward").asText());
        assertTrue(first.path("deliver").path("claimLater").asBoolean());
        assertEquals("灯换了", first.path("revisit").path("changes").get(0).path("text").asText());
        assertEquals("还有下文", first.path("unlock").path("hook").asText());

        JsonNode second = byNode.get(202L);
        assertEquals("乙开场", second.path("enter").path("opener").asText());
        assertFalse(second.has("brief"), "乙站没有 brief,不能因为同章共用就串到甲站台词");
        assertFalse(second.has("outcome"), "乙站没有 outcome,不得继承甲站结论");
        assertFalse(second.path("enter").has("npcId"), "npcId 是站内块的属性,不跨站");
    }

    @Test
    void assembleSkipsUnboundOrUnknownBlocksInsteadOfThrowing() {
        String blocks = "["
                + "{\"type\":\"text\",\"beat\":\"enter\",\"field\":\"opener\",\"content\":\"无归属\"},"
                + "{\"type\":\"text\",\"beat\":\"dance\",\"field\":\"opener\",\"content\":\"未知段\",\"nodeId\":1},"
                + "{\"type\":\"text\",\"beat\":\"enter\",\"field\":\"solo\",\"content\":\"未知位\",\"nodeId\":1},"
                + "{\"type\":\"text\",\"beat\":\"enter\",\"field\":\"opener\",\"content\":\"  \",\"nodeId\":1},"
                + "{broken";
        assertTrue(ChapterNarrativeBlocks.assembleByNode(blocks).isEmpty(), "坏数据只跳过,冻结不被打死");
        assertTrue(ChapterNarrativeBlocks.assembleByNode(null).isEmpty());
        assertTrue(ChapterNarrativeBlocks.assembleByNode("not-json").isEmpty());
    }

    // ==================== 写侧校验 ====================

    @Test
    void validChapterPasses() {
        List<ChapterBlockDTO> blocks = Arrays.asList(
                beat("brief", "question", 0, "委托"),
                beat("enter", "opener", 0, "开场"),
                beat("enter", "q", 0, "问?", "night"),
                beat("enter", "a", 0, "答。", "night"),
                nodeBlock(0),
                beat("outcome", "line", 0, "结论"),
                beat("outcome", "fact", 0, "事实"),
                beat("deliver", "reward", 0, "章"),
                beat("revisit", "change", 0, "变化"),
                nodeBlock(1));
        ChapterNarrativeBlocks.validateChapter(blocks, 1, 2);
    }

    @Test
    void positionIsEnforcedByArrivalSide() {
        ChapterNarrativeBlocks.validateChapter(Arrays.asList(
                beat("enter", "opener", 0, "开场"), nodeBlock(0), beat("outcome", "line", 0, "结论")), 1, 1);
        ChapterFlowValidationException enterLate = assertThrows(ChapterFlowValidationException.class,
                () -> ChapterNarrativeBlocks.validateChapter(Arrays.asList(
                        nodeBlock(0), beat("enter", "opener", 0, "开场")), 1, 1));
        assertTrue(enterLate.getMessage().contains("到店前"), enterLate.getMessage());
        ChapterFlowValidationException outcomeEarly = assertThrows(ChapterFlowValidationException.class,
                () -> ChapterNarrativeBlocks.validateChapter(Arrays.asList(
                        beat("outcome", "line", 0, "结论"), nodeBlock(0)), 1, 1));
        assertTrue(outcomeEarly.getMessage().contains("到店后"), outcomeEarly.getMessage());
    }

    @Test
    void shapeErrorsAreRejected() {
        assertRejected("beat 不支持", beat("dance", "opener", 0, "x"));
        ChapterBlockDTO wrongType = beat("enter", "opener", 0, "x");
        wrongType.setType("image");
        assertRejected("必须是 text", wrongType);
        assertRejected("不支持 field", beat("enter", "solo", 0, "x"));
        assertRejected("不能为空", beat("enter", "opener", 0, "   "));
        assertRejected("不能超过 200", beat("enter", "opener", 0, repeat("长", 201)));
        assertRejected("nodeIndex 越界", 0, beat("enter", "opener", 0, "x"));
        ChapterBlockDTO unbound = beat("enter", "opener", 0, "x");
        unbound.setNodeIndex(null);
        assertRejected("nodeIndex 越界", unbound);
    }

    @Test
    void stationLevelRulesAreRejected() {
        ChapterBlockDTO duplicate = beat("enter", "opener", 0, "第二遍");
        assertRejected("重复了本站", beat("enter", "opener", 0, "第一遍"), duplicate, nodeBlock(0));

        ChapterBlockDTO qOnly = beat("enter", "q", 0, "问?", "night");
        assertRejected("成对", qOnly, nodeBlock(0));
        ChapterBlockDTO answerWithoutQ = beat("enter", "a", 0, "答。", "night");
        assertRejected("成对", answerWithoutQ, nodeBlock(0));
        ChapterBlockDTO noQid = beat("enter", "q", 0, "问?");
        assertRejected("qid", noQid, nodeBlock(0));
        ChapterBlockDTO qidElsewhere = beat("brief", "question", 0, "委托");
        qidElsewhere.setQid("night");
        assertRejected("能带 qid", qidElsewhere, nodeBlock(0));

        ChapterBlockDTO npcFirst = beat("enter", "opener", 0, "开场");
        npcFirst.setNpcId(9L);
        ChapterBlockDTO npcSecond = beat("enter", "aside", 0, "旁白");
        npcSecond.setNpcId(10L);
        assertRejected("不一致", npcFirst, npcSecond, nodeBlock(0));
        ChapterBlockDTO zeroNpc = beat("enter", "opener", 0, "开场");
        zeroNpc.setNpcId(0L);
        assertRejected("正整数", zeroNpc, nodeBlock(0));
        ChapterBlockDTO npcOnBrief = beat("brief", "question", 0, "委托");
        npcOnBrief.setNpcId(9L);
        assertRejected("不支持 npcId", npcOnBrief, nodeBlock(0));

        ChapterBlockDTO whenOnLine = beat("outcome", "line", 0, "结论");
        whenOnLine.setWhen(json.createObjectNode().put("var", "clue.a").put("op", "EQ").put("value", 1));
        assertRejected("不支持 when", nodeBlock(0), whenOnLine);
        ChapterBlockDTO badWhen = beat("outcome", "fact", 0, "事实");
        badWhen.setWhen(json.createObjectNode().put("var", "clue.a").put("op", "MAYBE").put("value", 1));
        assertRejected("when 不合法", nodeBlock(0), badWhen);

        ChapterBlockDTO claimOnBrief = beat("brief", "question", 0, "委托");
        claimOnBrief.setClaimLater(Boolean.TRUE);
        assertRejected("只有 beat=deliver", claimOnBrief, nodeBlock(0));
        assertRejected("没有 reward", nodeBlock(0), beat("deliver", "line", 0, "交付语"));
    }

    @Test
    void rowCountCapsAreRejected() {
        List<ChapterBlockDTO> carry = new ArrayList<ChapterBlockDTO>();
        for (int i = 0; i < ChapterNarrativeBlocks.MAX_CARRY + 1; i++) {
            carry.add(beat("brief", "carry", 0, "携带物" + i));
        }
        carry.add(nodeBlock(0));
        ChapterFlowValidationException error = assertThrows(ChapterFlowValidationException.class,
                () -> ChapterNarrativeBlocks.validateChapter(carry, 1, 1));
        assertTrue(error.getMessage().contains("随身携带物"), error.getMessage());
    }

    // ==================== 物化行 ====================

    @Test
    void materializeRowCarriesOnlyConfiguredOptionals() {
        ChapterBlockDTO block = beat("enter", "q", 0, "问?", "night");
        block.setNpcId(9L);
        Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
        ChapterNarrativeBlocks.materializeRow(block, 101L, row);
        assertEquals("enter", row.get("beat"));
        assertEquals("q", row.get("field"));
        assertEquals("night", row.get("qid"));
        assertEquals(9L, row.get("npcId"));
        assertEquals(101L, row.get("nodeId"));
        assertFalse(row.containsKey("when"));
        assertFalse(row.containsKey("claimLater"));
    }

    private void assertRejected(String messagePart, ChapterBlockDTO... blocks) {
        assertRejected(messagePart, 1, blocks);
    }

    private void assertRejected(String messagePart, int nodeCount, ChapterBlockDTO... blocks) {
        ChapterFlowValidationException error = assertThrows(ChapterFlowValidationException.class,
                () -> ChapterNarrativeBlocks.validateChapter(Arrays.asList(blocks), 1, nodeCount));
        assertTrue(error.getMessage().contains(messagePart),
                "期望命中「" + messagePart + "」,实际:" + error.getMessage());
    }

    private ChapterBlockDTO beat(String beat, String field, int nodeIndex, String content) {
        ChapterBlockDTO block = new ChapterBlockDTO();
        block.setType("text");
        block.setBeat(beat);
        block.setField(field);
        block.setNodeIndex(Integer.valueOf(nodeIndex));
        block.setContent(content);
        return block;
    }

    private ChapterBlockDTO beat(String beat, String field, int nodeIndex, String content, String qid) {
        ChapterBlockDTO block = beat(beat, field, nodeIndex, content);
        block.setQid(qid);
        return block;
    }

    private ChapterBlockDTO nodeBlock(int nodeIndex) {
        ChapterBlockDTO block = new ChapterBlockDTO();
        block.setType("node");
        block.setNodeIndex(Integer.valueOf(nodeIndex));
        return block;
    }

    private static String repeat(String value, int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append(value);
        }
        return text.toString();
    }
}
