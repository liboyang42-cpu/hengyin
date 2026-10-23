package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.service.impl.AdvancedGameRuntimeServiceImpl;
import com.chengyinhub.business.service.support.AdvancedGameConfigValidator;
import com.chengyinhub.business.util.AdvancedGamePublicProjection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 呈现方式 present(施工契约 §1.5,2026-09-18 拍板):作者在 advanced_config_json 根层选
 * inline / fullscreen,不由 kit 类型定死。本测试守四件事:
 *
 * <ul>
 *   <li>默认表三档一个段都不许挪档(负控②:挪 qa 的档,本文件立刻红);</li>
 *   <li>不配 present 的存量配置照旧合法且规范化一字不改;</li>
 *   <li>「只能全屏」那批段配 inline 直接拒,绝不静默改回 fullscreen(负控①);</li>
 *   <li>运行期与会话投影下发的是最终生效值(负控③:存量模板投影变了立刻红)。</li>
 * </ul>
 */
class PlayKitPresentConfigTest {

    private final AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();
    private final ObjectMapper json = new ObjectMapper();

    /** 契约 §1.5 第一档:可内嵌、默认内嵌。 */
    private static final String[] INLINE_BY_DEFAULT = {
            "qa", "branch", "predict", "pricePair", "random", "profile", "note", "check", "quietHold" };
    /** 契约 §1.5 第二档:可内嵌、默认全屏(diceRoll 2026-09-18 挪入本档)。 */
    private static final String[] FULLSCREEN_BY_DEFAULT = {
            "estimate", "diyName", "typeIn",
            "dailySign", "slowTask", "musicCorner", "timeWindow", "diceRoll" };
    /** 契约 §1.5 第三档:只能全屏,配 inline 直接拒。compass / shout 为现场感契约 §5.3 新入档。 */
    private static final String[] FULLSCREEN_ONLY = {
            "reaction", "ballShake", "countdown", "stopwatch",
            "coinFlip", "hiddenObject", "scan", "steps", "compass", "shout", "blindTaste" };

    private static String onlySection(String section) {
        return "{\"schemaVersion\":1,\"" + section + "\":{\"enabled\":true}}";
    }

    private static String resolve(String configJson) {
        try {
            return AdvancedGameConfigValidator.resolvePresent(new ObjectMapper().readTree(configJson));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ==================== 默认表三档(负控②) ====================

    @Test
    void 默认表_第一档五段默认内嵌() {
        for (String section : INLINE_BY_DEFAULT) {
            assertEquals(AdvancedGameConfigValidator.PRESENT_INLINE, resolve(onlySection(section)),
                    section + " 必须默认内嵌(契约 §1.5 第一档)");
        }
    }

    @Test
    void 默认表_第二档十一段默认全屏() {
        for (String section : FULLSCREEN_BY_DEFAULT) {
            assertEquals(AdvancedGameConfigValidator.PRESENT_FULLSCREEN, resolve(onlySection(section)),
                    section + " 必须默认全屏(契约 §1.5 第二档)");
        }
    }

    @Test
    void 默认表_第三档九段只能全屏默认也是全屏() {
        for (String section : FULLSCREEN_ONLY) {
            assertEquals(AdvancedGameConfigValidator.PRESENT_FULLSCREEN, resolve(onlySection(section)),
                    section + " 必须全屏(契约 §1.5 第三档)");
        }
    }

    @Test
    void 默认表外的段与空配置回退全屏_与改动前行为一致() {
        for (String section : new String[] { "sort", "match", "classify", "compare", "silentOrder" }) {
            assertEquals(AdvancedGameConfigValidator.PRESENT_FULLSCREEN, resolve(onlySection(section)),
                    section + " 不在契约默认表里,必须回退全屏,不许自己升档");
        }
        assertEquals(AdvancedGameConfigValidator.PRESENT_FULLSCREEN,
                resolve("{\"schemaVersion\":1,\"timer\":{\"enabled\":true,\"durationSeconds\":60}}"));
        assertEquals(AdvancedGameConfigValidator.PRESENT_FULLSCREEN,
                resolve("{\"schemaVersion\":1}"));
    }

    // ==================== 不配 = 合法,存量配置逐字不变 ====================

    /** 存量模板(不带 present):照旧通过,规范化输出一字不改 —— 校验器绝不悄悄注入 present。 */
    @Test
    void 不配present的存量配置照旧通过且规范化一字不改() throws Exception {
        String legacy = "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"title\":\"这题选哪个\","
                + "\"reveal\":true,\"maxTries\":2,\"xp\":10,\"options\":["
                + "{\"id\":\"a\",\"label\":\"对\",\"correct\":true,\"fb\":\"对了\"},"
                + "{\"id\":\"b\",\"label\":\"错\",\"correct\":false,\"fb\":\"再想想\"}]}}";

        assertEquals(legacy, validator.validateAndNormalize(legacy),
                "存量配置过校验器后必须逐字不变(不注入 present)");
        assertTrue(validator.hasEnabledMechanic(legacy));
        assertFalse(validator.validateAndNormalize(legacy).contains("present"));
    }

    // ==================== 显式配值 ====================

    @Test
    void 显式present两值都合法() {
        String qa = "{\"schemaVersion\":1,\"present\":\"inline\",\"qa\":{\"enabled\":true,\"mode\":\"PICK\","
                + "\"title\":\"这题选哪个\",\"maxTries\":2,\"xp\":10,\"options\":["
                + "{\"id\":\"a\",\"label\":\"对\",\"correct\":true},"
                + "{\"id\":\"b\",\"label\":\"错\",\"correct\":false}]}}";
        assertTrue(validator.validateAndNormalize(qa).contains("\"present\":\"inline\""),
                "第一档显式 inline 合法且必须留在规范化输出里");

        assertTrue(validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"present\":\"fullscreen\",\"estimate\":{\"enabled\":true,"
                        + "\"title\":\"猜\",\"unit\":\"颗\",\"answer\":480,\"min\":0,\"max\":1000,"
                        + "\"tolerance\":50,\"xp\":100}}").contains("\"present\":\"fullscreen\""),
                "第二档显式 fullscreen 合法");

        assertTrue(validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"present\":\"inline\",\"sort\":{\"enabled\":true,\"prompt\":\"排一下\","
                        + "\"items\":[{\"id\":\"a\",\"label\":\"甲\"},{\"id\":\"b\",\"label\":\"乙\"}],"
                        + "\"answerOrder\":[\"b\",\"a\"]}}").contains("\"present\":\"inline\""),
                "默认表外的段也允许作者显式内嵌(不属只能全屏那批)");

        validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"present\":\"fullscreen\",\"reaction\":{\"enabled\":true,"
                        + "\"rounds\":3,\"goalMs\":320,\"xp\":10}}");
    }

    /**
     * diceRoll 例外(2026-09-18 用户点名):整屏与内嵌两套写法都保留 —— 后端不许把它当
     * 「只能全屏」拒,默认仍是 fullscreen,但 present=inline 必须放行并真的投影出去。
     * 负控:把 diceRoll 放回 FULLSCREEN_ONLY,本用例立刻红。
     */
    @Test
    void diceRoll两种写法都放行_默认全屏显式内嵌合法() throws Exception {
        String dice = "{\"schemaVersion\":1,\"diceRoll\":{\"enabled\":true,\"diceCount\":2,\"xp\":10,"
                + "\"faces\":[\"一面\",\"二面\",\"三面\",\"四面\",\"五面\",\"六面\"]}}";
        assertEquals(AdvancedGameConfigValidator.PRESENT_FULLSCREEN, resolve(dice), "diceRoll 默认全屏");
        assertEquals(AdvancedGameConfigValidator.PRESENT_FULLSCREEN, sessionView(dice).get("present"));

        String inline = withPresent(dice, "inline");
        assertTrue(validator.validateAndNormalize(inline).contains("\"present\":\"inline\""),
                "diceRoll 配 present=inline 必须放行,不许当只能全屏拒");
        assertEquals(AdvancedGameConfigValidator.PRESENT_INLINE, sessionView(inline).get("present"),
                "显式内嵌必须投影出去");
        assertEquals(AdvancedGameConfigValidator.PRESENT_INLINE,
                json.readTree(AdvancedGamePublicProjection.safeConfigJson(inline)).path("present").asText(),
                "公开投影同样带内嵌");
    }

    @Test
    void 不认识的值直接拒() {
        for (String bad : new String[] { "\"embedded\"", "\"INLINE\"", "\"full_screen\"", "\"\"" }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> validator.validateAndNormalize(
                            "{\"schemaVersion\":1,\"present\":" + bad + ",\"qa\":{\"enabled\":true}}"),
                    "present=" + bad + " 必须被拒");
            assertTrue(error.getMessage().contains("present"), error.getMessage());
        }
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(
                        "{\"schemaVersion\":1,\"present\":1,\"qa\":{\"enabled\":true}}"));
    }

    // ==================== 负控①:只能全屏配 inline 真拒 ====================

    /**
     * 负控:把 validatePresent 里对「只能全屏」那批的拒绝对掉,本用例立刻红。
     * 拒绝必须点名段名 + 说清理由 —— 静默改回 fullscreen 会让作者与玩家两边都不知道哪错了。
     */
    @Test
    void 负控_只能全屏的段配inline必须拒且说清理由() {
        for (String section : FULLSCREEN_ONLY) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> validator.validateAndNormalize(onlySection(section).replace(
                            "{\"schemaVersion\":1,", "{\"schemaVersion\":1,\"present\":\"inline\",")),
                    section + " 配 present=inline 必须被拒");
            assertTrue(error.getMessage().contains(section), error.getMessage());
            assertTrue(error.getMessage().contains("只能全屏"), error.getMessage());
            assertTrue(error.getMessage().contains("整屏"), error.getMessage());
        }
    }

    /** 「只能全屏」段与别的段同开时同样拒,不许因为存在可内嵌段就放行。 */
    @Test
    void 负控_只能全屏段与可内嵌段同开配inline也必须拒() {
        String config = "{\"schemaVersion\":1,\"present\":\"inline\","
                + "\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"maxTries\":2,\"xp\":10,\"options\":["
                + "{\"id\":\"a\",\"label\":\"对\",\"correct\":true},"
                + "{\"id\":\"b\",\"label\":\"错\",\"correct\":false}]},"
                + "\"reaction\":{\"enabled\":true,\"rounds\":3,\"goalMs\":320,\"xp\":10}}";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(config));
        assertTrue(error.getMessage().contains("reaction"), error.getMessage());
    }

    // ==================== 负控③:投影下发最终生效值 ====================

    @Test
    void 运行期会话视图下发最终生效的present() {
        assertEquals(AdvancedGameConfigValidator.PRESENT_INLINE, sessionView(legacyQaConfig()).get("present"),
                "存量模板(不带 present)的投影必须按默认表推出 inline");
        assertEquals(AdvancedGameConfigValidator.PRESENT_FULLSCREEN,
                sessionView(legacyEstimateConfig()).get("present"),
                "第二档存量模板必须推出 fullscreen");
        assertEquals(AdvancedGameConfigValidator.PRESENT_FULLSCREEN,
                sessionView("{\"schemaVersion\":1,\"reaction\":{\"enabled\":true,\"rounds\":3,"
                        + "\"goalMs\":320,\"xp\":10}}").get("present"),
                "只能全屏的段必须推出 fullscreen");
        assertEquals(AdvancedGameConfigValidator.PRESENT_FULLSCREEN,
                sessionView(withPresent(legacyQaConfig(), "fullscreen")).get("present"),
                "作者显式配的必须压过默认表");
        assertEquals(AdvancedGameConfigValidator.PRESENT_INLINE,
                sessionView(withPresent(legacyEstimateConfig(), "inline")).get("present"),
                "第二档作者想内嵌就内嵌");
    }

    @Test
    void 公开投影也带最终生效present_客户端不重推默认表() throws Exception {
        JsonNode qa = json.readTree(AdvancedGamePublicProjection.safeConfigJson(legacyQaConfig()));
        assertEquals(AdvancedGameConfigValidator.PRESENT_INLINE, qa.path("present").asText());

        JsonNode estimate = json.readTree(AdvancedGamePublicProjection.safeConfigJson(legacyEstimateConfig()));
        assertEquals(AdvancedGameConfigValidator.PRESENT_FULLSCREEN, estimate.path("present").asText());

        JsonNode explicit = json.readTree(AdvancedGamePublicProjection.safeConfigJson(
                withPresent(legacyQaConfig(), "fullscreen")));
        assertEquals(AdvancedGameConfigValidator.PRESENT_FULLSCREEN, explicit.path("present").asText());
    }

    private static String withPresent(String configJson, String present) {
        return configJson.replace("{\"schemaVersion\":1,", "{\"schemaVersion\":1,\"present\":\"" + present + "\",");
    }

    private static String legacyQaConfig() {
        return "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"reveal\":true,"
                + "\"maxTries\":2,\"xp\":10,\"options\":["
                + "{\"id\":\"a\",\"label\":\"对\",\"correct\":true,\"fb\":\"对了\"},"
                + "{\"id\":\"b\",\"label\":\"错\",\"correct\":false,\"fb\":\"再想想\"}]}}";
    }

    private static String legacyEstimateConfig() {
        return "{\"schemaVersion\":1,\"estimate\":{\"enabled\":true,\"title\":\"猜一猜\",\"unit\":\"颗\","
                + "\"answer\":480,\"min\":0,\"max\":1000,\"tolerance\":50,\"xp\":100}}";
    }

    /** 走 /api/play/advanced/state 同一条视图构造:present 是顶层下发字段。 */
    private Map<String, Object> sessionView(String configJson) {
        GameRuntimeSessionMapper sessionMapper = mock(GameRuntimeSessionMapper.class);
        AdvancedGameRuntimeServiceImpl service = new AdvancedGameRuntimeServiceImpl();
        com.chengyinhub.business.service.impl.AdvancedActionTestFixtures.transactions(service);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        GameRuntimeSession session = new GameRuntimeSession();
        session.setId(51L);
        session.setConfigSnapshotJson(configJson);
        session.setStateJson("{}");
        session.setStatus(GameRuntimeSession.STATUS_RUNNING);
        session.setVersion(0);
        when(sessionMapper.selectAccessibleById(51L, 7L)).thenReturn(session);
        return service.state(7L, 51L);
    }
}
