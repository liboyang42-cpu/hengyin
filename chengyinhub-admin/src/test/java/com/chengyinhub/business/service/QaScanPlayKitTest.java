package com.chengyinhub.business.service;

import com.chengyinhub.business.service.support.AdvancedGameConfigValidator;
import com.chengyinhub.business.util.AdvancedGamePublicProjection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 问答三种模式 + 扫码参与,并入高级玩法这条通道(2026-09-10 用户拍板)。
 *
 * <p>此前这两个走的是节点 validationMethod 那条老链路 —— 跟其余十七个玩法不在同一套配置里,
 * 也不在同一条游玩通道上,结果是新做的那几屏在生产根本到不了。
 *
 * <p>这份用例守两条线:**答案不下发**(打字题的 answerText、选项题的 correct),
 * 以及**扫码的回复要等扫完才给**(扫之前就能看见,这一站的「到店」就没有意义了)。
 */
class QaScanPlayKitTest {

    private final AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();

    private String typeQa() {
        return "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"TYPE\","
                + "\"title\":\"这座桥建于哪一年?\",\"answerText\":\"1908、一九〇八\",\"maxTries\":3,\"xp\":20}}";
    }

    private String pickQa() {
        return "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"PICK\","
                + "\"title\":\"石碑上的图案象征什么?\",\"reveal\":true,\"maxTries\":2,\"xp\":20,\"options\":["
                + "{\"id\":\"a\",\"label\":\"丰收\",\"correct\":true,\"fb\":\"对了,是麦穗。\"},"
                + "{\"id\":\"b\",\"label\":\"远航\",\"correct\":false,\"fb\":\"再看看穗子。\"}]}}";
    }

    private String scanText() {
        return "{\"schemaVersion\":1,\"scan\":{\"enabled\":true,\"kind\":\"TEXT\","
                + "\"reply\":\"欢迎来到这家店,今天的手冲是耶加雪菲。\",\"xp\":10}}";
    }

    @Test
    void acceptsAllThreeQaModes() {
        assertTrue(validator.validateAndNormalize(typeQa()).contains("1908"));
        assertTrue(validator.validateAndNormalize(pickQa()).contains("丰收"));
        assertTrue(validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"SHOT\","
                        + "\"title\":\"拍下这块招牌\",\"xp\":20}}").contains("拍下这块招牌"));
    }

    @Test
    void typeQaMustCarryAnAnswerOrNobodyCouldEverPass() {
        String noAnswer = typeQa().replace(",\"answerText\":\"1908、一九〇八\"", "");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(noAnswer));
        assertEquals("打字问答必须填正确答案", error.getMessage());
    }

    @Test
    void pickQaNeedsExactlyOneAnswer() {
        String none = pickQa().replace("\"correct\":true", "\"correct\":false");
        assertEquals("选项问答必须指定且只指定一个正确答案",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(none)).getMessage());
        String two = pickQa().replace("\"label\":\"远航\",\"correct\":false", "\"label\":\"远航\",\"correct\":true");
        assertEquals("选项问答必须指定且只指定一个正确答案",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(two)).getMessage());
    }

    @Test
    void shotQaNeedsNoAnswerBecausePhotosCannotBeJudged() {
        // 拍照没有对错 —— 交上来就算做到了,所以不校验答案
        String normalized = validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"SHOT\",\"title\":\"拍一张\",\"xp\":0}}");
        assertTrue(normalized.contains("SHOT"));
    }

    @Test
    void publicProjectionNeverShipsTheAnswer() {
        String typed = AdvancedGamePublicProjection.safeConfigJson(typeQa());
        assertFalse(typed.contains("1908"), "打字题的答案绝不能下发");
        assertFalse(typed.contains("answerText"));

        String picked = AdvancedGamePublicProjection.safeConfigJson(pickQa());
        assertTrue(picked.contains("丰收"), "选项文案要给,不然屏上没东西可点");
        assertFalse(picked.contains("correct"), "哪个是对的不能下发");
        assertFalse(picked.contains("麦穗"), "选项反馈是判完才看到的,提前给等于提示答案");
    }

    @Test
    void scanRepliesAreNotShippedBeforeScanning() {
        String publicJson = AdvancedGamePublicProjection.safeConfigJson(scanText());
        assertTrue(publicJson.contains("TEXT"), "回什么形态可以给 —— 屏上要先画对样子");
        assertFalse(publicJson.contains("耶加雪菲"),
                "回复内容是扫完的奖励;扫之前就能看见,这一站的到店就没有意义了");
    }

    @Test
    void scanMustCarryWhateverItPromisedToReplyWith() {
        assertEquals("扫码回语音就得配一段语音", assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(
                        "{\"schemaVersion\":1,\"scan\":{\"enabled\":true,\"kind\":\"VOICE\",\"xp\":0}}")).getMessage());
        assertEquals("扫码回图片就得配一张图", assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(
                        "{\"schemaVersion\":1,\"scan\":{\"enabled\":true,\"kind\":\"IMAGE\",\"xp\":0}}")).getMessage());
        assertEquals("扫码回文字就得写一句话", assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(
                        "{\"schemaVersion\":1,\"scan\":{\"enabled\":true,\"kind\":\"TEXT\",\"xp\":0}}")).getMessage());
    }

    // ==================== R3:选项问答多选(multi)====================

    private String multiPickQa() {
        return "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"multi\":true,"
                + "\"title\":\"哪些是冷萃?\",\"reveal\":true,\"maxTries\":2,\"xp\":20,\"options\":["
                + "{\"id\":\"a\",\"label\":\"冷萃\",\"correct\":true,\"fb\":\"对,冷萃是冷的。\"},"
                + "{\"id\":\"b\",\"label\":\"拿铁\",\"correct\":false,\"fb\":\"拿铁是热的。\"},"
                + "{\"id\":\"c\",\"label\":\"冰滴\",\"correct\":true,\"fb\":\"对,冰滴也是。\"}]}}";
    }

    @Test
    void multiPickAcceptsTwoOrMoreCorrectAnswers() {
        assertTrue(validator.validateAndNormalize(multiPickQa()).contains("冷萃"));
        assertTrue(validator.hasEnabledMechanic(multiPickQa()));
    }

    @Test
    void multiPickRejectsASingleCorrectAnswerOrAllCorrectAnswers() {
        String one = multiPickQa().replace(
                "{\"id\":\"c\",\"label\":\"冰滴\",\"correct\":true,\"fb\":\"对,冰滴也是。\"}",
                "{\"id\":\"c\",\"label\":\"冰滴\",\"correct\":false,\"fb\":\"冰滴不算。\"}");
        assertEquals("多选问答至少要指定两个正确答案",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(one)).getMessage());

        String all = multiPickQa().replace(
                "{\"id\":\"b\",\"label\":\"拿铁\",\"correct\":false,\"fb\":\"拿铁是热的。\"}",
                "{\"id\":\"b\",\"label\":\"拿铁\",\"correct\":true,\"fb\":\"拿铁也算。\"}");
        assertEquals("多选问答不能把全部选项都设为正确答案",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(all)).getMessage());
    }

    /** 老单选配置行为不变:不带 multi 时仍要求恰好一个正确答案。 */
    @Test
    void singlePickStillNeedsExactlyOneAnswerWithoutTheMultiFlag() {
        assertTrue(pickQa().contains("\"correct\":true"));
        String none = pickQa().replace("\"correct\":true", "\"correct\":false");
        assertEquals("选项问答必须指定且只指定一个正确答案",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(none)).getMessage());
    }

    @Test
    void multiPickShipsTheFlagButNeverWhichOptionsAreCorrect() {
        String safe = AdvancedGamePublicProjection.safeConfigJson(multiPickQa());

        assertTrue(safe.contains("\"multi\":true"), "多选标记不给,客户端会按单选渲染:" + safe);
        assertFalse(safe.contains("correct"), "哪个是对的不能下发:" + safe);
        assertFalse(safe.contains("冷萃是冷的"), "选项反馈是判完才看到的:" + safe);
    }

    /** ★ 只配了问答或扫码的节点也得算「配了高级玩法」,否则开局直接被判成没配,玩家到点就打卡。 */
    @Test
    void qaOrScanAloneStillCountsAsAnEnabledMechanic() {
        assertTrue(validator.hasEnabledMechanic(typeQa()));
        assertTrue(validator.hasEnabledMechanic(scanText()));
        // 顺带把决定类也钉住 —— 它们此前同样漏在这张表外
        assertTrue(validator.hasEnabledMechanic(
                "{\"schemaVersion\":1,\"stopwatch\":{\"enabled\":true,\"targetSeconds\":10,"
                        + "\"toleranceMs\":300,\"tries\":3,\"xp\":0}}"));
    }
}
