package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
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

class MerchantGamePortfolio35ContractTest {

    private static final Path A8 = Paths.get("sql/migration_seed_merchant_game_a8_20260823.sql");
    private static final Path B27 = Paths.get("sql/migration_seed_merchant_game_b27_20260823.sql");
    private static final Path REPORT = Paths.get("../docs/research/merchant-game-portfolio-35-implementation-20260823.md");
    private static final Path MEDIA_REPORT = Paths.get("../docs/research/merchant-game-media-provenance-20260823.md");
    // 封面已搬出小程序包(主包 2MB 硬限)：线上走 prod-api/profile 静态目录，
    // 母版留在仓库 assets/covers/ 供追溯与重传。这里校验的是母版。
    private static final Path MERCHANT_IMAGE_DIR = Paths.get("../assets/covers/merchant-games");
    private static final Path PLAY_JS = Paths.get("../chengyinhub-xcx/pages/play/index.js");
    private static final Path PLAY_WXML = Paths.get("../chengyinhub-xcx/pages/play/index.wxml");
    private static final Pattern MARKER = Pattern.compile(
            "^-- TEMPLATE ([a-z0-9_]+)\\|([^|]+)\\|([^|]+)\\|([^\\r\\n]+)$",
            Pattern.MULTILINE);
    private static final Pattern ANY_TITLE = Pattern.compile("^-- TEMPLATE [^|]+\\|([^|]+)\\|", Pattern.MULTILINE);

    private final AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void secondBatchCompletesExactlyThirtyFivePlayableTemplatesAcrossEightCategories() throws Exception {
        String a8 = read(A8);
        String b27 = read(B27);
        Map<String, Spec> expected = expectedSpecs();

        assertPortfolio(b27, expected);

        Set<String> allTitles = new LinkedHashSet<String>();
        Matcher firstBatch = ANY_TITLE.matcher(a8);
        while (firstBatch.find()) assertTrue(allTitles.add(firstBatch.group(1)), "A8 标题重复");
        Matcher secondBatch = MARKER.matcher(b27);
        while (secondBatch.find()) assertTrue(allTitles.add(secondBatch.group(2)), "35 组合标题重复:" + secondBatch.group(2));
        assertEquals(35, allTitles.size());
        String report = read(REPORT);
        for (String title : allTitles) assertTrue(report.contains(title), "实施报告缺少模板:" + title);

        Map<String, Integer> totals = new LinkedHashMap<String, Integer>();
        totals.put("美食烹饪", 3);
        totals.put("运动健身", 1);
        totals.put("娱乐达人", 2);
        totals.put("生活美学", 0);
        totals.put("礼品纪念", 0);
        totals.put("衣物洗护", 1);
        totals.put("家政维修", 1);
        totals.put("出行服务", 0);
        for (Spec spec : expected.values()) totals.put(spec.category, totals.get(spec.category) + 1);
        assertEquals(Integer.valueOf(6), totals.get("美食烹饪"));
        assertEquals(Integer.valueOf(5), totals.get("运动健身"));
        assertEquals(Integer.valueOf(5), totals.get("娱乐达人"));
        assertEquals(Integer.valueOf(5), totals.get("生活美学"));
        assertEquals(Integer.valueOf(4), totals.get("礼品纪念"));
        assertEquals(Integer.valueOf(3), totals.get("衣物洗护"));
        assertEquals(Integer.valueOf(3), totals.get("家政维修"));
        assertEquals(Integer.valueOf(4), totals.get("出行服务"));
    }

    @Test
    void portfolioDoesNotPretendUnsupportedCrossVisitOrPrivateSocialMechanicsExist() throws Exception {
        String sql = read(B27);
        assertFalse(sql.contains("周连胜"));
        assertFalse(sql.contains("全馆共同里程碑"));
        assertFalse(sql.contains("同步揭晓"));
        assertFalse(sql.contains("公开投票"));
        assertFalse(sql.contains("隐藏身份"));
        assertFalse(sql.contains("正确完赛"), "B27 validation_method=0 不得承诺正确性榜单");
        assertFalse(sql.contains("正确完成后按"), "B27 validation_method=0 不得承诺正确性榜单");
        assertFalse(sql.contains("按正确用时"), "B27 validation_method=0 不得承诺正确性榜单");
        assertTrue(sql.contains("单场内"), "未补周期账本前必须明确单场边界");
        assertTrue(sql.contains("不记录体重 BMI 或热量"), "健身模板必须保留隐私边界");
        assertTrue(sql.contains("不以消费额排名"), "出行小队任务不得变成消费竞赛");
    }

    @Test
    void negativeControlMissingOneTemplateMustTurnPortfolioGateRed() throws Exception {
        String sql = read(B27);
        String broken = sql.replaceFirst("(?m)^-- TEMPLATE table_flavor_relay\\|[^\\r\\n]+\\r?\\n", "");
        assertNotEquals(sql, broken);
        assertThrows(AssertionError.class, () -> assertPortfolio(broken, expectedSpecs()));
    }

    @Test
    void negativeControlInvalidAdvancedConfigMustTurnValidatorRed() throws Exception {
        String sql = read(B27);
        String valid = config(sql, "detail_observation_blitz");
        String broken = valid.replace("\"durationSeconds\":90", "\"durationSeconds\":9");
        assertNotEquals(valid, broken);
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(broken));
    }

    @Test
    void allTemplatesReuseTheExistingPlayPageShareLoop() throws Exception {
        String javascript = read(PLAY_JS);
        String wxml = read(PLAY_WXML);
        assertTrue(javascript.contains("onShareAppMessage(event)"));
        assertTrue(javascript.contains("onShareTimeline()"));
        assertTrue(javascript.contains("saveFinishCard()"));
        assertTrue(javascript.contains("shareFinish()"));
        assertTrue(wxml.contains("bindtap=\"saveFinishCard\""));
        assertTrue(wxml.contains("bindtap=\"shareFinish\""));
    }

    @Test
    void everyTemplateHasOneDistinctRealPhotoAssetWithProvenance() throws Exception {
        assertMediaPortfolio(read(A8), read(B27));
    }

    @Test
    void negativeControlMissingOnePhotoMappingMustTurnMediaGateRed() throws Exception {
        String b27 = read(B27);
        String broken = b27.replaceFirst(
                "(?m)^\\s*\\('table_flavor_relay', 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/table-flavor-relay\\.jpg'\\),\\r?\\n", "");
        assertNotEquals(b27, broken);
        assertThrows(AssertionError.class, () -> assertMediaPortfolio(read(A8), broken));
    }

    private void assertMediaPortfolio(String a8, String b27) throws Exception {
        Pattern a8Pattern = Pattern.compile("WHEN '([^']+)' THEN '(https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/[^']+\\.jpg)'");
        Pattern b27Pattern = Pattern.compile("\\('([a-z0-9_]+)', '(https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/[^']+\\.jpg)'\\)");
        Map<String, String> a8Media = new LinkedHashMap<String, String>();
        Matcher a8Matcher = a8Pattern.matcher(a8);
        while (a8Matcher.find()) {
            String previous = a8Media.put(a8Matcher.group(1), a8Matcher.group(2));
            assertTrue(previous == null || previous.equals(a8Matcher.group(2)), "A8 同一模板媒体路径冲突:" + a8Matcher.group(1));
        }
        assertEquals(8, a8Media.size());
        assertFalse(a8.contains("AI 生成"), "真实摄影素材迁移不得残留 AI 生成声明");

        Map<String, String> b27Media = new LinkedHashMap<String, String>();
        Matcher b27Matcher = b27Pattern.matcher(b27);
        while (b27Matcher.find()) {
            assertTrue(b27Media.put(b27Matcher.group(1), b27Matcher.group(2)) == null,
                    "B27 媒体映射重复:" + b27Matcher.group(1));
        }
        assertEquals(expectedSpecs().keySet(), b27Media.keySet());
        assertEquals(27, b27Media.size());
        assertTrue(a8.contains("l.story_img = CASE l.title"));
        assertTrue(b27.contains("img_url, players"));
        assertTrue(b27.contains("story_text, story_img"));
        assertTrue(b27.contains("SET s.image_path = m.image_path"));
        assertTrue(b27.contains("l.story_img = s.image_path"));

        Set<String> paths = new LinkedHashSet<String>();
        paths.addAll(a8Media.values());
        paths.addAll(b27Media.values());
        assertEquals(35, paths.size(), "35 款模板必须一款一张图，不复用路径");

        String provenance = read(MEDIA_REPORT);
        Set<String> imageDigests = new LinkedHashSet<String>();
        Set<String> sourceUrls = new LinkedHashSet<String>();
        Map<String, String> pathToTitle = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : a8Media.entrySet()) {
            pathToTitle.put(entry.getValue(), entry.getKey());
        }
        Map<String, Spec> b27Specs = expectedSpecs();
        for (Map.Entry<String, String> entry : b27Media.entrySet()) {
            pathToTitle.put(entry.getValue(), b27Specs.get(entry.getKey()).title);
        }
        for (String webPath : paths) {
            String filename = webPath.substring(webPath.lastIndexOf('/') + 1);
            Path imagePath = MERCHANT_IMAGE_DIR.resolve(filename);
            assertTrue(Files.isRegularFile(imagePath), "缺少图片文件:" + filename);
            assertTrue(Files.size(imagePath) <= 60 * 1024, "单图超过 60 KiB 包体预算:" + filename);
            BufferedImage image = ImageIO.read(imagePath.toFile());
            assertTrue(image != null, "不是可解码图片:" + filename);
            assertEquals(640, image.getWidth(), filename);
            assertEquals(480, image.getHeight(), filename);
            String digest = sha256(imagePath);
            String rowPattern = "(?m)^\\|[^\\r\\n]*\\| " + Pattern.quote(pathToTitle.get(webPath))
                    + " \\| `" + Pattern.quote(filename) + "` \\|[^\\r\\n]*"
                    + "(https://www\\.pexels\\.com/photo/[^/)]+/)\\) \\| `"
                    + digest + "` \\|$";
            Matcher provenanceRow = Pattern.compile(rowPattern).matcher(provenance);
            assertTrue(provenanceRow.find(),
                    "溯源清单题名、文件、作品页或哈希未在同一行对齐:" + filename);
            imageDigests.add(digest);
            sourceUrls.add(provenanceRow.group(1));
        }
        assertEquals(35, imageDigests.size(), "35 款必须使用 35 份不同图片内容");
        assertEquals(35, sourceUrls.size(), "35 款必须对应 35 个不同 Pexels 作品页");
        assertEquals(35, countOccurrences(provenance, "https://www.pexels.com/photo/"));
        assertTrue(provenance.contains("https://www.pexels.com/license/"));
        assertFalse(Files.exists(MERCHANT_IMAGE_DIR.resolve("signature-dish-clue.jpg")));
        assertFalse(Files.exists(MERCHANT_IMAGE_DIR.resolve("stain-coffee.jpg")));
        assertFalse(Files.exists(MERCHANT_IMAGE_DIR.resolve("home-exit-hazard.jpg")));
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private String sha256(Path path) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        StringBuilder result = new StringBuilder(hash.length * 2);
        for (byte value : hash) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private void assertPortfolio(String sql, Map<String, Spec> expected) throws Exception {
        Map<String, Spec> actual = new LinkedHashMap<String, Spec>();
        Matcher matcher = MARKER.matcher(sql);
        while (matcher.find()) {
            String slug = matcher.group(1);
            assertFalse(actual.containsKey(slug), "B27 slug 重复:" + slug);
            actual.put(slug, spec(matcher.group(2), matcher.group(3), matcher.group(4)));
        }
        assertEquals(expected, actual);

        for (Map.Entry<String, Spec> entry : expected.entrySet()) {
            String slug = entry.getKey();
            Spec spec = entry.getValue();
            String advanced = config(sql, slug);
            JsonNode normalized = objectMapper.readTree(validator.validateAndNormalize(advanced));
            assertTrue(validator.hasEnabledMechanic(advanced), slug);
            assertEquals(spec.mechanics, enabledMechanics(normalized), slug);
            assertTrue(advanced.contains(expectedSemanticTokens().get(slug)), "玩法内容仍是占位文案:" + slug);
            assertFalse(advanced.contains("从公开信息里寻找第一条线索"), "随机卡仍是通用占位:" + slug);
            assertFalse(advanced.contains("从细节开始"), "分支仍是通用占位:" + slug);
            Integer drawCount = expectedDrawCounts().get(slug);
            if (drawCount != null) assertEquals(drawCount.intValue(), normalized.path("random").path("drawCount").asInt(), slug);
            if (normalized.path("branch").path("enabled").asBoolean(false)) {
                Set<String> targets = new LinkedHashSet<String>();
                JsonNode steps = normalized.path("branch").path("steps");
                for (JsonNode option : steps.get(0).path("options")) targets.add(option.path("nextStepId").asText());
                assertEquals(2, targets.size(), "两个选择必须抵达不同结局:" + slug);
                int terminals = 0;
                for (JsonNode step : steps) if (step.path("terminal").asBoolean(false)) terminals++;
                assertEquals(2, terminals, "分支模板必须提供两个可见结局:" + slug);
            }
            if ("gym_charity_sprint".equals(slug)) {
                assertEquals(4, normalized.path("multiplayer").path("requiredTurns").asInt(), slug);
            }
            assertTrue(sql.contains("('" + slug + "', '" + spec.title + "', '" + spec.category + "'"),
                    "规格表未绑定:" + slug);
        }
        assertTrue(sql.contains("JOIN seed_merchant_game_b27_spec s ON s.slug = c.slug"));
        assertTrue(sql.contains("SET l.activity_categoryids = s.activity_categoryids"));
        assertFalse(sql.contains("SET l.category_id"), "type=1 商家品类不得写货架 type=4 category_id");
        assertTrue(sql.contains("COALESCE(MIN(activity_categoryids), '') = ''"));
        assertTrue(sql.contains("COALESCE(MIN(create_by), '')"));
    }

    private String config(String sql, String slug) {
        Pattern pattern = Pattern.compile("INSERT INTO seed_merchant_game_b27_config "
                + "\\(slug, advanced_config\\)\\s+VALUES \\('" + Pattern.quote(slug)
                + "', '(.*?)'\\);", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(sql);
        assertTrue(matcher.find(), "缺少 B27 配置:" + slug);
        String value = matcher.group(1).replace("''", "'");
        assertFalse(matcher.find(), "B27 配置重复:" + slug);
        return value;
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
        result.put("table_flavor_relay", spec("同桌口味接力", "美食烹饪", "random,branch,multiplayer"));
        result.put("table_mission_bingo", spec("一桌任务宾果", "美食烹饪", "random,multiplayer"));
        result.put("menu_co_creation", spec("菜单共创接力", "美食烹饪", "random,branch,multiplayer"));
        result.put("team_energy_relay", spec("小队能量接力", "运动健身", "multiplayer,leaderboard"));
        result.put("partner_training_relay", spec("搭子训练接力", "运动健身", "timer,multiplayer,leaderboard"));
        result.put("movement_bingo", spec("动作宾果", "运动健身", "random,multiplayer"));
        result.put("gym_charity_sprint", spec("全馆公益热身接力", "运动健身", "timer,multiplayer,leaderboard"));
        result.put("song_mood_relay", spec("点歌情绪接力", "娱乐达人", "random,branch,multiplayer"));
        result.put("performance_bingo", spec("表演任务宾果", "娱乐达人", "random,multiplayer"));
        result.put("original_role_mystery", spec("原创角色协作局", "娱乐达人", "branch,multiplayer"));
        result.put("style_personality_lab", spec("风格人格实验室", "生活美学", "random,branch"));
        result.put("color_co_creation", spec("色彩共创接力", "生活美学", "random,branch,multiplayer"));
        result.put("detail_observation_blitz", spec("细节观察挑战", "生活美学", "timer,branch,leaderboard"));
        result.put("self_care_bingo", spec("日常护理宾果", "生活美学", "random,branch"));
        result.put("space_styling_challenge", spec("空间搭配挑战", "生活美学", "random,branch,leaderboard"));
        result.put("duo_memory_relay", spec("双人纪念故事接力", "礼品纪念", "random,multiplayer"));
        result.put("gift_match_guess", spec("礼物偏好猜想局", "礼品纪念", "branch,multiplayer"));
        result.put("keepsake_treasure_clues", spec("纪念物线索寻宝", "礼品纪念", "timer,branch"));
        result.put("festival_story_puzzle", spec("节日故事拼图", "礼品纪念", "random,branch,multiplayer"));
        result.put("clothing_care_bingo", spec("衣物护理宾果", "衣物洗护", "random,branch"));
        result.put("seasonal_storage_challenge", spec("换季收纳挑战", "衣物洗护", "timer,branch,leaderboard"));
        result.put("repair_tool_guess", spec("维修工具盲猜", "家政维修", "timer,random,leaderboard"));
        result.put("family_safety_check_relay", spec("家庭安全检查接力", "家政维修", "random,multiplayer"));
        result.put("city_sidequest_observation", spec("城市支线观察局", "出行服务", "random,branch"));
        result.put("travel_partner_decisions", spec("旅伴决策接力", "出行服务", "branch,multiplayer"));
        result.put("travel_safety_bingo", spec("安全观察宾果", "出行服务", "random,branch"));
        result.put("route_badge_story", spec("路线纪念章故事局", "出行服务", "random,branch,multiplayer"));
        return result;
    }

    private Map<String, String> expectedSemanticTokens() {
        Map<String, String> result = new LinkedHashMap<String, String>();
        result.put("table_flavor_relay", "清爽词卡");
        result.put("table_mission_bingo", "菜单侦探");
        result.put("menu_co_creation", "香气灵感");
        result.put("team_energy_relay", "安全口令员");
        result.put("partner_training_relay", "训练读题者");
        result.put("movement_bingo", "热身格");
        result.put("gym_charity_sprint", "公益口令员");
        result.put("song_mood_relay", "热烈时刻");
        result.put("performance_bingo", "一句台词");
        result.put("original_role_mystery", "前台钟比实际时间慢五分钟");
        result.put("style_personality_lab", "留白关键词");
        result.put("color_co_creation", "暖色词");
        result.put("detail_observation_blitz", "先观察形状");
        result.put("self_care_bingo", "敏感情境");
        result.put("space_styling_challenge", "留白空间");
        result.put("duo_memory_relay", "第一次见面");
        result.put("gift_match_guess", "猜实用陪伴");
        result.put("keepsake_treasure_clues", "材质线索");
        result.put("festival_story_puzzle", "灯亮之前");
        result.put("clothing_care_bingo", "标签格");
        result.put("seasonal_storage_challenge", "先清洁再收纳");
        result.put("repair_tool_guess", "测量工具");
        result.put("family_safety_check_relay", "用电观察");
        result.put("city_sidequest_observation", "建筑线索");
        result.put("travel_partner_decisions", "稳妥预案");
        result.put("travel_safety_bingo", "路线格");
        result.put("route_badge_story", "街巷词卡");
        return result;
    }

    private Map<String, Integer> expectedDrawCounts() {
        Map<String, Integer> result = new LinkedHashMap<String, Integer>();
        result.put("table_mission_bingo", 3);
        result.put("movement_bingo", 3);
        result.put("performance_bingo", 3);
        result.put("self_care_bingo", 3);
        result.put("festival_story_puzzle", 3);
        result.put("clothing_care_bingo", 3);
        result.put("repair_tool_guess", 3);
        result.put("family_safety_check_relay", 3);
        result.put("travel_safety_bingo", 3);
        return result;
    }

    private Spec spec(String title, String category, String mechanics) {
        return new Spec(title, category, new LinkedHashSet<String>(Arrays.asList(mechanics.split(","))));
    }

    private String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static final class Spec {
        private final String title;
        private final String category;
        private final Set<String> mechanics;

        private Spec(String title, String category, Set<String> mechanics) {
            this.title = title;
            this.category = category;
            this.mechanics = mechanics;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Spec)) return false;
            Spec spec = (Spec) other;
            return title.equals(spec.title) && category.equals(spec.category) && mechanics.equals(spec.mechanics);
        }

        @Override
        public int hashCode() {
            int result = title.hashCode();
            result = 31 * result + category.hashCode();
            result = 31 * result + mechanics.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return title + "/" + category + "/" + mechanics;
        }
    }
}
