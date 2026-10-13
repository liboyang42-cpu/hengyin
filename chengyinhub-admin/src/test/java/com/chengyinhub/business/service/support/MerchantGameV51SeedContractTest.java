package com.chengyinhub.business.service.support;

import com.chengyinhub.business.util.AdvancedGamePublicProjection;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v5.1 新玩法商家种子的配置契约。
 *
 * 为什么值得一测:这份 SQL 里的 advanced_config 只有落到生产库、被商家采用、玩家走到节点
 * 才会真正被解析 —— 配错了不会有任何报错,只会「玩家走到点直接打卡,玩法静默不存在」,
 * 正是 migration_20260825 那批 vm=0 模板的病。所以在 CI 就用**真的校验器**跑一遍。
 */
class MerchantGameV51SeedContractTest {

    private static final Path MIGRATION = Paths.get("sql/migration_seed_merchant_game_v51_20260827.sql");
    /** -- TEMPLATE slug|标题|vm|适用业态|机制 */
    private static final Pattern TEMPLATE_MARKER = Pattern.compile(
            "^-- TEMPLATE ([a-z0-9_]+)\\|([^|]+)\\|(\\d+)\\|([^|]+)\\|([^\\r\\n]+)$", Pattern.MULTILINE);
    /** INSERT INTO seed_..._config (slug, advanced_config) VALUES ('slug', '{...}'); */
    private static final Pattern CONFIG_ROW = Pattern.compile(
            "INSERT INTO seed_merchant_game_v51_config \\(slug, advanced_config\\)\\s*\\n?VALUES \\('([a-z0-9_]+)', '(\\{.*?\\})'\\);",
            Pattern.DOTALL);

    private final AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();

    private String sql() throws Exception {
        return new String(Files.readAllBytes(MIGRATION), StandardCharsets.UTF_8);
    }

    private Map<String, String> configs() throws Exception {
        Map<String, String> byslug = new LinkedHashMap<String, String>();
        Matcher m = CONFIG_ROW.matcher(sql());
        while (m.find()) byslug.put(m.group(1), m.group(2));
        return byslug;
    }

    @Test
    void 七款各带一个新机制且标记行与配置一一对应() throws Exception {
        Map<String, String> configs = configs();
        Set<String> marked = new LinkedHashSet<String>();
        Matcher m = TEMPLATE_MARKER.matcher(sql());
        while (m.find()) {
            assertTrue(marked.add(m.group(1)), "slug 不能重复:" + m.group(1));
            // vm 一律 0:玩法整个跑在 advanced 段里,不占用四型交互
            assertEquals("0", m.group(3), m.group(1) + " 的 validation_method 应为 0");
        }
        assertEquals(7, marked.size(), "本批应为 7 款");
        assertEquals(marked, configs.keySet(), "标记行与 config 行必须一一对应,漏一个就是配了却没生效");
    }

    @Test
    void 每一份配置都能过真的校验器() throws Exception {
        for (Map.Entry<String, String> entry : configs().entrySet()) {
            String normalized = validator.validateAndNormalize(entry.getValue());
            assertTrue(normalized != null && !normalized.isEmpty(), entry.getKey() + " 配置为空");
        }
    }

    @Test
    void 每一份配置都必须被认作有高级玩法() throws Exception {
        // 这条最要命:hasEnabledMechanic 为 false 时客户端 hasAdvanced 也是 false,
        // 玩家走到点直接打卡,玩法一声不响地不存在,而且零告警
        for (Map.Entry<String, String> entry : configs().entrySet()) {
            assertTrue(validator.hasEnabledMechanic(entry.getValue()),
                    entry.getKey() + " 没有被认作高级玩法 —— 玩家会走到点直接打卡");
        }
    }

    @Test
    void 负控_盲品答案与签文池都不会经模板投影下发() throws Exception {
        Map<String, String> configs = configs();

        String blind = configs.get("blind_taste_master");
        assertTrue(blind.contains("answerKey"), "种子里本来就该带答案,它靠服务端保管");
        String safeBlind = AdvancedGamePublicProjection.safeConfigJson(blind);
        assertFalse(safeBlind.contains("answerKey"), "投影里出现答案 = 这道题当场作废:" + safeBlind);

        String sign = configs.get("daily_city_sign");
        assertTrue(sign.contains("poems"), "种子里本来就该带签文池");
        String safeSign = AdvancedGamePublicProjection.safeConfigJson(sign);
        assertFalse(safeSign.contains("poems"), "投影里出现签文池 = 把明天的签提前给了:" + safeSign);
    }

    @Test
    void 音乐角不承诺奖励分() throws Exception {
        // 2026-08-26 拍板:它不判定通关。配置里若给了 xp,文案与实际就对不上了
        String music = configs().get("healing_music_corner");
        assertFalse(music.contains("\"xp\""), "音乐角不判定通关,不该配奖励分:" + music);
    }

    @Test
    void 幂等与守卫都在() throws Exception {
        String sql = sql();
        // 每条 INSERT 都带 NOT EXISTS 守卫 ⇒ 重跑收敛而不是插重复
        int inserts = countOccurrences(sql, "INSERT INTO cms_template_library");
        int guards = countOccurrences(sql, "WHERE NOT EXISTS (SELECT 1 FROM cms_template_library");
        assertEquals(7, inserts);
        assertEquals(inserts, guards, "每条模板 INSERT 都必须带 NOT EXISTS 守卫,否则重跑会插重复");

        assertTrue(sql.contains("seed_merchant_game_v51_guard"), "必须有 fail-closed 守卫表");
        assertTrue(sql.contains("runner: requires=transaction"), "多条 INSERT 要么全上要么全不上");
        // 回读刻意不写在文件里:--transaction 白名单不收 SELECT,而且同事务读自己刚写的值
        // 属于「问自己」不是观测 —— 但执行者必须拿得到那条回读 SQL,所以它以注释形式在册
        assertTrue(sql.contains("执行者请在 COMMIT 之后单独跑"), "必须给出落库后的回读 SQL");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = haystack.indexOf(needle);
        while (idx >= 0) {
            count++;
            idx = haystack.indexOf(needle, idx + needle.length());
        }
        return count;
    }
}
