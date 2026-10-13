package com.chengyinhub.business.service.support;

import com.chengyinhub.business.util.AdvancedGamePublicProjection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * R16-DUO 双人视角(roleViews)配置合同:
 * 校验只放行「恰好 A、B 两个角色、每个角色恰好一个 view、文本 ≤200、items ≤8」;
 * 公开投影(模板详情等无局场景的玩家可见面)一个字都不许出现。
 */
class RoleViewsConfigTest {

    private static final String ROLES = "[{\"id\":\"A\",\"label\":\"看实物\"},"
            + "{\"id\":\"B\",\"label\":\"查档案\"}]";

    private static final String VIEWS = "["
            + "{\"roleId\":\"A\",\"title\":\"A 的实物清单\",\"body\":\"先看实物\","
            + "\"items\":[{\"label\":\"铜牌\",\"text\":\"边缘磨亮了\"}]},"
            + "{\"roleId\":\"B\",\"title\":\"B 的档案摘录\",\"body\":\"再查档案\","
            + "\"items\":[{\"label\":\"旧账\",\"text\":\"第三页缺角\"}]}]";

    private final AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static String config(String roles, String views) {
        return "{\"schemaVersion\":1,\"roleViews\":{\"enabled\":true,"
                + "\"roles\":" + roles + ",\"views\":" + views + "}}";
    }

    private static String text(int length) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < length; i++) builder.append('字');
        return builder.toString();
    }

    private static String items(int count) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) builder.append(',');
            builder.append("{\"label\":\"物").append(i).append("\",\"text\":\"记\"}");
        }
        return builder.append(']').toString();
    }

    @Test
    void validRoleViewsPassesAndKeepsSection() throws Exception {
        String normalized = validator.validateAndNormalize(config(ROLES, VIEWS));

        assertNotNull(normalized);
        JsonNode json = objectMapper.readTree(normalized);
        assertEquals(2, json.path("roleViews").path("roles").size());
        assertEquals("看实物", json.path("roleViews").path("roles").get(0).path("label").asText());
        assertEquals(2, json.path("roleViews").path("views").size());
        assertEquals("铜牌",
                json.path("roleViews").path("views").get(0).path("items").get(0).path("label").asText());
    }

    /** 缺省 / enabled=false = 老配置照旧:这一段不存在也不校验。 */
    @Test
    void absentOrDisabledRoleViewsIsLegacyConfig() {
        assertNotNull(validator.validateAndNormalize("{\"schemaVersion\":1}"));
        assertNotNull(validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"roleViews\":{\"enabled\":false,\"roles\":[],\"views\":[]}}"));
    }

    /** 负控:roles 不是恰好 A、B 两个(1 个 / 3 个 / id 不是 A、B / 重复)全部拦下。 */
    @Test
    void roleSetMustBeExactlyAAndB() {
        for (String roles : Arrays.asList(
                "[{\"id\":\"A\",\"label\":\"看实物\"}]",
                "[{\"id\":\"A\",\"label\":\"看实物\"},{\"id\":\"B\",\"label\":\"查档案\"},"
                        + "{\"id\":\"C\",\"label\":\"第三人\"}]",
                "[{\"id\":\"A\",\"label\":\"看实物\"},{\"id\":\"C\",\"label\":\"查档案\"}]",
                "[{\"id\":\"A\",\"label\":\"看实物\"},{\"id\":\"A\",\"label\":\"又是 A\"}]")) {
            assertThrows(IllegalArgumentException.class,
                    () -> validator.validateAndNormalize(config(roles, VIEWS)),
                    "该 roles 配置应被拒绝:" + roles);
        }
    }

    /** 负控:每个角色恰好一个 view —— 缺一个 / 同一个角色配两个 / 引用不存在的角色都拦下。 */
    @Test
    void eachRoleNeedsExactlyOneView() {
        String aOnly = "[{\"roleId\":\"A\",\"title\":\"实物\"}]";
        String duplicated = "[{\"roleId\":\"A\",\"title\":\"实物\"},{\"roleId\":\"A\",\"title\":\"又是实物\"}]";
        String foreign = "[{\"roleId\":\"A\",\"title\":\"实物\"},{\"roleId\":\"C\",\"title\":\"外来的\"}]";
        for (String views : Arrays.asList(aOnly, duplicated, foreign)) {
            assertThrows(IllegalArgumentException.class,
                    () -> validator.validateAndNormalize(config(ROLES, views)),
                    "该 views 配置应被拒绝:" + views);
        }
    }

    /** 负控:文本 >200 与 items >8 是硬上限,200 / 8 是放行边界。 */
    @Test
    void textCapTwoHundredAndItemsCapEight() {
        assertNotNull(validator.validateAndNormalize(config(ROLES,
                "[{\"roleId\":\"A\",\"title\":\"" + text(200) + "\"},"
                        + "{\"roleId\":\"B\",\"title\":\"档案\"}]")));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(config(ROLES,
                "[{\"roleId\":\"A\",\"title\":\"" + text(201) + "\"},"
                        + "{\"roleId\":\"B\",\"title\":\"档案\"}]")));

        assertNotNull(validator.validateAndNormalize(config(ROLES,
                "[{\"roleId\":\"A\",\"items\":" + items(8) + "},{\"roleId\":\"B\",\"title\":\"档案\"}]")));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(config(ROLES,
                "[{\"roleId\":\"A\",\"items\":" + items(9) + "},{\"roleId\":\"B\",\"title\":\"档案\"}]")));

        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(config(ROLES,
                "[{\"roleId\":\"A\",\"items\":[{\"label\":\"铜牌\"}]},{\"roleId\":\"B\",\"title\":\"档案\"}]")));
    }

    /**
     * 公开投影(模板详情 / 列表页)绝不下发 roleViews:它是按角色在运行期下发的那一半,
     * 提前印在货架上等于把对方那一半也剧透了。
     *
     * <p>负控:给 AdvancedGamePublicProjection 加一个 copyRoleViews,本用例立刻红。
     */
    @Test
    void publicProjectionNeverCarriesRoleViews() {
        String safe = AdvancedGamePublicProjection.safeConfigJson(config(ROLES, VIEWS));

        assertNotNull(safe);
        String lower = safe.toLowerCase();
        List<String> forbidden = Arrays.asList("roleviews", "看实物", "查档案",
                "a 的实物清单", "b 的档案摘录", "铜牌", "旧账");
        for (String token : forbidden) {
            assertFalse(lower.contains(token.toLowerCase()),
                    "公开投影出现了角色视角内容 " + token + ":" + safe);
        }
    }
}
