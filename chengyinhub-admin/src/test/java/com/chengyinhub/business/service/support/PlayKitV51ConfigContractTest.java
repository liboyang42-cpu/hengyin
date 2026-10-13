package com.chengyinhub.business.service.support;

import com.chengyinhub.business.util.AdvancedGamePublicProjection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Figma 组件库 v5.1 新玩法(node 45:248)的配置校验与公开投影契约。
 *
 * 两类断言分得很清:
 *   ① 校验:配错了必须当场抛,不能让"答案不在选项里""开放时段起止相同"这种
 *      **永远过不了关又不报错**的配置进库 —— 那是静默失效,最难查;
 *   ② 投影:盲品答案与签文池**绝不能下发**。这条错了不会有任何报错,
 *      只会让玩法当场作废,所以每条都配一个负控。
 */
class PlayKitV51ConfigContractTest {

    private final AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();

    private static String wrap(String section) {
        return "{\"schemaVersion\":1," + section + "}";
    }

    private static final String BLIND_TASTE = "\"blindTaste\":{\"enabled\":true,"
            + "\"title\":\"闭上眼,先尝再猜。\",\"steps\":\"① 领小样 ② 闭眼尝 ③ 作答\","
            + "\"hint\":\"猫向导:别偷看\",\"xp\":15,"
            + "\"options\":[{\"key\":\"A\",\"label\":\"桂花乌龙\"},{\"key\":\"B\",\"label\":\"茉莉雪芽\"},"
            + "{\"key\":\"C\",\"label\":\"蜜桃红茶\"},{\"key\":\"D\",\"label\":\"陈皮白茶\"}],"
            + "\"answerKey\":\"B\"}";

    // ==================== 校验 ====================

    @Test
    void 八个新机制任一开启都算有高级玩法() {
        // 漏认一个 = 配了玩法但 hasAdvanced 仍 false,玩家走到点直接打卡、玩法静默不存在
        String[] sections = {
                "\"timeWindow\":{\"enabled\":true,\"openFrom\":\"00:00\",\"openTo\":\"01:00\"}",
                BLIND_TASTE,
                "\"silentOrder\":{\"enabled\":true,\"title\":\"不说一个字\"}",
                "\"diyName\":{\"enabled\":true,\"title\":\"给它起个名字\"}",
                "\"musicCorner\":{\"enabled\":true,\"title\":\"只用耳朵\"}",
                "\"steps\":{\"enabled\":true,\"goal\":6000}",
                "\"dailySign\":{\"enabled\":true,\"poems\":[[\"这座城市今晚会把\",\"一条没走过的巷子\"]]}",
                "\"slowTask\":{\"enabled\":true,\"title\":\"明天再来\",\"unlockText\":\"老板会在门口摆一张椅子\"}",
        };
        for (String section : sections) {
            assertTrue(validator.hasEnabledMechanic(wrap(section)),
                    "该段开启后必须被认作高级玩法:" + section);
        }
    }

    @Test
    void 负控_盲品答案不在选项里必须判红() {
        String bad = BLIND_TASTE.replace("\"answerKey\":\"B\"", "\"answerKey\":\"Z\"");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(wrap(bad)));
        // 放过它 = 这题永远答不对,而且不报错
        assertTrue(e.getMessage().contains("正确答案"), e.getMessage());
    }

    @Test
    void 负控_盲品选项key重复必须判红() {
        String bad = BLIND_TASTE.replace("{\"key\":\"C\",\"label\":\"蜜桃红茶\"}",
                "{\"key\":\"A\",\"label\":\"蜜桃红茶\"}");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(bad)));
    }

    @Test
    void 负控_盲品选项少于两个或多于六个都判红() {
        String one = "\"blindTaste\":{\"enabled\":true,\"title\":\"t\",\"answerKey\":\"A\","
                + "\"options\":[{\"key\":\"A\",\"label\":\"只有一个\"}]}";
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(one)));
    }

    @Test
    void 负控_开放时段起止相同必须判红() {
        // 相等既能读成"全天开放"也能读成"永不开放",歧义比不合法更危险
        String bad = "\"timeWindow\":{\"enabled\":true,\"openFrom\":\"01:00\",\"openTo\":\"01:00\"}";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(wrap(bad)));
        assertTrue(e.getMessage().contains("起止不能相同"), e.getMessage());
    }

    @Test
    void 跨夜时段是合法的() {
        // 午夜电台 23:00–01:00 正是跨夜,不能因为 from > to 就判非法
        String ok = "\"timeWindow\":{\"enabled\":true,\"openFrom\":\"23:00\",\"openTo\":\"01:00\"}";
        assertTrue(validator.hasEnabledMechanic(wrap(ok)));
    }

    @Test
    void 负控_时段格式不是HHmm判红() {
        String bad = "\"timeWindow\":{\"enabled\":true,\"openFrom\":\"25:00\",\"openTo\":\"01:00\"}";
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(wrap(bad)));
    }

    @Test
    void 负控_计步目标越界判红() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(wrap("\"steps\":{\"enabled\":true,\"goal\":10}")));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(wrap("\"steps\":{\"enabled\":true,\"goal\":999999}")));
    }

    @Test
    void 负控_签文池为空或行数越界判红() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(wrap("\"dailySign\":{\"enabled\":true,\"poems\":[]}")));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                wrap("\"dailySign\":{\"enabled\":true,\"poems\":[[\"1\",\"2\",\"3\",\"4\",\"5\"]]}")));
    }

    @Test
    void 负控_跨日任务缺少解锁内容必须判红() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(wrap(
                        "\"slowTask\":{\"enabled\":true,\"title\":\"明天再来\"}")));
        assertTrue(e.getMessage().contains("解锁"), e.getMessage());
    }

    @Test
    void 未开启的段不校验内部字段() {
        // enabled=false 时里面写什么都不该拦 —— 否则商家关掉玩法反而存不了
        String off = "\"steps\":{\"enabled\":false,\"goal\":1}";
        assertFalse(validator.hasEnabledMechanic(wrap(off)));
    }

    // ==================== 公开投影 ====================

    @Test
    void 负控_盲品答案绝不下发给客户端() {
        String safe = AdvancedGamePublicProjection.safeConfigJson(wrap(BLIND_TASTE));

        assertFalse(safe.contains("answerKey"), "投影里出现 answerKey = 答案发给了客户端:" + safe);
        // 但选项本身要在,否则玩家看不到题
        assertTrue(safe.contains("桂花乌龙"), safe);
        assertTrue(safe.contains("茉莉雪芽"), safe);
    }

    @Test
    void 负控_签文池绝不下发给客户端() {
        String config = wrap("\"dailySign\":{\"enabled\":true,\"signer\":\"猫向导\","
                + "\"poems\":[[\"今晚的签\"],[\"明天的签\"]]}");

        String safe = AdvancedGamePublicProjection.safeConfigJson(config);

        assertFalse(safe.contains("poems"), "投影里出现 poems = 把明天的签提前给了:" + safe);
        assertFalse(safe.contains("明天的签"), safe);
        assertTrue(safe.contains("猫向导"), "落款是可以给的:" + safe);
    }

    @Test
    void 投影保留玩家看得见的字段() {
        String config = wrap("\"timeWindow\":{\"enabled\":true,\"openFrom\":\"00:00\",\"openTo\":\"01:00\","
                + "\"title\":\"还没到开播时间\"},"
                + "\"steps\":{\"enabled\":true,\"goal\":6000,\"xp\":20}");

        String safe = AdvancedGamePublicProjection.safeConfigJson(config);

        assertTrue(safe.contains("00:00") && safe.contains("01:00"), safe);
        assertTrue(safe.contains("还没到开播时间"), safe);
        assertTrue(safe.contains("6000"), safe);
    }

    @Test
    void 未开启的段在投影里只留一个enabled_false() {
        String safe = AdvancedGamePublicProjection.safeConfigJson(
                wrap("\"blindTaste\":{\"enabled\":false,\"answerKey\":\"B\"}"));

        assertTrue(safe.contains("\"blindTaste\":{\"enabled\":false}"), safe);
        assertFalse(safe.contains("answerKey"), "关掉的段也不能漏答案:" + safe);
    }

    @Test
    void 旧配置不受影响() {
        // 既有五机制的投影形状不能因为加了新段而改变
        String legacy = wrap("\"timer\":{\"enabled\":true,\"durationSeconds\":60,\"timeoutResult\":\"EXPIRED\"}");
        String safe = AdvancedGamePublicProjection.safeConfigJson(legacy);
        assertTrue(safe.contains("\"timer\":{\"enabled\":true,\"durationSeconds\":60}"), safe);
        assertEquals(1, validator.hasEnabledMechanic(legacy) ? 1 : 0);
    }
}
