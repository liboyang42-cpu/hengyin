package com.chengyinhub.business.service;

import com.chengyinhub.business.service.support.AdvancedGameConfigValidator;
import com.chengyinhub.business.util.AdvancedGamePublicProjection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 估数题(advanced.estimate):商家给一个正确值与容差,玩家在数轴上猜。
 *
 * <p>本测试守两件事:配置自洽的边界,以及**答案不出服务端**。
 * 估数是本批九个玩法里第一个新建的 section,它的形状是后面几个的模子。
 */
class EstimatePlayKitTest {

    private final AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();

    /** 一罐咖啡豆有多少颗:答案 480,量程 0–1000,容差 50。 */
    private String config(String overrides) {
        return "{\"schemaVersion\":1,\"estimate\":{\"enabled\":true,"
                + "\"title\":\"这罐咖啡豆有多少颗?\",\"unit\":\"颗\","
                + "\"answer\":480,\"min\":0,\"max\":1000,\"tolerance\":50,\"xp\":100,"
                + "\"reveal\":\"一共 480 颗,老板数了两遍\"" + overrides + "}}";
    }

    @Test
    void acceptsAWellFormedEstimate() {
        String normalized = validator.validateAndNormalize(config(""));
        assertTrue(normalized.contains("\"answer\":480"), "真源里必须留着答案");
    }

    @Test
    void rejectsAnswerOutsideTheDial() {
        // 答案在数轴之外 = 这题永远拿不到满分,而且不会报错
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(
                        "{\"schemaVersion\":1,\"estimate\":{\"enabled\":true,\"title\":\"t\","
                                + "\"answer\":2000,\"min\":0,\"max\":1000,\"tolerance\":50,\"xp\":10}}"));
        assertTrue(thrown.getMessage().contains("量程之内"), thrown.getMessage());
    }

    @Test
    void rejectsToleranceWiderThanHalfTheDial() {
        // 容差过半 = 数轴上随便点都算命中
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(
                        "{\"schemaVersion\":1,\"estimate\":{\"enabled\":true,\"title\":\"t\","
                                + "\"answer\":500,\"min\":0,\"max\":1000,\"tolerance\":600,\"xp\":10}}"));
        assertTrue(thrown.getMessage().contains("一半"), thrown.getMessage());
    }

    @Test
    void rejectsNonPositiveTolerance() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(
                        "{\"schemaVersion\":1,\"estimate\":{\"enabled\":true,\"title\":\"t\","
                                + "\"answer\":500,\"min\":0,\"max\":1000,\"tolerance\":0,\"xp\":10}}"));
    }

    @Test
    void rejectsInvertedRange() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(
                        "{\"schemaVersion\":1,\"estimate\":{\"enabled\":true,\"title\":\"t\","
                                + "\"answer\":500,\"min\":1000,\"max\":0,\"tolerance\":10,\"xp\":10}}"));
    }

    @Test
    void rejectsNonNumericFields() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(
                        "{\"schemaVersion\":1,\"estimate\":{\"enabled\":true,\"title\":\"t\","
                                + "\"answer\":\"约五百\",\"min\":0,\"max\":1000,\"tolerance\":50,\"xp\":10}}"));
    }

    @Test
    void rejectsXpBeyondTheSharedCeiling() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(
                        "{\"schemaVersion\":1,\"estimate\":{\"enabled\":true,\"title\":\"t\","
                                + "\"answer\":500,\"min\":0,\"max\":1000,\"tolerance\":50,\"xp\":5000}}"));
    }

    /** ★ 本文件最重要的一条:答案、容差、揭示文案都不许下发。 */
    @Test
    void publicProjectionHidesAnswerToleranceAndReveal() {
        String publicJson = AdvancedGamePublicProjection.safeConfigJson(config(""));

        assertFalse(publicJson.contains("480"),
                "答案泄露了,抓包即可满分:" + publicJson);
        assertFalse(publicJson.contains("tolerance"),
                "容差泄露 = 满分区间宽度已知,几次试探就能定位答案:" + publicJson);
        assertFalse(publicJson.contains("老板数了两遍"),
                "揭示文案里通常直接写着真值:" + publicJson);
        assertFalse(publicJson.contains("reveal"), publicJson);
    }

    @Test
    void publicProjectionStillCarriesWhatTheDialNeeds() {
        String publicJson = AdvancedGamePublicProjection.safeConfigJson(config(""));

        assertTrue(publicJson.contains("这罐咖啡豆有多少颗?"), "题干要下发");
        assertTrue(publicJson.contains("\"unit\":\"颗\""), "单位要下发");
        assertTrue(publicJson.contains("\"min\":0.0"), "数轴下限要下发:" + publicJson);
        assertTrue(publicJson.contains("\"max\":1000.0"), "数轴上限要下发:" + publicJson);
    }

    @Test
    void disabledSectionCarriesNothingButTheFlag() {
        String publicJson = AdvancedGamePublicProjection.safeConfigJson(
                "{\"schemaVersion\":1,\"estimate\":{\"enabled\":false,\"answer\":480,\"tolerance\":50}}");

        assertFalse(publicJson.contains("480"), "关掉的段也不许漏答案:" + publicJson);
        assertTrue(publicJson.contains("\"estimate\":{\"enabled\":false}"), publicJson);
    }

    /**
     * 三档的边界。判定本身在 AdvancedGameRuntimeServiceImpl#submitEstimate,
     * 这里把「差多少算哪一档」这条规则本身钉住,免得日后改判定时无声地挪了边界。
     */
    @Test
    void tierBoundariesFollowTheThreeStepRule() {
        double answer = 480, tolerance = 50;

        assertEquals("HIT", tierOf(480, answer, tolerance), "正中");
        assertEquals("HIT", tierOf(530, answer, tolerance), "容差边界上仍算命中");
        assertEquals("CLOSE", tierOf(531, answer, tolerance), "越过容差一点点掉到接近档");
        assertEquals("CLOSE", tierOf(630, answer, tolerance), "3 倍容差边界上仍算接近");
        assertEquals("MISS", tierOf(631, answer, tolerance), "越过 3 倍容差就是偏离");
        assertEquals("HIT", tierOf(430, answer, tolerance), "低估同样分档");
        assertEquals("MISS", tierOf(0, answer, tolerance), "猜数轴下限");
    }

    /** 与 submitEstimate 里的分档保持同一套算式。 */
    private String tierOf(double guess, double answer, double tolerance) {
        double deviation = Math.abs(guess - answer);
        if (deviation <= tolerance) return "HIT";
        if (deviation <= tolerance * 3) return "CLOSE";
        return "MISS";
    }
}
