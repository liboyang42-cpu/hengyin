package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * R11-A:检定动作 id 的唯一定义 —— B5 写回执与 NARRATIVE 读回执都从这里取,
 * 格式一旦漂移,两处对不上是静默失效(失败文案永远不生效)。
 */
class JourneyCheckActionIdTest {

    @Test
    void formatIsTheOneBothB5AndNarrativeRead() {
        assertEquals("check:101:ask_lin:settle", JourneyCheckActionId.of(101L, " ask_lin ", "settle"));
        assertEquals("check:101:ask_lin:settle", JourneyCheckActionId.settle(101L, "ask_lin"));
    }

    @Test
    void invalidArgumentsRejected() {
        assertThrows(IllegalArgumentException.class, () -> JourneyCheckActionId.of(0L, "ask", "settle"));
        assertThrows(IllegalArgumentException.class, () -> JourneyCheckActionId.of(101L, "带空格 的", "settle"));
        assertThrows(IllegalArgumentException.class,
                () -> JourneyCheckActionId.of(101L, longId(100), "settle"));
    }

    /**
     * R22-SEC S12:合法最长值(19 位 nodeId + 64 位 checkId)必须被接受,不能 off-by-one 拒真。
     *
     * <p>负控:MAX_LENGTH 改回 96,本用例立刻红(抛「检定标识过长」)。
     */
    @Test
    void maxLegalLengthIsAccepted() {
        String id = JourneyCheckActionId.settle(Long.MAX_VALUE, longId(64));

        assertEquals("check:" + Long.MAX_VALUE + ":" + longId(64) + ":settle", id);
        assertEquals(97, id.length(), "check:(6) + 19 + :(1) + 64 + :(1) + settle(6)");
    }

    private static String longId(int length) {
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < length; i++) id.append('x');
        return id.toString();
    }
}
