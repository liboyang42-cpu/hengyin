package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 整局 N 的产品规则（不是迎合实现的期望）：
 * <ul>
 *   <li>N 是**整局唯一值**：根层、每个 {@code nodeConfigs}、每个 {@code nodeAnswers}（含嵌套
 *       {@code advancedConfigJson}）里所有显式声明必须一致，任何两处不同即冲突。</li>
 *   <li>根层标量**不能**掩盖节点上的不同声明；直层字段**不能**掩盖嵌套里的不同声明。</li>
 *   <li>没有显式 N 但有 {@code hintLadder} 的新局 → 整局默认 0；真正旧局（无 N 无阶梯）→ null。</li>
 *   <li>节点没有声明时只继承整局值，不会被强加 0 制造假冲突。</li>
 * </ul>
 * 客户端永不参与取值。
 */
class PlayHelpRunQuotaPolicyTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    // ---------------------------------------------------------------- 冲突（核心漏洞）

    /** 已实证漏洞：根层 3 + 节点 9 曾直接返回 3，把节点的不同声明静默吞掉。 */
    @Test
    void rootScalarAndNodeDeclarationDisagreeingIsAConflict() throws Exception {
        JsonNode snapshot = JSON.readTree("{\"hintFreeUsesPerRun\":3,"
                + "\"nodeConfigs\":{\"31\":{\"hintFreeUsesPerRun\":9}}}");

        IllegalArgumentException conflict = assertThrows(IllegalArgumentException.class,
                () -> PlayHelpRunQuotaPolicy.runFreeUses(snapshot));
        assertTrue(conflict.getMessage().startsWith(PlayHelpRunQuotaPolicy.REASON_DECLARED_CONFLICT));
    }

    /** 声明一致时不是冲突：根层 3 + 节点 3 仍是整局 3。 */
    @Test
    void rootScalarAgreeingWithNodeDeclarationIsAccepted() throws Exception {
        JsonNode snapshot = JSON.readTree("{\"hintFreeUsesPerRun\":3,"
                + "\"nodeConfigs\":{\"31\":{\"hintFreeUsesPerRun\":3}}}");

        assertEquals(3, PlayHelpRunQuotaPolicy.runFreeUses(snapshot).intValue());
    }

    /** 已实证漏洞：节点冻结答案的直层字段掩盖 nested 冲突。 */
    @Test
    void frozenAnswerDirectLayerAndNestedConfigDisagreeingIsAConflict() throws Exception {
        JsonNode frozen = JSON.readTree("{\"hintFreeUsesPerRun\":3,\"advancedConfigJson\":"
                + "\"{\\\"hintFreeUsesPerRun\\\":9}\"}");

        assertThrows(IllegalArgumentException.class,
                () -> PlayHelpRunQuotaPolicy.frozenUses(frozen));
    }

    /** 同一份节点答案里直层与嵌套一致时正常取值。 */
    @Test
    void frozenAnswerDirectLayerAgreeingWithNestedConfigIsAccepted() throws Exception {
        JsonNode frozen = JSON.readTree("{\"hintFreeUsesPerRun\":4,\"advancedConfigJson\":"
                + "\"{\\\"hintFreeUsesPerRun\\\":4}\"}");

        assertEquals(4, PlayHelpRunQuotaPolicy.frozenUses(frozen).intValue());
    }

    /** 不同节点各自声明不同值同样是冲突，且与是否经过根层无关。 */
    @Test
    void distinctPerNodeDeclarationsAreAConflictNotAPick() throws Exception {
        JsonNode snapshot = JSON.readTree("{\"nodeConfigs\":{\"31\":{\"hintFreeUsesPerRun\":2},"
                + "\"32\":{\"hintFreeUsesPerRun\":3}}}");

        IllegalArgumentException conflict = assertThrows(IllegalArgumentException.class,
                () -> PlayHelpRunQuotaPolicy.runFreeUses(snapshot));
        assertTrue(conflict.getMessage().startsWith(PlayHelpRunQuotaPolicy.REASON_DECLARED_CONFLICT));
    }

    /** nodeConfigs 与 nodeAnswers 两处（含嵌套串）也必须一致。 */
    @Test
    void conflictsAcrossNodeConfigsAndNestedNodeAnswersAreDetected() throws Exception {
        JsonNode snapshot = JSON.readTree("{\"nodeConfigs\":{\"31\":{\"hintFreeUsesPerRun\":2}},"
                + "\"nodeAnswers\":{\"31\":{\"advancedConfigJson\":"
                + "\"{\\\"hintFreeUsesPerRun\\\":5}\"}}}");

        assertThrows(IllegalArgumentException.class,
                () -> PlayHelpRunQuotaPolicy.runFreeUses(snapshot));
    }

    // ---------------------------------------------------------------- 默认值规则

    /** 新阶梯但没写 N：整局默认 0（新规则不送免费次数），不是 null。 */
    @Test
    void ladderWithoutExplicitNDefaultsToZero() throws Exception {
        JsonNode snapshot = JSON.readTree("{\"nodeAnswers\":{\"31\":{\"hintLadder\":"
                + "[{\"level\":1,\"type\":\"TEXT\"}]}}}");

        assertEquals(0, PlayHelpRunQuotaPolicy.runFreeUses(snapshot).intValue());
    }

    /** 根层阶梯也一样触发默认 0。 */
    @Test
    void rootLadderWithoutExplicitNAlsoDefaultsToZero() throws Exception {
        JsonNode snapshot = JSON.readTree("{\"hintLadder\":[{\"level\":1,\"type\":\"TEXT\"}]}");

        assertEquals(0, PlayHelpRunQuotaPolicy.runFreeUses(snapshot).intValue());
    }

    /** 真正的旧局（无 N、无阶梯）才是 null：调用方继续走逐层旧语义。 */
    @Test
    void runWithoutNOrLadderIsOldBehaviour() throws Exception {
        assertNull(PlayHelpRunQuotaPolicy.runFreeUses(JSON.readTree("{\"nodeConfigs\":{}}")));
        assertNull(PlayHelpRunQuotaPolicy.runFreeUses(JSON.readTree("{\"qa\":{\"enabled\":true}}")));
        assertNull(PlayHelpRunQuotaPolicy.runFreeUses(null));
    }

    /** 显式 0 就是 0，不会被当成"没配"再去套默认。 */
    @Test
    void explicitZeroIsStillZero() throws Exception {
        assertEquals(0, PlayHelpRunQuotaPolicy.runFreeUses(
                JSON.readTree("{\"hintFreeUsesPerRun\":0,"
                        + "\"hintLadder\":[{\"level\":1,\"type\":\"TEXT\"}]}")).intValue());
    }

    // ---------------------------------------------------------------- 只继承，不制造假冲突

    /** 节点没有声明 N 时只继承整局值：不因为缺字段就强加 0 与之冲突。 */
    @Test
    void omittedRowsInheritTheRunValueWithoutFalseConflict() throws Exception {
        JsonNode snapshot = JSON.readTree("{\"nodeConfigs\":{\"31\":{\"hintFreeUsesPerRun\":2},"
                + "\"32\":{\"qa\":{\"enabled\":true}},\"33\":{}},\"nodeAnswers\":{\"32\":{}}}");

        assertEquals(2, PlayHelpRunQuotaPolicy.runFreeUses(snapshot).intValue());
    }

    /** 空 hintLadder 数组等同没配阶梯：不算"新阶梯局"。 */
    @Test
    void emptyLadderArrayDoesNotTriggerTheNewRuleDefault() throws Exception {
        assertNull(PlayHelpRunQuotaPolicy.runFreeUses(
                JSON.readTree("{\"hintLadder\":[],\"nodeConfigs\":{}}")));
    }

    // ---------------------------------------------------------------- 取值与 fail-closed

    @Test
    void realFrozenBlockConfigUnderNodeConfigsIsRead() throws Exception {
        JsonNode snapshot = JSON.readTree("{\"nodeConfigs\":{\"31\":{\"hintFreeUsesPerRun\":2}},"
                + "\"nodeAnswers\":{\"31\":{\"contentSnapshotVersion\":1}}}");

        assertEquals(2, PlayHelpRunQuotaPolicy.runFreeUses(snapshot).intValue());
        // 整局值对每个节点都一样：不允许按节点各自初始化一个 N。
        assertEquals(2, PlayHelpRunQuotaPolicy.fromSnapshot(snapshot, 31).intValue());
        assertEquals(2, PlayHelpRunQuotaPolicy.fromSnapshot(snapshot, 32).intValue());
    }

    @Test
    void nestedAdvancedConfigJsonIsReadWhenDirectLayerAbsent() throws Exception {
        JsonNode frozen = JSON.readTree("{\"advancedConfigJson\":\"{\\\"hintFreeUsesPerRun\\\":2}\"}");
        assertEquals(2, PlayHelpRunQuotaPolicy.frozenUses(frozen).intValue());
    }

    @Test
    void absentFieldInNodeAnswerMeansNoDeclaration() throws Exception {
        JsonNode frozen = JSON.readTree("{\"hintLadder\":[{\"level\":1,\"type\":\"TEXT\"}]}");
        assertNull(PlayHelpRunQuotaPolicy.frozenUses(frozen));
        assertNull(PlayHelpRunQuotaPolicy.frozenUses(null));
        assertNull(PlayHelpRunQuotaPolicy.fromSnapshot(null, 31));
    }

    @Test
    void negativeFractionalAndOversizedValuesAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> PlayHelpRunQuotaPolicy.frozenUses(JSON.readTree("{\"hintFreeUsesPerRun\":-1}")));
        assertThrows(IllegalArgumentException.class,
                () -> PlayHelpRunQuotaPolicy.frozenUses(JSON.readTree("{\"hintFreeUsesPerRun\":1.5}")));
        assertThrows(IllegalArgumentException.class,
                () -> PlayHelpRunQuotaPolicy.frozenUses(JSON.readTree("{\"hintFreeUsesPerRun\":"
                        + (PlayHelpRunQuotaPolicy.MAX_RUN_FREE_USES + 1) + "}")));
        assertThrows(IllegalArgumentException.class,
                () -> PlayHelpRunQuotaPolicy.runFreeUses(JSON.readTree(
                        "{\"nodeConfigs\":{\"31\":{\"hintFreeUsesPerRun\":-2}}}")));
    }

    @Test
    void brokenFrozenConfigFailsClosed() throws Exception {
        JsonNode frozen = JSON.readTree("{\"advancedConfigJson\":\"{not-json\"}");
        assertThrows(IllegalStateException.class, () -> PlayHelpRunQuotaPolicy.frozenUses(frozen));
        assertThrows(IllegalStateException.class, () -> PlayHelpRunQuotaPolicy.runFreeUses(
                JSON.readTree("{\"nodeAnswers\":{\"31\":{\"advancedConfigJson\":\"{not-json\"}}}")));
    }

    @Test
    void requireConfiguredRejectsNullAndOutOfRange() {
        assertEquals(2, PlayHelpRunQuotaPolicy.requireConfigured(2));
        assertEquals(0, PlayHelpRunQuotaPolicy.requireConfigured(0));
        assertThrows(IllegalArgumentException.class, () -> PlayHelpRunQuotaPolicy.requireConfigured(null));
        assertThrows(IllegalArgumentException.class, () -> PlayHelpRunQuotaPolicy.requireConfigured(-1));
        assertThrows(IllegalArgumentException.class, () -> PlayHelpRunQuotaPolicy.requireConfigured(
                PlayHelpRunQuotaPolicy.MAX_RUN_FREE_USES + 1));
    }
}
