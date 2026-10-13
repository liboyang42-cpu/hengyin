package com.chengyinhub.business.service;

import com.chengyinhub.business.service.support.AdvancedGameConfigValidator;
import com.chengyinhub.business.util.AdvancedGamePublicProjection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 猜图(advanced.pricePair):几张图里挑出正确的那一张。
 *
 * <p>★ 2026-09-10 改模型。这一段原来是**比价**(每件带价格、两两比谁贵、连对算 streak);
 * 用户当天拍板「猜图不是比价格」,它就是「几张图挑正确的那张」。段名沿用 pricePair
 * 是为了不动已经存下来的那把钥匙 —— 老配置(带 price、没有 correct)由校验器整段关掉,
 * 不报错:报错会让已经发布的节点在开局校验时打不开,而那一段本来就没人玩过。
 *
 * <p>这题的全部难度就在「别把答案发下去」。correct 一旦出现在客户端,
 * 玩法从「猜」退化成「点」。
 */
class PricePairPlayKitTest {

    private final AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();

    /** 三张图,第二张是正确答案。 */
    private String config() {
        return "{\"schemaVersion\":1,\"pricePair\":{\"enabled\":true,\"title\":\"哪一张是这家店的招牌?\","
                + "\"xp\":30,\"maxTries\":2,\"items\":["
                + "{\"id\":\"a\",\"name\":\"冰美式\",\"correct\":false},"
                + "{\"id\":\"b\",\"name\":\"燕麦拿铁\",\"correct\":true},"
                + "{\"id\":\"c\",\"name\":\"单一产地手冲\",\"correct\":false}]}}";
    }

    @Test
    void acceptsAWellFormedBoard() {
        String normalized = validator.validateAndNormalize(config());
        assertTrue(normalized.contains("哪一张是这家店的招牌?"));
        assertTrue(normalized.contains("燕麦拿铁"));
    }

    @Test
    void publicProjectionHidesWhichOneIsRight() {
        String publicJson = AdvancedGamePublicProjection.safeConfigJson(config());
        assertFalse(publicJson.contains("correct"),
                "哪一张是对的绝不能下发 —— 抓一次包这一屏就作废了");
    }

    @Test
    void publicProjectionKeepsNamesAndIdsSoTheCardsCanRender() {
        String publicJson = AdvancedGamePublicProjection.safeConfigJson(config());
        assertTrue(publicJson.contains("\"id\":\"a\""));
        assertTrue(publicJson.contains("冰美式"), "名字要给,不然四张图分不出谁是谁");
        assertTrue(publicJson.contains("哪一张是这家店的招牌?"));
    }

    @Test
    void disabledSectionLeaksNothing() {
        String publicJson = AdvancedGamePublicProjection.safeConfigJson(
                "{\"schemaVersion\":1,\"pricePair\":{\"enabled\":false,\"title\":\"别下发我\","
                        + "\"items\":[{\"id\":\"a\",\"name\":\"也别下发我\",\"correct\":true}]}}");
        assertTrue(publicJson.contains("\"pricePair\":{\"enabled\":false}"));
        assertFalse(publicJson.contains("别下发我"));
    }

    @Test
    void rejectsFewerThanThreeItems() {
        String tooFew = "{\"schemaVersion\":1,\"pricePair\":{\"enabled\":true,\"title\":\"哪一张\","
                + "\"items\":[{\"id\":\"a\",\"name\":\"甲\",\"correct\":true},"
                + "{\"id\":\"b\",\"name\":\"乙\"}]}}";
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(tooFew));
        assertTrue(error.getMessage().contains("猜图的图片须为"));
    }

    @Test
    void rejectsNoAnswerBecauseNobodyCouldEverWin() {
        String none = config().replace("\"correct\":true", "\"correct\":false");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(none));
        assertEquals("猜图必须指定且只指定一张正确答案", error.getMessage());
    }

    @Test
    void rejectsTwoAnswersBecauseThatMakesEveryPickRight() {
        String two = config().replace("{\"id\":\"a\",\"name\":\"冰美式\",\"correct\":false}",
                "{\"id\":\"a\",\"name\":\"冰美式\",\"correct\":true}");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(two));
        assertEquals("猜图必须指定且只指定一张正确答案", error.getMessage());
    }

    @Test
    void rejectsDuplicateIds() {
        String dup = config().replace("\"id\":\"c\"", "\"id\":\"a\"");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(dup));
        assertEquals("猜图图片 id 不能重复", error.getMessage());
    }

    @Test
    void rejectsNonHttpsImage() {
        String bad = config().replace("{\"id\":\"a\",\"name\":\"冰美式\",\"correct\":false}",
                "{\"id\":\"a\",\"name\":\"冰美式\",\"correct\":false,\"imageUrl\":\"http://x/y.png\"}");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(bad));
    }

    /**
     * ★ 老的比价配置不报错,整段关掉。
     *
     * <p>报错会让已经发布的节点在开局校验那一步直接打不开 —— 拿一个从没被玩过的玩法
     * 去换一个能用的节点,不划算。商家下次编辑时会看到这个玩法是关着的,那是可见的。
     */
    @Test
    void retiresLegacyPriceConfigInsteadOfBreakingPublishedNodes() {
        String legacy = "{\"schemaVersion\":1,\"pricePair\":{\"enabled\":true,\"title\":\"猜猜哪个更贵\","
                + "\"xpPerHit\":30,\"items\":["
                + "{\"id\":\"a\",\"name\":\"冰美式\",\"price\":1800},"
                + "{\"id\":\"b\",\"name\":\"燕麦拿铁\",\"price\":2600},"
                + "{\"id\":\"c\",\"name\":\"单一产地手冲\",\"price\":3800}]}}";

        String normalized = validator.validateAndNormalize(legacy);

        assertTrue(normalized.contains("\"enabled\":false"), "老比价配置该被关掉");
        assertFalse(normalized.contains("1800"), "关掉的同时把商品也丢了,别留半份数据");
    }
}
