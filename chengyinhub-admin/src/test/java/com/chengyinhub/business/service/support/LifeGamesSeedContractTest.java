package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W7 x-life-games R12:migration_20261116_life_games.sql 写死的十二份机制都必须真能玩。
 *
 * <ul>
 *   <li>偏好题组逐条跑 {@link PreferenceResultEvaluator}:穷举每一种选法,都要出唯一结果卡(没有对错 = 任何选择都能完成),
 *       每个结果都要能到达,结果卡引用玩家全部选择。</li>
 *   <li>选项题:四个选项齐全、正确项在 A—D 里;能从题面数字重算的(营业时间重叠、每段步行上限、预算、排程)按题面重算,
 *       必须恰好一个选项成立,且就是 correct。</li>
 *   <li>计时配置跑 {@link AdvancedGameConfigValidator}。</li>
 * </ul>
 *
 * <p>负控:把生活 G3 的 correct 改成 'B',{@code timePuzzlesHaveExactlyOneRightOption} 应红;
 * 把生活 G4 任一卡的 scores 改成空对象,{@code everyPreferenceChoiceFinishes} 应红。
 */
class LifeGamesSeedContractTest {
    private static final String SQL = "sql/migration_20261116_life_games.sql";
    private static final String LIFE = "seed_life_backup_games_20260820";
    private static final String CELEBRATE = "seed_celebrate_games_20260821";
    private final ObjectMapper json = new ObjectMapper();
    private final PreferenceResultEvaluator evaluator = new PreferenceResultEvaluator();

    @Test
    void coversTheTwelveShelfGames() throws Exception {
        Set<String> keys = new HashSet<>();
        for (Row row : rows()) keys.add(row.createBy + "/" + row.title);
        assertEquals(new HashSet<>(Arrays.asList(
                LIFE + "/G0｜30秒生活掉线测试", LIFE + "/G3｜营业时间拼图", LIFE + "/G4｜去之前准备什么", LIFE + "/G6｜首选失效怎么办",
                CELEBRATE + "/G0｜30秒今天值得什么", CELEBRATE + "/G1｜每人一个不能", CELEBRATE + "/G2｜气氛卡碰撞",
                CELEBRATE + "/G3｜三小时时间方块", CELEBRATE + "/G4｜每人预算筹码", CELEBRATE + "/G5｜十分钟步行上限",
                CELEBRATE + "/G6｜主选突然掉线", CELEBRATE + "/G7｜60秒共同落款")), keys,
                "迁移应覆盖生活 G0/G3/G4/G6 与庆祝 G0—G7;提取逻辑或 SQL 结构变了");
    }

    @Test
    void everyPreferenceChoiceFinishes() throws Exception {
        int checked = 0;
        for (Row row : rows()) {
            if (row.vm != 6) continue;
            checked++;
            assertNull(row.correct, row.title + ":偏好题没有正确答案");
            evaluator.validate(row.preference);
            JsonNode root = json.readTree(row.preference);
            List<Map<String, String>> picks = new ArrayList<>();
            picks.add(new LinkedHashMap<>());
            for (JsonNode step : root.path("steps")) {
                List<Map<String, String>> next = new ArrayList<>();
                for (Map<String, String> pick : picks) {
                    for (JsonNode option : step.path("options")) {
                        Map<String, String> copy = new LinkedHashMap<>(pick);
                        copy.put(step.path("key").asText(), option.path("key").asText());
                        next.add(copy);
                    }
                }
                picks = next;
            }
            Set<String> reached = new HashSet<>();
            for (Map<String, String> pick : picks) {
                PreferenceResultEvaluator.Evaluation out = evaluator.evaluate(row.preference, pick);
                assertFalse(out.isNeedsTiebreak(), row.title + ":主步骤选完就要出结果,不让玩家再做取舍题 " + pick);
                assertNotNull(out.getResultCode(), row.title);
                assertEquals(root.path("steps").size(), out.getChoices().size(), row.title + ":结果卡引用每一步的选择");
                for (String choice : out.getChoices()) assertTrue(out.getBody().contains(choice), row.title + ":结果卡写出 " + choice);
                reached.add(out.getResultCode());
            }
            assertEquals(root.path("dimensions").size(), reached.size(), row.title + ":每个结果都要能拿到");
        }
        assertEquals(6, checked, "生活 G0/G4 与庆祝 G0/G1/G2/G7 是偏好题组");
    }

    @Test
    void pickThreeIsADiscardOfOneFromFour() throws Exception {
        Row g4 = row(LIFE, "G4｜去之前准备什么");
        JsonNode step = json.readTree(g4.preference).path("steps").get(0);
        assertEquals("discard", step.path("type").asText(), "四张卡去掉一张 = 留下三张");
        assertEquals(4, step.path("options").size());
        for (JsonNode option : step.path("options")) {
            PreferenceResultEvaluator.Evaluation out = evaluator.evaluate(g4.preference,
                    java.util.Collections.singletonMap("drop", option.path("key").asText()));
            for (JsonNode other : step.path("options")) {
                if (other == option) continue;
                assertTrue(out.getBody().contains(other.path("text").asText()), "结果卡列出留下的三张:" + other);
            }
        }
    }

    @Test
    void optionGamesAreComplete() throws Exception {
        int checked = 0;
        for (Row row : rows()) {
            if (row.vm != 3) continue;
            checked++;
            assertNull(row.preference, row.title);
            for (String text : Arrays.asList(row.question, row.a, row.b, row.c, row.d, row.hint1, row.reveal, row.feedback)) {
                assertTrue(text != null && !text.trim().isEmpty(), row.title + ":题面、四个选项、提示、揭示、反馈都要写");
            }
            assertTrue(Arrays.asList("A", "B", "C", "D").contains(row.correct), row.title);
            assertEquals(4, new HashSet<>(Arrays.asList(row.a, row.b, row.c, row.d)).size(), row.title + ":四个选项不能重复");
        }
        assertEquals(6, checked, "生活 G3/G6 与庆祝 G3/G4/G5/G6 是选项题");
    }

    @Test
    void timePuzzlesHaveExactlyOneRightOption() throws Exception {
        // 生活 G3:三段营业时间的交集
        Row g3 = row(LIFE, "G3｜营业时间拼图");
        Matcher w = Pattern.compile("(\\d{1,2}):(\\d{2})–(\\d{1,2}):(\\d{2})").matcher(g3.question);
        int open = 0, close = 24 * 60, windows = 0;
        while (w.find()) {
            windows++;
            open = Math.max(open, minutes(w.group(1), w.group(2)));
            close = Math.min(close, minutes(w.group(3), w.group(4)));
        }
        assertEquals(3, windows);
        final int from = open, to = close;
        assertOnlyCorrect(g3, t -> { int m = clock(t); return m >= from && m < to; });

        // 庆祝 G3:先手作 60 分钟,步行 15 分钟,17:30 开门的晚餐 90 分钟;15:00 开门、最后一节 17:00;三小时内且不空等
        Row block = row(CELEBRATE, "G3｜三小时时间方块");
        assertOnlyCorrect(block, t -> {
            int start = clock(t);
            int arrive = start + 60 + 15;
            int dinner = minutes("17", "30");
            return start >= minutes("15", "00") && start <= minutes("17", "00")
                    && arrive == dinner && dinner + 90 - start <= 180;
        });

        // 庆祝 G5:每段步行都不超过 10 分钟
        Row walk = row(CELEBRATE, "G5｜十分钟步行上限");
        assertOnlyCorrect(walk, t -> {
            Matcher leg = Pattern.compile("(\\d+) 分钟").matcher(t);
            boolean ok = true;
            int legs = 0;
            while (leg.find()) { legs++; ok &= Integer.parseInt(leg.group(1)) <= 10; }
            return legs == 2 && ok;
        });

        // 庆祝 G4:人均价格相加不超过 200
        Row budget = row(CELEBRATE, "G4｜每人预算筹码");
        Map<String, Integer> price = new HashMap<>();
        price.put("晚餐", 120);
        price.put("手作", 98);
        price.put("合影打印", 30);
        assertOnlyCorrect(budget, t -> {
            int sum = 0;
            if (t.contains("三样都要")) sum = 120 + 98 + 30;
            else for (String part : t.split(" \\+ ")) {
                Matcher cheap = Pattern.compile("人均 (\\d+) 元的晚餐").matcher(part);
                sum += cheap.find() ? Integer.parseInt(cheap.group(1)) : price.get(part.trim());
            }
            return sum <= 200;
        });

        // 庆祝 G6:18:00 有位、人均 ≤120、安静,三条同时成立
        Row fallback = row(CELEBRATE, "G6｜主选突然掉线");
        assertOnlyCorrect(fallback, t -> {
            Matcher cost = Pattern.compile("人均 (\\d+) 元").matcher(t);
            return cost.find() && Integer.parseInt(cost.group(1)) <= 120 && t.contains("18:00 有位") && t.contains("安静");
        });

        // 生活 G6:明早 8:00 前能拿到
        Row closed = row(LIFE, "G6｜首选失效怎么办");
        assertOnlyCorrect(closed, t -> t.contains("营业到 21:00") && t.contains("加急 3 小时"));
    }

    @Test
    void timersAreValidAndOnlyOnTheTwoFallbackDrills() throws Exception {
        AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();
        Set<String> timed = new HashSet<>();
        for (Row row : rows()) {
            if (row.timer == null) continue;
            timed.add(row.createBy + "/" + row.title);
            JsonNode root = json.readTree(validator.validateAndNormalize(row.timer));
            assertTrue(validator.hasEnabledMechanic(row.timer), row.title + ":计时要算启用的玩法,两端才会先开计时");
            assertEquals(30, root.path("timer").path("durationSeconds").asInt());
            assertEquals("EXPIRED", root.path("timer").path("timeoutResult").asText(), "超时可以重来,不判死");
        }
        assertEquals(new HashSet<>(Arrays.asList(LIFE + "/G6｜首选失效怎么办", CELEBRATE + "/G6｜主选突然掉线")), timed);
    }

    @Test
    void playerRulesArePlainAndHonest() throws Exception {
        for (Row row : rows()) {
            for (String word : Arrays.asList("规则卡", "员工动作", "商家资产", "商家配置", "原回执", "回读", "lease", "gate",
                    "approval", "capability", "即将开放", "敬请期待")) {
                assertFalse(row.rule.contains(word), row.title + " 的玩家规则不应出现:" + word);
                assertFalse(row.description.contains(word), row.title + " 的简介不应出现:" + word);
            }
            assertFalse(row.guide.trim().isEmpty(), row.title + ":给商家的话放在 merchant_guide");
        }
    }

    private interface OptionRule { boolean holds(String option); }

    private static void assertOnlyCorrect(Row row, OptionRule rule) {
        String[] letters = {"A", "B", "C", "D"};
        String[] options = {row.a, row.b, row.c, row.d};
        List<String> right = new ArrayList<>();
        for (int i = 0; i < 4; i++) if (rule.holds(options[i])) right.add(letters[i]);
        assertEquals(Arrays.asList(row.correct), right, row.title + ":按题面重算,只有正确项成立");
    }

    private static int clock(String text) {
        Matcher m = Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(text);
        assertTrue(m.find(), "选项里没有时间:" + text);
        return minutes(m.group(1), m.group(2));
    }

    private static int minutes(String h, String m) { return Integer.parseInt(h) * 60 + Integer.parseInt(m); }

    private Row row(String createBy, String title) throws Exception {
        for (Row row : rows()) if (row.createBy.equals(createBy) && row.title.equals(title)) return row;
        throw new AssertionError("迁移里没有 " + title);
    }

    private static final class Row {
        String createBy, title, description, rule, guide, question, a, b, c, d, correct, hint1, reveal, feedback, preference, timer;
        int vm;
    }

    /** 读 INSERT INTO life_games_mechanics 的 VALUES:单引号字符串('' 转义)、NULL、整数。 */
    private List<Row> rows() throws Exception {
        String text = read();
        int start = text.indexOf("VALUES", text.indexOf("INSERT INTO life_games_mechanics"));
        int end = text.indexOf(";", text.indexOf("\n    (", start) + 1);
        while (inString(text, start, end)) end = text.indexOf(";", end + 1);
        List<List<String>> tuples = new ArrayList<>();
        List<String> current = null;
        int i = start + "VALUES".length();
        while (i < end) {
            char ch = text.charAt(i);
            if (ch == '(' && current == null) { current = new ArrayList<>(); i++; }
            else if (ch == ')' && current != null) { tuples.add(current); current = null; i++; }
            else if (ch == '\'' && current != null) {
                StringBuilder value = new StringBuilder();
                i++;
                while (true) {
                    char c = text.charAt(i);
                    if (c == '\'' && i + 1 < end && text.charAt(i + 1) == '\'') { value.append('\''); i += 2; }
                    else if (c == '\'') { i++; break; }
                    else { value.append(c); i++; }
                }
                current.add(value.toString());
            } else if (current != null && text.startsWith("NULL", i)) { current.add(null); i += 4; }
            else if (current != null && Character.isDigit(ch)) {
                int j = i;
                while (Character.isDigit(text.charAt(j))) j++;
                current.add(text.substring(i, j));
                i = j;
            } else i++;
        }
        List<Row> out = new ArrayList<>();
        for (List<String> t : tuples) {
            assertEquals(17, t.size(), "每行 17 列:" + t);
            Row r = new Row();
            r.createBy = t.get(0); r.title = t.get(1); r.vm = Integer.parseInt(t.get(2));
            r.description = t.get(3); r.rule = t.get(4); r.guide = t.get(5); r.question = t.get(6);
            r.a = t.get(7); r.b = t.get(8); r.c = t.get(9); r.d = t.get(10); r.correct = t.get(11);
            r.hint1 = t.get(12); r.reveal = t.get(13); r.feedback = t.get(14); r.preference = t.get(15); r.timer = t.get(16);
            out.add(r);
        }
        return out;
    }

    private static boolean inString(String text, int from, int to) {
        boolean in = false;
        for (int i = from; i < to; i++) if (text.charAt(i) == '\'') in = !in;
        return in;
    }

    private static String read() throws Exception {
        Path direct = Paths.get(SQL);
        Path path = Files.exists(direct) ? direct : Paths.get("chengyinhub-admin").resolve(SQL);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
