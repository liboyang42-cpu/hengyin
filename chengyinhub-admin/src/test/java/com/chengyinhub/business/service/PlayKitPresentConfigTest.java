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
 * 呈现方式 present(施工契约 §1.5):作者在 advanced_config_json 根层选 inline / fullscreen。
 * 2026-10-30 业主裁决「所有玩法都内嵌」:原三档默认表合成一档,任何玩法默认内嵌、也都允许显式全屏。本测试守四件事:
 *
 * <ul>
 *   <li>每一个玩法段不配 present 时都推出 inline(负控②:漏一个段,本文件立刻红);</li>
 *   <li>不配 present 的存量配置照旧合法且规范化一字不改;没有玩法段时回退 fullscreen;</li>
 *   <li>每一个玩法段配 inline / fullscreen 都合法,不再有「只能全屏」的拒绝(负控①);</li>
 *   <li>运行期与会话投影下发的是最终生效值,作者显式 fullscreen 压过默认(负控③)。</li>
 * </ul>
 */
class PlayKitPresentConfigTest {

    private final AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();
    private final ObjectMapper json = new ObjectMapper();

    /** 全部玩法段(与 AdvancedGameConfigValidator.PRESENT_SECTIONS 同口径)。 */
    private static final String[] GAME_SECTIONS = {
            "qa", "branch", "predict", "pricePair", "random", "profile", "note", "check", "quietHold",
            "estimate", "diyName", "typeIn", "dailySign", "slowTask", "musicCorner", "timeWindow", "diceRoll",
            "reaction", "ballShake", "countdown", "stopwatch", "coinFlip", "hiddenObject", "scan", "steps",
            "compass", "shout", "blindTaste", "silentOrder", "sort", "match", "classify", "compare", "photoCheck", "album" };

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

    // ==================== 默认一律内嵌(负控②) ====================

    @Test
    void 每个玩法段不配present都默认内嵌() {
        for (String section : GAME_SECTIONS) {
            assertEquals(AdvancedGameConfigValidator.PRESENT_INLINE, resolve(onlySection(section)),
                    section + " 必须默认内嵌(2026-10-30 所有玩法都内嵌)");
        }
    }

    @Test
    void 没有玩法段时回退全屏_与改动前行为一致() {
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

    /** diceRoll 两套写法都保留:默认内嵌(故事流里直接掷),显式 fullscreen 走整屏摇一摇,两者都要投影出去。 */
    @Test
    void diceRoll两种写法都放行_默认内嵌显式全屏合法() throws Exception {
        String dice = "{\"schemaVersion\":1,\"diceRoll\":{\"enabled\":true,\"diceCount\":2,\"xp\":10,"
                + "\"faces\":[\"一面\",\"二面\",\"三面\",\"四面\",\"五面\",\"六面\"]}}";
        assertEquals(AdvancedGameConfigValidator.PRESENT_INLINE, resolve(dice), "diceRoll 默认内嵌");
        assertEquals(AdvancedGameConfigValidator.PRESENT_INLINE, sessionView(dice).get("present"));

        String full = withPresent(dice, "fullscreen");
        assertTrue(validator.validateAndNormalize(full).contains("\"present\":\"fullscreen\""));
        assertEquals(AdvancedGameConfigValidator.PRESENT_FULLSCREEN, sessionView(full).get("present"),
                "显式全屏必须投影出去");
        assertEquals(AdvancedGameConfigValidator.PRESENT_FULLSCREEN,
                json.readTree(AdvancedGamePublicProjection.safeConfigJson(full)).path("present").asText(),
                "公开投影同样带全屏");
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

    // ==================== 负控①:不再有「只能全屏」 ====================

    /** 负控:谁把「只能全屏」的拒绝加回 validatePresent,本用例立刻红。 */
    @Test
    void 每个玩法段配inline和fullscreen都合法() {
        for (String section : GAME_SECTIONS) {
            for (String present : new String[] { "inline", "fullscreen" }) {
                String config = onlySection(section).replace("{\"schemaVersion\":1,",
                        "{\"schemaVersion\":1,\"present\":\"" + present + "\",");
                assertEquals(present, resolve(config), section + " 显式 " + present + " 必须生效");
            }
        }
        validator.validateAndNormalize("{\"schemaVersion\":1,\"present\":\"inline\","
                + "\"reaction\":{\"enabled\":true,\"rounds\":3,\"goalMs\":320,\"xp\":10}}");
    }

    // ==================== 负控③:投影下发最终生效值 ====================

    @Test
    void 运行期会话视图下发最终生效的present() {
        assertEquals(AdvancedGameConfigValidator.PRESENT_INLINE, sessionView(legacyQaConfig()).get("present"),
                "存量模板(不带 present)的投影必须按默认表推出 inline");
        assertEquals(AdvancedGameConfigValidator.PRESENT_INLINE,
                sessionView(legacyEstimateConfig()).get("present"),
                "原默认全屏的存量模板现在推出 inline");
        assertEquals(AdvancedGameConfigValidator.PRESENT_INLINE,
                sessionView("{\"schemaVersion\":1,\"reaction\":{\"enabled\":true,\"rounds\":3,"
                        + "\"goalMs\":320,\"xp\":10}}").get("present"),
                "原只能全屏的段现在同样推出 inline");
        assertEquals(AdvancedGameConfigValidator.PRESENT_FULLSCREEN,
                sessionView(withPresent(legacyQaConfig(), "fullscreen")).get("present"),
                "作者显式配的必须压过默认表");
        assertEquals(AdvancedGameConfigValidator.PRESENT_INLINE,
                sessionView(withPresent(legacyEstimateConfig(), "inline")).get("present"),
                "显式内嵌照样生效");
    }

    @Test
    void 公开投影也带最终生效present_客户端不重推默认表() throws Exception {
        JsonNode qa = json.readTree(AdvancedGamePublicProjection.safeConfigJson(legacyQaConfig()));
        assertEquals(AdvancedGameConfigValidator.PRESENT_INLINE, qa.path("present").asText());

        JsonNode estimate = json.readTree(AdvancedGamePublicProjection.safeConfigJson(legacyEstimateConfig()));
        assertEquals(AdvancedGameConfigValidator.PRESENT_INLINE, estimate.path("present").asText());

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
