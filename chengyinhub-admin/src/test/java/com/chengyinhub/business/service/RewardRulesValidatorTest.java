package com.chengyinhub.business.service;

import com.chengyinhub.business.service.support.RewardRulesValidator;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 条件奖励:让商家能配「达到某个结果才发这份奖」。
 *
 * <p>原来奖励是三个平铺列、语义写死成「完成即发」,没有条件位。
 * 估数分三档、比价按连对几个分档、竞猜要猜中才发 —— 这些都得有地方挂条件。
 */
class RewardRulesValidatorTest {

    private final RewardRulesValidator validator = new RewardRulesValidator();

    @Test
    void acceptsRulesKeyedByOutcomeCode() {
        String normalized = validator.validateAndNormalize(
                "[{\"outcomeCode\":\"ESTIMATE_HIT\",\"couponId\":123,\"xp\":50},"
                        + "{\"outcomeCode\":\"ESTIMATE_CLOSE\",\"xp\":20}]");

        assertNotNull(normalized);
        assertTrue(normalized.contains("ESTIMATE_HIT"));
        assertTrue(normalized.contains("ESTIMATE_CLOSE"));
    }

    @Test
    void emptyOrBlankMeansNoConditionalReward() {
        assertNull(validator.validateAndNormalize(null));
        assertNull(validator.validateAndNormalize(""));
        assertNull(validator.validateAndNormalize("[]"));
    }

    @Test
    void rejectsDuplicateOutcomeCodeBecauseNobodyCouldSayHowManyToGrant() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(
                        "[{\"outcomeCode\":\"HIT\",\"xp\":10},{\"outcomeCode\":\"HIT\",\"xp\":20}]"));
        assertTrue(thrown.getMessage().contains("只能配一份"), thrown.getMessage());
    }

    @Test
    void rejectsCodeShapesThatTheFrontEndContractWouldNotProduce() {
        // 前后端保留旧模板已有结果码，但仍拒绝超长、空白和非法字符。
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize("[{\"outcomeCode\":\"hit a\",\"xp\":10}]"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize("[{\"outcomeCode\":\"\",\"xp\":10}]"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize("[{\"outcomeCode\":\"结果\",\"xp\":10}]"));
    }

    @Test
    void legacyCodesKeepExactCaseAndRewardBinding() {
        String saved = validator.validateAndNormalize("[{\"outcomeCode\":\"finish_a\",\"xp\":10},"
                + "{\"outcomeCode\":\"9-end\",\"xp\":20}]");
        assertEquals(10, validator.ruleFor(saved, "finish_a").path("xp").asInt());
        assertEquals(20, validator.ruleFor(saved, "9-end").path("xp").asInt());
        assertNull(validator.ruleFor(saved, "FINISH_A"));
    }

    /** 配了奖却什么都不发,是商家最容易犯又最难自查的一种配置事故。 */
    @Test
    void rejectsARuleThatGrantsNothing() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize("[{\"outcomeCode\":\"HIT\"}]"));
        assertTrue(thrown.getMessage().contains("至少要发一样"), thrown.getMessage());
    }

    /** 勋章发不出去就不该收:它随节点数据提前下发,那时还没有结果码。 */
    @Test
    void rejectsMedalBecauseTheGrantPathCannotDeliverIt() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(
                        "[{\"outcomeCode\":\"HIT\",\"medalName\":\"神算子\",\"medalImg\":\"https://a/b.png\"}]"));
        assertTrue(thrown.getMessage().contains("不支持勋章"), thrown.getMessage());
    }

    @Test
    void rejectsOversizedXpAndText() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize("[{\"outcomeCode\":\"HIT\",\"xp\":5000}]"));

        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 201; i++) longText.append('字');
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(
                        "[{\"outcomeCode\":\"HIT\",\"feedbackText\":\"" + longText + "\"}]"));
    }

    @Test
    void rejectsNonArrayAndTooManyRules() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize("{\"outcomeCode\":\"HIT\",\"xp\":10}"));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize("不是 JSON"));

        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 9; i++) {
            if (i > 0) many.append(',');
            many.append("{\"outcomeCode\":\"C").append(i).append("\",\"xp\":1}");
        }
        many.append(']');
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(many.toString()));
    }

    @Test
    void ruleForPicksTheMatchingOutcome() {
        String rules = "[{\"outcomeCode\":\"ESTIMATE_HIT\",\"xp\":50},"
                + "{\"outcomeCode\":\"ESTIMATE_MISS\",\"feedbackText\":\"下次再来\"}]";

        JsonNode hit = validator.ruleFor(rules, "ESTIMATE_HIT");
        assertNotNull(hit);
        assertEquals(50, hit.path("xp").asInt());

        JsonNode miss = validator.ruleFor(rules, "ESTIMATE_MISS");
        assertEquals("下次再来", miss.path("feedbackText").asText());

        assertNull(validator.ruleFor(rules, "ESTIMATE_CLOSE"), "没配的档位不发奖");
        assertNull(validator.ruleFor(rules, null), "没有结果码就没有条件奖励");
        assertNull(validator.ruleFor(null, "ESTIMATE_HIT"));
    }

    /**
     * 存量脏数据不能把玩家卡在节点上。
     *
     * <p>少发一份奖是可修的;抛异常会让整个节点完成回滚,玩家反复重试都过不去。
     */
    @Test
    void ruleForTreatsCorruptJsonAsNoConditionalReward() {
        assertNull(validator.ruleFor("{坏掉的 json", "HIT"));
        assertNull(validator.ruleFor("\"不是数组\"", "HIT"));
    }
}
