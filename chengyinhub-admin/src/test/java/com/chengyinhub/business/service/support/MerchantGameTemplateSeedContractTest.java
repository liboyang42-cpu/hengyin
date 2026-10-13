package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantGameTemplateSeedContractTest {

    private static final Path MIGRATION = Paths.get("sql/migration_seed_merchant_game_a8_20260823.sql");
    private static final Pattern TEMPLATE_MARKER = Pattern.compile(
            "^-- TEMPLATE ([a-z0-9_]+)\\|([^|]+)\\|(\\d+)\\|([^|]+)\\|([^\\r\\n]+)$",
            Pattern.MULTILINE);

    private final AdvancedGameConfigValidator advancedValidator = new AdvancedGameConfigValidator();
    private final PreferenceResultEvaluator preferenceEvaluator = new PreferenceResultEvaluator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void seedContainsExactlyEightPlayableMerchantTemplates() throws Exception {
        String sql = readMigration();
        Map<String, Spec> expected = expectedSpecs();
        Map<String, Spec> actual = new LinkedHashMap<String, Spec>();
        Matcher matcher = TEMPLATE_MARKER.matcher(sql);
        while (matcher.find()) {
            String slug = matcher.group(1);
            assertFalse(actual.containsKey(slug), "模板 slug 不能重复:" + slug);
            actual.put(slug, new Spec(matcher.group(2), Integer.parseInt(matcher.group(3)),
                    set(matcher.group(4)), set(matcher.group(5))));
        }

        assertEquals(expected.keySet(), actual.keySet());
        for (Map.Entry<String, Spec> entry : expected.entrySet()) {
            String slug = entry.getKey();
            Spec spec = entry.getValue();
            assertEquals(spec, actual.get(slug), slug);

            String advanced = variable(sql, slug, "advanced_config");
            JsonNode normalized = objectMapper.readTree(advancedValidator.validateAndNormalize(advanced));
            assertTrue(advancedValidator.hasEnabledMechanic(advanced), slug);
            assertEquals(spec.mechanics, enabledMechanics(normalized), slug);
            assertTemplateBinding(sql, slug, spec, expected.keySet());
        }

        String preference = variable(sql, "coffee_flavor_personality", "preference_config");
        JsonNode preferenceConfig = preferenceEvaluator.validate(preference);
        assertEquals(2, preferenceConfig.path("dimensions").size());
        assertTrue(preferenceConfig.path("results").has("bright"), "咖啡人格必须有明亮结果分支");
        assertTrue(preferenceConfig.path("results").has("mellow"), "咖啡人格必须有醇厚结果分支");
        assertFalse(preferenceConfig.has("tagOutput"), "风味人格不得复用无关的跨节点标签");
        assertTrue(sql.contains("COALESCE(MIN(create_by), '')"), "同名存量 guard 必须显式拒绝 NULL");
        assertTrue(sql.contains("COALESCE(l.validation_method, -1)"), "回读必须显式拒绝 NULL 验证方式");
        assertFalse(sql.contains("http://"), "种子不得放入临时 HTTP 素材地址");
        // 原断言是「不许有 https:// 远程素材」——那是封面还打在小程序包里时的口径。
        // 封面搬出主包后 https 是常态,守的东西得跟着换:不许出现**我们自己静态目录之外**
        // 的任何主机(否则又会引进没人管、随时 404 的第三方图床)。
        java.util.regex.Matcher hosts = java.util.regex.Pattern
                .compile("https://[^'\\s]+").matcher(sql);
        while (hosts.find()) {
            assertTrue(hosts.group().startsWith("https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/"),
                    "种子只允许引用自有静态目录,发现外部素材地址:" + hosts.group());
        }
        assertEquals(8, count(sql, "'seed_merchant_game_a8_20260823', NOW()"));
    }

    @Test
    void negativeControlRejectsBrokenTimerInsteadOfStayingGreen() throws Exception {
        String valid = variable(readMigration(), "signature_dish_blitz", "advanced_config");
        String broken = valid.replace("\"durationSeconds\":60", "\"durationSeconds\":9");
        assertNotEquals(valid, broken, "负控必须真正破坏计时配置");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> advancedValidator.validateAndNormalize(broken));
        assertEquals("计时时长须为 10 秒至 24 小时", error.getMessage());
    }

    @Test
    void negativeControlRejectsConfigBoundToAnotherTemplate() throws Exception {
        String sql = readMigration();
        String broken = sql.replace(
                "WHERE slug = 'signature_dish_blitz'",
                "WHERE slug = 'home_safety_mines'");
        assertNotEquals(sql, broken, "负控必须真正错绑高级配置");
        Map<String, Spec> specs = expectedSpecs();
        assertThrows(AssertionError.class, () -> assertTemplateBinding(broken,
                "signature_dish_blitz", specs.get("signature_dish_blitz"), specs.keySet()));
    }

    @Test
    void seedBindsOriginalMediaRoundAudioAndStableCategoryNames() throws Exception {
        String sql = readMigration();

        assertTrue(sql.contains("('signature_dish_blitz', '招牌菜限时盲猜', '美食烹饪')"));
        assertTrue(sql.contains("('fitness_reaction_60', '60秒健身知识反应赛', '运动健身')"));
        assertTrue(sql.contains("('rhythm_blind_guess', '节奏盲猜夜', '娱乐达人')"));
        assertTrue(sql.contains("('stain_detective', '污渍侦探', '衣物洗护')"));
        assertTrue(sql.contains("('home_safety_mines', '家居安全排雷', '家政维修')"));
        assertFalse(sql.contains("SET l.category_id = c.category_id"), "商家 type=1 品类不得写入只接受 type=4 的模板货架品类");
        assertTrue(sql.contains("l.activity_categoryids = c.activity_categoryids"));
        assertTrue(sql.contains("SET c.advanced_config = g.advanced_config"), "高级配置必须通过事务白名单允许的单 JOIN 形状回填");
        assertTrue(sql.contains("COALESCE(MIN(category_id), 0) = 0"), "品类解析失败必须 fail closed");

        assertMedia(sql, "signature_dish_blitz", "signature-dish-blitz.jpg");
        assertMedia(sql, "stain_detective", "stain-detective.jpg");
        assertMedia(sql, "home_safety_mines", "home-safety-mines.jpg");

        JsonNode rhythm = objectMapper.readTree(advancedValidator.validateAndNormalize(
                variable(sql, "rhythm_blind_guess", "advanced_config")));
        assertEquals("ELAPSED_TIME", rhythm.path("leaderboard").path("metric").asText());
        assertEquals(3, rhythm.path("random").path("items").size());
        for (JsonNode item : rhythm.path("random").path("items")) {
            String audioUrl = item.path("audioUrl").asText();
            assertTrue(audioUrl.startsWith("/audio/merchant-games/rhythm-"), audioUrl);
            // #1103 主包瘦身:库值仍下发 /audio/merchant-games/*,实体 mp3 已下沉 pages/play 分包,
            // 客户端(merchant-game-assets.js)按同一路径映射播放;文件存在性按分包内路径校验。
            assertTrue(Files.isRegularFile(Paths.get("..", "chengyinhub-xcx", "pages", "play", audioUrl.substring(1))), audioUrl);
        }
    }

    private String readMigration() throws Exception {
        return new String(Files.readAllBytes(MIGRATION), StandardCharsets.UTF_8);
    }

    private void assertMedia(String sql, String slug, String filename) {
        String path = "https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/" + filename;
        assertTrue(sql.contains(path), slug + " 缺少题面素材绑定");
        // 线上封面在 prod-api/profile 静态目录；仓库留母版供追溯与重传，这里校验母版在位。
        assertTrue(Files.isRegularFile(Paths.get("..", "assets", "covers", "merchant-games", filename)),
                "线上封面必须在仓库留有母版:" + filename);
    }

    private String variable(String sql, String slug, String suffix) {
        Pattern pattern = Pattern.compile("INSERT INTO seed_merchant_game_a8_config "
                + "\\(slug, advanced_config, preference_config\\)\\s+VALUES \\('"
                + Pattern.quote(slug) + "', '(.*?)', (NULL|'(.*?)')\\);", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(sql);
        assertTrue(matcher.find(), "缺少配置变量:" + slug + "/" + suffix);
        String value = "advanced_config".equals(suffix) ? matcher.group(1) : matcher.group(3);
        assertTrue(value != null, "缺少配置值:" + slug + "/" + suffix);
        value = value.replace("''", "'");
        assertFalse(matcher.find(), "配置变量只能声明一次:" + slug + "/" + suffix);
        return value;
    }

    private void assertTemplateBinding(String sql, String slug, Spec spec, Set<String> allSlugs) {
        String marker = "-- TEMPLATE " + slug + "|";
        int start = sql.indexOf(marker);
        assertTrue(start >= 0, "缺少模板块:" + slug);
        int end = sql.indexOf("-- TEMPLATE ", start + marker.length());
        if (end < 0) end = sql.indexOf("-- 事务 runner", start);
        assertTrue(end > start, "模板块没有结束边界:" + slug);
        String block = sql.substring(start, end);
        int insertStart = block.indexOf("INSERT INTO cms_template_library");
        assertTrue(insertStart >= 0, "模板块缺少公共库 INSERT:" + slug);
        String insert = block.substring(insertStart);

        assertTrue(insert.contains("SELECT 100000, NULL, '" + spec.title + "'"),
                "INSERT 标题错绑:" + slug);
        assertTrue(insert.contains("(SELECT validation_method FROM seed_merchant_game_a8_expected WHERE title = '"
                + spec.title + "')"), "INSERT 验证方式未绑定 expected 行:" + slug);
        assertTrue(insert.contains("slug = '" + slug + "'"), "INSERT 高级配置错绑:" + slug);
        assertTrue(insert.contains("member_id = 100000 AND title = '" + spec.title + "' AND del_flag = 0"),
                "INSERT 幂等键错绑:" + slug);
        for (String other : allSlugs) {
            if (!slug.equals(other)) {
                assertFalse(insert.contains("slug = '" + other + "'"),
                        "INSERT 引用了其他模板配置:" + slug + " -> " + other);
            }
        }
    }

    private Set<String> enabledMechanics(JsonNode json) {
        Set<String> enabled = new LinkedHashSet<String>();
        for (String mechanic : Arrays.asList("timer", "random", "branch", "leaderboard", "multiplayer")) {
            if (json.path(mechanic).path("enabled").asBoolean(false)) enabled.add(mechanic);
        }
        return enabled;
    }

    private Map<String, Spec> expectedSpecs() {
        Map<String, Spec> result = new LinkedHashMap<String, Spec>();
        result.put("signature_dish_blitz", spec("招牌菜限时盲猜", 3,
                "餐饮,咖啡馆,烘焙甜品", "timer,leaderboard"));
        result.put("coffee_flavor_personality", spec("咖啡风味人格", 6,
                "咖啡馆,茶饮,烘焙甜品", "random,branch"));
        result.put("hidden_menu_clue_chain", spec("隐藏菜单线索链", 3,
                "餐饮,咖啡馆,酒吧", "timer,branch"));
        result.put("fitness_reaction_60", spec("60秒健身知识反应赛", 3,
                "健身房,运动场馆,瑜伽馆", "timer,leaderboard"));
        result.put("rhythm_blind_guess", spec("节奏盲猜夜", 3,
                "KTV,酒吧,音乐餐厅,咖啡馆", "timer,random,leaderboard"));
        result.put("branch_story_relay", spec("分支故事接力", 0,
                "桌游馆,剧本馆,书店,俱乐部", "random,branch,multiplayer"));
        result.put("stain_detective", spec("污渍侦探", 3,
                "洗衣店,衣物洗护,家政", "timer,branch"));
        result.put("home_safety_mines", spec("家居安全排雷", 3,
                "家政维修,装修,物业", "branch"));
        return result;
    }

    private Spec spec(String title, int validationMethod, String merchants, String mechanics) {
        return new Spec(title, validationMethod, set(merchants), set(mechanics));
    }

    private Set<String> set(String csv) {
        return new LinkedHashSet<String>(Arrays.asList(csv.split(",")));
    }

    private int count(String source, String target) {
        int result = 0;
        int from = 0;
        while ((from = source.indexOf(target, from)) >= 0) {
            result++;
            from += target.length();
        }
        return result;
    }

    private static final class Spec {
        private final String title;
        private final int validationMethod;
        private final Set<String> merchantTypes;
        private final Set<String> mechanics;

        private Spec(String title, int validationMethod, Set<String> merchantTypes, Set<String> mechanics) {
            this.title = title;
            this.validationMethod = validationMethod;
            this.merchantTypes = merchantTypes;
            this.mechanics = mechanics;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Spec)) return false;
            Spec spec = (Spec) other;
            return validationMethod == spec.validationMethod && title.equals(spec.title)
                    && merchantTypes.equals(spec.merchantTypes) && mechanics.equals(spec.mechanics);
        }

        @Override
        public int hashCode() {
            int result = title.hashCode();
            result = 31 * result + validationMethod;
            result = 31 * result + merchantTypes.hashCode();
            result = 31 * result + mechanics.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return title + "/vm=" + validationMethod + "/" + merchantTypes + "/" + mechanics;
        }
    }
}
