package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W7 x-template-games:migration_20261116_template_games.sql 里写死的每一份配置都必须真能跑。
 *
 * <p>偏好题组配置不合法时,线上表现是「模板照常上架、玩家点进去报错」,到不了 CI。
 * 这里把每一条偏好题组逐字读出来跑 {@link PreferenceResultEvaluator}:每个选项都要能出一张
 * 唯一的结果卡(偏好题没有对错,任何选择都必须完成),结果卡要引用玩家的选择。
 *
 * <p>负控:把新品盲测局任一选项的 scores 改成空对象,{@code everyPreferenceOptionYieldsItsOwnResult} 应红。
 */
class TemplateGamesSeedContractTest {
    private static final String SQL = "sql/migration_20261116_template_games.sql";
    private final ObjectMapper json = new ObjectMapper();
    private final PreferenceResultEvaluator evaluator = new PreferenceResultEvaluator();

    @Test
    void everyPreferenceOptionYieldsItsOwnResult() throws Exception {
        String text = read();
        Matcher m = Pattern.compile("\\('([^']+)', '(\\{\"dimensions\".*?})'\\)").matcher(text);
        Set<String> titles = new HashSet<>();
        while (m.find()) {
            String title = m.group(1);
            String config = m.group(2);
            titles.add(title);
            evaluator.validate(config);
            JsonNode root = json.readTree(config);
            assertEquals(1, root.path("steps").size(), title + ":货架偏好题只做一步,玩家一次选完");
            JsonNode step = root.path("steps").get(0);
            Set<String> results = new HashSet<>();
            for (JsonNode option : step.path("options")) {
                PreferenceResultEvaluator.Evaluation out = evaluator.evaluate(config,
                        Collections.singletonMap(step.path("key").asText(), option.path("key").asText()));
                assertFalse(out.isNeedsTiebreak(), title + ":单步选择不得打平");
                assertTrue(results.add(out.getResultCode()), title + ":每个选项各出一张结果卡");
            }
            assertEquals(root.path("dimensions").size(), results.size(), title);
        }
        assertEquals(new HashSet<>(java.util.Arrays.asList(
                "新品盲测局", "G1｜现在出状况,你先选什么", "G2｜我的底线三选一", "G5｜值不值得现在处理")), titles,
                "迁移应给 4 个玩法配偏好题组;提取正则或 SQL 结构变了");
    }

    @Test
    void roleDeckIsAValidRandomDraw() throws Exception {
        Matcher m = Pattern.compile("SET advanced_config_json = '(\\{\"schemaVersion\".*?})'").matcher(read());
        assertTrue(m.find(), "找不到今日角色任务的随机角色卡配置");
        AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();
        JsonNode root = json.readTree(validator.validateAndNormalize(m.group(1)));
        assertTrue(validator.hasEnabledMechanic(m.group(1)), "随机卡必须算启用的玩法,否则两端不会先发角色卡");
        assertEquals(1, root.path("random").path("drawCount").asInt());
        assertEquals(4, root.path("random").path("items").size());
        assertFalse(root.path("multiplayer").path("enabled").asBoolean(), "货架模板不默认开多人");
    }

    @Test
    void playerRulesNoLongerCarryOperatorNotes() throws Exception {
        String text = read();
        int start = text.indexOf("INSERT INTO template_games_copy");
        int end = text.indexOf("-- ①", start);
        String block = text.substring(start, end);
        assertFalse(block.contains("运营提示"), "新的玩家规则不得再带运营提示");
    }

    private static String read() throws Exception {
        Path direct = Paths.get(SQL);
        Path path = Files.exists(direct) ? direct : Paths.get("chengyinhub-admin").resolve(SQL);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
