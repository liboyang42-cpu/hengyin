package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P-02:老文字问答按编辑器提示「可填多个，用、分隔」填写时,任一项都能过。 */
class BasicTextAnswerTest {

    @Test
    void anyListedAlternativePasses() {
        String authored = "7、七、第七根、第7根";
        assertTrue(BasicTextAnswer.matches(authored, "7"));
        assertTrue(BasicTextAnswer.matches(authored, "七"));
        assertTrue(BasicTextAnswer.matches(authored, " 第七根 "));
        assertTrue(BasicTextAnswer.matches(authored, "第７根"));
        assertFalse(BasicTextAnswer.matches(authored, "6"));
    }

    @Test
    void commasSplitToo() {
        assertTrue(BasicTextAnswer.matches("吴淞江,苏州河", "苏州河"));
        assertTrue(BasicTextAnswer.matches("吴淞江，苏州河", "苏州河"));
    }

    @Test
    void singleAnswerAndBlanksKeepTheOldRule() {
        assertTrue(BasicTextAnswer.matches("Bund", "bund"));
        assertTrue(BasicTextAnswer.matches("外 滩", "外滩"));
        assertFalse(BasicTextAnswer.matches("外滩", ""));
        assertFalse(BasicTextAnswer.matches("", "外滩"));
        assertFalse(BasicTextAnswer.matches(null, "外滩"));
        assertFalse(BasicTextAnswer.matches("、、", "、"));
    }
}
