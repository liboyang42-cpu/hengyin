package com.chengyinhub.business.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B1:StateKeyRefs.collect 按路线图写法的全部来源 + 空/老配置。 */
class StateKeyRefsTest {

    @Test
    void collectsEveryRoadmapStyleSource() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"requires\":[{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1},"
                + "{\"op\":\"HAS_TAG\",\"value\":\"tag.entered_once\"},"
                + "{\"op\":\"NODE_COMPLETED\",\"nodeId\":123}],"
                + "\"effects\":[{\"op\":\"INC\",\"var\":\"counter.coins\",\"value\":2},"
                + "{\"op\":\"ADD_TAG\",\"value\":\"tag.hurried\"}],"
                + "\"check\":{\"enabled\":true,\"checkId\":\"ask_lin\","
                + "\"mods\":[{\"when\":{\"var\":\"counter.luck\",\"op\":\"GT\",\"value\":0},\"value\":1}],"
                + "\"advantageIf\":[{\"var\":\"relation.lin_trust\",\"op\":\"GTE\",\"value\":2}],"
                + "\"disadvantageIf\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.rushed\"}],"
                + "\"successEffects\":[{\"op\":\"SET\",\"var\":\"clue.torn_note\",\"value\":1}],"
                + "\"failEffects\":[{\"op\":\"ADD_TAG\",\"value\":\"tag.guarded\"}],"
                + "\"failCostTag\":\"tag.weary\"}}";

        assertEquals(set("clue.ledger_page", "tag.entered_once",
                "counter.coins", "tag.hurried",
                "counter.luck", "relation.lin_trust", "tag.rushed",
                "clue.torn_note", "tag.guarded", "tag.weary"),
                StateKeyRefs.collect(json));
    }

    /** NODE_COMPLETED 问的是节点做过没有,不引用任何状态键 —— 混在 requires 里也不能收集。 */
    @Test
    void nodeCompletedAloneCollectsNothing() {
        assertTrue(StateKeyRefs.collect(
                "{\"requires\":[{\"op\":\"NODE_COMPLETED\",\"nodeId\":123}]}").isEmpty());
    }

    @Test
    void emptyAndBlankConfigCollectNothing() {
        assertTrue(StateKeyRefs.collect(null).isEmpty());
        assertTrue(StateKeyRefs.collect("").isEmpty());
        assertTrue(StateKeyRefs.collect("   ").isEmpty());
    }

    @Test
    void legacyConfigWithoutNewFieldsCollectsNothing() {
        assertTrue(StateKeyRefs.collect("{\"schemaVersion\":1,\"timer\":{\"enabled\":true}}").isEmpty());
    }

    @Test
    void wrongShapesAreIgnoredNotFatal() {
        String json = "{\"requires\":\"clue.x\",\"effects\":[{\"var\":3,\"op\":\"SET\"}],"
                + "\"check\":{\"mods\":[{\"nokey\":1}],\"failCostTag\":\"\"}}";
        assertTrue(StateKeyRefs.collect(json).isEmpty());
    }

    @Test
    void malformedJsonFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> StateKeyRefs.collect("{not json"));
    }

    /**
     * R8 叙事段的 when 也是引用:outcome.facts[] 与 revisit.changes[] 都必须进发布闸对账,
     * 否则「配了条件但键没注册」会静默上线。
     *
     * <p>负控:注释掉 StateKeyRefs.collect 里的 collectRowConditions 两行,本用例立刻红。
     */
    @Test
    void collectsNarrativeSectionConditions() {
        String json = "{\"schemaVersion\":1,"
                + "\"outcome\":{\"title\":\"账本的夜\",\"facts\":["
                + "{\"text\":\"无条件\"},"
                + "{\"when\":{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1},\"text\":\"翻到过\"},"
                + "{\"when\":{\"op\":\"NODE_COMPLETED\",\"nodeId\":9},\"text\":\"做过\"}]},"
                + "\"revisit\":{\"changes\":["
                + "{\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"},\"text\":\"上回跑着来\"},"
                + "{\"when\":{\"var\":\"relation.lin_trust\",\"op\":\"GT\",\"value\":0},\"text\":\"熟了些\"},"
                + "{\"when\":null,\"text\":\"一直是这样\"}]}}";

        assertEquals(set("clue.ledger_page", "tag.hurried", "relation.lin_trust"),
                StateKeyRefs.collect(json));
    }

    /** 叙事段形状不对(when 不是对象、数组不是数组)一律按「没引用」处理,不能炸。 */
    @Test
    void wrongNarrativeShapesAreIgnoredNotFatal() {
        assertTrue(StateKeyRefs.collect("{\"outcome\":{\"facts\":\"clue.x\"},"
                + "\"revisit\":{\"changes\":[{\"when\":\"tag.x\"}]}}").isEmpty());
        assertTrue(StateKeyRefs.collect("{\"outcome\":\"clue.x\",\"revisit\":[1,2]}").isEmpty());
    }

    /**
     * R9:brief.carry[].when 也是引用,必须进发布闸对账 ——
     * 漏了它,「配了条件但没注册键」的随身携带物会静默过闸。
     *
     * <p>负控:注释掉 StateKeyRefs.collect 里的
     * collectRowConditions(root.get("brief"), "carry", keys) 一行,本用例立刻红。
     */
    @Test
    void collectsBriefCarryConditions() {
        String json = "{\"schemaVersion\":1,"
                + "\"brief\":{\"question\":\"账本上写着什么\",\"body\":\"替我去柜台取那本账\","
                + "\"carry\":[{\"text\":\"无条件\"},"
                + "{\"when\":{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1},\"text\":\"带上那一页\"},"
                + "{\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"},\"text\":\"路上慢一点\"},"
                + "{\"when\":{\"op\":\"NODE_COMPLETED\",\"nodeId\":9},\"text\":\"做过\"},"
                + "{\"when\":null,\"text\":\"一直带着\"}]}}";

        assertEquals(set("clue.ledger_page", "tag.hurried"), StateKeyRefs.collect(json));
    }

    /** brief 形状不对(carry 不是数组、整段不是对象)一律按「没引用」处理,不能炸。 */
    @Test
    void wrongBriefShapesAreIgnoredNotFatal() {
        assertTrue(StateKeyRefs.collect("{\"brief\":{\"carry\":\"clue.x\"}}").isEmpty());
        assertTrue(StateKeyRefs.collect("{\"brief\":[1,2]}").isEmpty());
    }

    /**
     * R19:叙事搬进章节故事流后,beat 块上的 when 是真源,必须收进发布闸对账;
     * 非 beat 块的 when 不是真源(写侧也不允许),不收。
     *
     * <p>负控:把 collectChapterBlocks 改成直接 return keys,本用例立刻红。
     */
    @Test
    void collectsChapterStoryFlowBeatConditions() {
        String blocks = "["
                + "{\"type\":\"text\",\"content\":\"开场旁白\"},"
                + "{\"type\":\"node\",\"nodeId\":501},"
                + "{\"type\":\"text\",\"beat\":\"brief\",\"field\":\"carry\",\"nodeId\":501,"
                + "\"content\":\"带上那一页\",\"when\":{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1}},"
                + "{\"type\":\"text\",\"beat\":\"outcome\",\"field\":\"fact\",\"nodeId\":501,"
                + "\"content\":\"翻到过\",\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"}},"
                + "{\"type\":\"text\",\"beat\":\"revisit\",\"field\":\"change\",\"nodeId\":501,"
                + "\"content\":\"做过\",\"when\":{\"op\":\"NODE_COMPLETED\",\"nodeId\":9}},"
                + "{\"type\":\"text\",\"beat\":\"unlock\",\"field\":\"hook\",\"nodeId\":501,"
                + "\"content\":\"无条件的引子\"},"
                + "{\"type\":\"text\",\"content\":\"旁白\",\"when\":{\"var\":\"clue.not_real\",\"op\":\"EQ\",\"value\":1}}"
                + "]";

        assertEquals(set("clue.ledger_page", "tag.hurried"), StateKeyRefs.collectChapterBlocks(blocks));
    }

    /**
     * R22-SEC S14:空 / 不是数组按「没引用」处理(读侧对脏块只跳过的口径);坏 JSON 抛 ——
     * 发布闸 / 删除前查引用要 fail-closed,读不出引用不能当成没有引用。
     *
     * <p>负控:collectChapterBlocks 的 readTree catch 改回 return keys,最后一行立刻红。
     */
    @Test
    void brokenChapterBlocksCollectNothingButBadJsonThrows() {
        assertTrue(StateKeyRefs.collectChapterBlocks(null).isEmpty());
        assertTrue(StateKeyRefs.collectChapterBlocks("   ").isEmpty());
        assertTrue(StateKeyRefs.collectChapterBlocks("{\"beat\":\"brief\"}").isEmpty());
        assertTrue(StateKeyRefs.collectChapterBlocks("[1,\"x\",null]").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> StateKeyRefs.collectChapterBlocks("{broken"));
    }

    private static Set<String> set(String... values) {
        return new LinkedHashSet<String>(Arrays.asList(values));
    }
}
