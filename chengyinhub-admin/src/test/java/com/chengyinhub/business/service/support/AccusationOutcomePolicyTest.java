package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CT-07 / 0.5:指认结论由服务端判定;提前结案、错误指认、机会用尽三者分开。
 * 正确答案只来自既有 QA,本类不读 {@code accusation.correctSuspectId},也不给 recordAs 默认值。
 */
class AccusationOutcomePolicyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String CONFIG = "{"
            + "\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"judge\":true,\"multi\":false,\"options\":["
            + "{\"id\":\"o1\",\"label\":\"张医生\",\"correct\":false},"
            + "{\"id\":\"o2\",\"label\":\"李师傅\",\"correct\":true}]},"
            + "\"accusation\":{\"enabled\":true,"
            + "\"suspects\":[{\"id\":\"o1\",\"name\":\"张医生\"},{\"id\":\"o2\",\"name\":\"李师傅\"}],"
            + "\"winScreen\":\"win-1\",\"loseScreen\":\"lose-1\","
            + "\"earlyAccuse\":{\"allowed\":true,\"recordAs\":\"作者写的结束态\"}}}";

    /** 正确答案唯一来源:既有 QA 的 options[].correct。 */
    private static String correctOf(String configText) {
        JsonNode config = json(configText);
        return AccusationTemplateSupport.correctSuspectId(config.get("qa"));
    }

    @Test
    void correctSuspectComesOnlyFromTheExistingQa() {
        assertEquals("o2", correctOf(CONFIG));
        // accusation 段里写 correctSuspectId 不是本类读取的字段(且会被 G2 的校验当未知字段拒)。
        AccusationOutcomePolicy.Verdict verdict = AccusationOutcomePolicy.judge(
                "o2", correctOf(CONFIG), AccusationOutcomePolicy.earlyAccuse(json(CONFIG)), true, 3);
        assertEquals(AccusationOutcomePolicy.VERDICT_CORRECT, verdict.verdict());
    }

    @Test
    void unresolvedAnswerSourceIsRefusedNotGuessed() {
        AccusationOutcomePolicy.Verdict verdict = AccusationOutcomePolicy.judge(
                "o2", null, new AccusationOutcomePolicy.EarlyAccuse(false, null), true, 3);
        assertEquals(AccusationOutcomePolicy.VERDICT_REFUSED, verdict.verdict());
        assertEquals(AccusationOutcomePolicy.REFUSED_CORRECT_UNKNOWN, verdict.refuseCode());
        assertFalse(verdict.correct(), "答案来源不可用时不能判成对");
        assertFalse(verdict.terminal());
    }

    @Test
    void correctAndEarlyAreTwoIndependentServerFacts() {
        JsonNode config = json(CONFIG);
        String correct = correctOf(CONFIG);
        AccusationOutcomePolicy.EarlyAccuse early = AccusationOutcomePolicy.earlyAccuse(config);

        AccusationOutcomePolicy.Verdict normal =
                AccusationOutcomePolicy.judge(correct, correct, early, true, 3);
        assertTrue(normal.correct());
        assertFalse(normal.early());
        assertNull(normal.recordCode(), "正常结案不记提前结案");
        assertTrue(normal.terminal());
        assertEquals("winScreen", normal.endingScreen());

        AccusationOutcomePolicy.Verdict earlyCorrect =
                AccusationOutcomePolicy.judge(correct, correct, early, false, 3);
        assertTrue(earlyCorrect.correct());
        assertTrue(earlyCorrect.early());
        assertEquals("作者写的结束态", earlyCorrect.recordCode(), "recordAs 原样透传,不设默认值");
        assertTrue(earlyCorrect.terminal(), "提前结案仍是结案,不是失败");
    }

    @Test
    void wrongAccusationWithChancesLeftCanContinue() {
        AccusationOutcomePolicy.Verdict wrong = AccusationOutcomePolicy.judge(
                "o1", correctOf(CONFIG), AccusationOutcomePolicy.earlyAccuse(json(CONFIG)), true, 2);
        assertEquals(AccusationOutcomePolicy.VERDICT_WRONG, wrong.verdict());
        assertFalse(wrong.correct());
        assertTrue(wrong.canRetry(), "还有机会就继续调查");
        assertNull(wrong.endingScreen(), "还能继续时不下发终局文案");
        assertFalse(wrong.terminal());
    }

    @Test
    void earlyWrongDiffersFromEarlyCorrectAndFromFailure() {
        JsonNode config = json(CONFIG);
        String correct = correctOf(CONFIG);
        AccusationOutcomePolicy.EarlyAccuse early = AccusationOutcomePolicy.earlyAccuse(config);

        AccusationOutcomePolicy.Verdict earlyWrong =
                AccusationOutcomePolicy.judge("o1", correct, early, false, 1);
        assertEquals(AccusationOutcomePolicy.VERDICT_WRONG, earlyWrong.verdict());
        assertTrue(earlyWrong.early());
        assertNull(earlyWrong.recordCode(), "错误指认不记提前结案成立");

        AccusationOutcomePolicy.Verdict earlyFailed =
                AccusationOutcomePolicy.judge("o1", correct, early, false, 0);
        assertEquals(AccusationOutcomePolicy.VERDICT_FAILED, earlyFailed.verdict());
        assertFalse(earlyFailed.correct());
        assertTrue(earlyFailed.terminal());
        assertEquals("loseScreen", earlyFailed.endingScreen());
    }

    @Test
    void exhaustionIsFailureNotAWrongAnswer() {
        AccusationOutcomePolicy.Verdict last = AccusationOutcomePolicy.judge(
                "o1", correctOf(CONFIG), AccusationOutcomePolicy.earlyAccuse(json(CONFIG)), true, 0);
        assertEquals(AccusationOutcomePolicy.VERDICT_FAILED, last.verdict());
        assertFalse(last.canRetry());
        assertEquals("loseScreen", last.endingScreen());
    }

    @Test
    void earlyAccusationMustBeAllowedByConfiguration() {
        String strict = "{\"accusation\":{\"enabled\":true,\"earlyAccuse\":{\"allowed\":false}}}";
        AccusationOutcomePolicy.EarlyAccuse early = AccusationOutcomePolicy.earlyAccuse(json(strict));
        assertFalse(early.allowed());
        AccusationOutcomePolicy.Verdict refused =
                AccusationOutcomePolicy.judge("o2", "o2", early, false, 2);
        assertEquals(AccusationOutcomePolicy.REFUSED_EARLY_NOT_ALLOWED, refused.refuseCode());
        assertFalse(refused.correct(), "被拒的指认不能判成对");
        assertFalse(refused.terminal());
        assertTrue(refused.canRetry(), "拒绝不消耗机会");
    }

    @Test
    void missingOrUnknownSuspectIsRefused() {
        String correct = correctOf(CONFIG);
        AccusationOutcomePolicy.EarlyAccuse early = AccusationOutcomePolicy.earlyAccuse(json(CONFIG));
        assertEquals(AccusationOutcomePolicy.REFUSED_MISSING_SUSPECT,
                AccusationOutcomePolicy.judge("  ", correct, early, true, 2).refuseCode());
    }

    @Test
    void noAccusationBlockMeansNoEarlyRules() {
        AccusationOutcomePolicy.EarlyAccuse early =
                AccusationOutcomePolicy.earlyAccuse(json("{\"qa\":{\"enabled\":true}}"));
        assertFalse(early.allowed());
        assertNull(early.recordAs(), "没配就没有结束态记录,不补默认值");
        assertNull(AccusationOutcomePolicy.earlyAccuse(null).recordAs());
    }

    @Test
    void recordAsIsPassedThroughWithoutADefaultOrWhitelist() {
        String config = "{\"accusation\":{\"enabled\":true,"
                + "\"earlyAccuse\":{\"allowed\":true,\"recordAs\":\"作者自定:提前收网\"}}}";
        AccusationOutcomePolicy.EarlyAccuse early = AccusationOutcomePolicy.earlyAccuse(json(config));
        assertTrue(early.allowed());
        assertEquals("作者自定:提前收网", early.recordAs());
        AccusationOutcomePolicy.Verdict verdict = AccusationOutcomePolicy.judge("o2", "o2", early, false, 1);
        assertEquals("作者自定:提前收网", verdict.recordCode());
    }

    @Test
    void endingScreenUsesTheExistingTemplateSupport() {
        JsonNode config = json(CONFIG);
        String correct = correctOf(CONFIG);
        AccusationOutcomePolicy.EarlyAccuse early = AccusationOutcomePolicy.earlyAccuse(config);

        AccusationOutcomePolicy.Verdict win =
                AccusationOutcomePolicy.judge(correct, correct, early, true, 1);
        JsonNode winScreen = AccusationOutcomePolicy.endingScreen(config, win);
        assertNotNull(winScreen);
        assertEquals("win-1", winScreen.asText());

        AccusationOutcomePolicy.Verdict lose =
                AccusationOutcomePolicy.judge("o1", correct, early, true, 0);
        assertEquals("lose-1", AccusationOutcomePolicy.endingScreen(config, lose).asText());

        AccusationOutcomePolicy.Verdict retry =
                AccusationOutcomePolicy.judge("o1", correct, early, true, 2);
        assertNull(AccusationOutcomePolicy.endingScreen(config, retry), "可继续时不给终局段");
        assertNull(AccusationOutcomePolicy.endingScreen(config, null));
    }
}
