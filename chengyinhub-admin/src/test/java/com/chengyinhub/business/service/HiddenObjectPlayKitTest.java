package com.chengyinhub.business.service;

import com.chengyinhub.business.service.support.AdvancedGameConfigValidator;
import com.chengyinhub.business.util.AdvancedGamePublicProjection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 找东西(advanced.hiddenObject):一张店内照片上标 3–5 个热区,玩家点中即亮。
 *
 * <p>坐标是归一化的 0–1,与玩家把图缩放到多大无关。
 * 这个玩法只有一道防线:热区坐标不下发。下发了,抓包就知道东西藏在哪。
 */
class HiddenObjectPlayKitTest {

    private final AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();

    /** 三只猫藏在图的左上、中间、右下。 */
    private String config() {
        return "{\"schemaVersion\":1,\"hiddenObject\":{\"enabled\":true,"
                + "\"title\":\"找出藏在店里的三只猫\",\"hint\":\"有一只在收银台后面\","
                + "\"imageUrl\":\"https://cdn.example.com/shop.jpg\",\"xp\":80,\"hotspots\":["
                + "{\"id\":\"cat1\",\"label\":\"窗台上的橘猫\",\"x\":0.2,\"y\":0.3,\"r\":0.06},"
                + "{\"id\":\"cat2\",\"label\":\"书架旁的狸花\",\"x\":0.5,\"y\":0.5,\"r\":0.05},"
                + "{\"id\":\"cat3\",\"label\":\"收银台后的黑猫\",\"x\":0.8,\"y\":0.75,\"r\":0.07}]}}";
    }

    @Test
    void acceptsAWellFormedScene() {
        String normalized = validator.validateAndNormalize(config());
        assertTrue(normalized.contains("\"x\":0.2"), "真源里必须留着坐标");
    }

    /** ★ 本文件唯一要紧的一条。 */
    @Test
    void publicProjectionHidesEveryCoordinate() {
        String publicJson = AdvancedGamePublicProjection.safeConfigJson(config());

        assertFalse(publicJson.contains("0.2"), "坐标泄露,抓包即知答案:" + publicJson);
        assertFalse(publicJson.contains("0.5"), publicJson);
        assertFalse(publicJson.contains("0.75"), publicJson);
        assertFalse(publicJson.contains("\"x\""), "连字段名都不该出现:" + publicJson);
        assertFalse(publicJson.contains("\"y\""), publicJson);
        assertFalse(publicJson.contains("\"r\""), publicJson);
    }

    /** 待找清单要下发:玩家得知道自己在找什么,否则只能瞎点。 */
    @Test
    void publicProjectionKeepsTheImageAndTheShoppingList() {
        String publicJson = AdvancedGamePublicProjection.safeConfigJson(config());

        assertTrue(publicJson.contains("shop.jpg"), "图要下发");
        assertTrue(publicJson.contains("窗台上的橘猫"), "待找清单要下发");
        assertTrue(publicJson.contains("\"total\":3"), "还差几样要下发:" + publicJson);
        assertTrue(publicJson.contains("有一只在收银台后面"), "提示要下发");
    }

    @Test
    void disabledSceneLeaksNothing() {
        String publicJson = AdvancedGamePublicProjection.safeConfigJson(
                "{\"schemaVersion\":1,\"hiddenObject\":{\"enabled\":false,\"hotspots\":["
                        + "{\"id\":\"a\",\"label\":\"x\",\"x\":0.11,\"y\":0.22,\"r\":0.05}]}}");

        assertFalse(publicJson.contains("0.11"), "关掉的段也不许漏坐标:" + publicJson);
    }

    @Test
    void requiresAnImage() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(
                        "{\"schemaVersion\":1,\"hiddenObject\":{\"enabled\":true,\"title\":\"t\","
                                + "\"hotspots\":[{\"id\":\"a\",\"label\":\"x\",\"x\":0.2,\"y\":0.2,\"r\":0.05},"
                                + "{\"id\":\"b\",\"label\":\"y\",\"x\":0.5,\"y\":0.5,\"r\":0.05},"
                                + "{\"id\":\"c\",\"label\":\"z\",\"x\":0.8,\"y\":0.8,\"r\":0.05}]}}"));
        assertTrue(thrown.getMessage().contains("必须有一张图"), thrown.getMessage());
    }

    @Test
    void rejectsTooFewOrTooManyTargets() {
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"hiddenObject\":{\"enabled\":true,\"title\":\"t\","
                        + "\"imageUrl\":\"https://a/b.jpg\",\"hotspots\":["
                        + "{\"id\":\"a\",\"label\":\"x\",\"x\":0.2,\"y\":0.2,\"r\":0.05},"
                        + "{\"id\":\"b\",\"label\":\"y\",\"x\":0.6,\"y\":0.6,\"r\":0.05}]}}"));
    }

    @Test
    void rejectsCoordinatesOutsideTheImage() {
        // 归一化坐标只能是 0–1,超出去的点永远点不到
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"hiddenObject\":{\"enabled\":true,\"title\":\"t\","
                        + "\"imageUrl\":\"https://a/b.jpg\",\"hotspots\":["
                        + "{\"id\":\"a\",\"label\":\"x\",\"x\":1.5,\"y\":0.2,\"r\":0.05},"
                        + "{\"id\":\"b\",\"label\":\"y\",\"x\":0.5,\"y\":0.5,\"r\":0.05},"
                        + "{\"id\":\"c\",\"label\":\"z\",\"x\":0.8,\"y\":0.8,\"r\":0.05}]}}"));
    }

    @Test
    void rejectsRadiusThatIsUnhittableOrCoversHalfTheImage() {
        // 太小:手指比它还大
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                sceneWithRadius(0.001)));
        // 太大:整张图都是答案
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                sceneWithRadius(0.4)));
    }

    private String sceneWithRadius(double r) {
        return "{\"schemaVersion\":1,\"hiddenObject\":{\"enabled\":true,\"title\":\"t\","
                + "\"imageUrl\":\"https://a/b.jpg\",\"hotspots\":["
                + "{\"id\":\"a\",\"label\":\"x\",\"x\":0.2,\"y\":0.2,\"r\":" + r + "},"
                + "{\"id\":\"b\",\"label\":\"y\",\"x\":0.5,\"y\":0.5,\"r\":0.05},"
                + "{\"id\":\"c\",\"label\":\"z\",\"x\":0.8,\"y\":0.8,\"r\":0.05}]}}";
    }

    /** 两个目标挨太近,玩家点一下同时落进两个圈,谁也说不清点中了哪个。 */
    @Test
    void rejectsOverlappingTargets() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(
                        "{\"schemaVersion\":1,\"hiddenObject\":{\"enabled\":true,\"title\":\"t\","
                                + "\"imageUrl\":\"https://a/b.jpg\",\"hotspots\":["
                                + "{\"id\":\"a\",\"label\":\"x\",\"x\":0.50,\"y\":0.50,\"r\":0.08},"
                                + "{\"id\":\"b\",\"label\":\"y\",\"x\":0.54,\"y\":0.50,\"r\":0.08},"
                                + "{\"id\":\"c\",\"label\":\"z\",\"x\":0.9,\"y\":0.9,\"r\":0.05}]}}"));
        assertTrue(thrown.getMessage().contains("挨得太近"), thrown.getMessage());
    }

    @Test
    void rejectsDuplicateTargetIds() {
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"hiddenObject\":{\"enabled\":true,\"title\":\"t\","
                        + "\"imageUrl\":\"https://a/b.jpg\",\"hotspots\":["
                        + "{\"id\":\"a\",\"label\":\"x\",\"x\":0.2,\"y\":0.2,\"r\":0.05},"
                        + "{\"id\":\"a\",\"label\":\"y\",\"x\":0.5,\"y\":0.5,\"r\":0.05},"
                        + "{\"id\":\"c\",\"label\":\"z\",\"x\":0.8,\"y\":0.8,\"r\":0.05}]}}"));
    }

    /**
     * 配了找东西,节点就必须被当成"有高级玩法"。
     *
     * <p>漏了这条,hasAdvanced 为 false,玩家走到点直接打卡,玩法静默不存在 ——
     * 校验器里那条注释记着 migration_20260825 那批 vm=0 模板正是这个病。
     */
    @Test
    void countsAsAnEnabledMechanicSoTheNodeDoesNotSilentlySkipIt() {
        assertTrue(validator.hasEnabledMechanic(config()));
    }
}
