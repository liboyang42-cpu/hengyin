package com.chengyinhub.business.service.support;

import com.chengyinhub.business.util.StateKeyRefs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 共享契约 v4 特例:创作者效果里只放行 sys.luck 一个服务端保留变量(INC 1–3),
 * 且它不进 B1 键注册表。R14 起 sys.hp / sys.luck / sys.asked / sys.mistakes / sys.outcome
 * 作为**只读**系统变量允许出现在条件里,效果里仍然只放行 sys.luck。
 */
class JourneySysLuckConfigTest {

    private final AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();

    @Test
    void sysLuckIncEffectIsAcceptedInBlockEffects() {
        String normalized = validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"effects\":[{\"op\":\"INC\",\"var\":\"sys.luck\",\"value\":2}]}");

        assertTrue(normalized.contains("\"sys.luck\""));
    }

    @Test
    void sysLuckIncEffectIsAcceptedInCheckEffects() {
        String normalized = validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"check\":{\"enabled\":true,\"checkId\":\"c1\",\"tier\":\"easy\","
                        + "\"successEffects\":[{\"op\":\"INC\",\"var\":\"sys.luck\",\"value\":1}]}}");

        assertTrue(normalized.contains("\"sys.luck\""));
    }

    @Test
    void sysLuckValueOutsideOneToThreeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"effects\":[{\"op\":\"INC\",\"var\":\"sys.luck\",\"value\":4}]}"));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"effects\":[{\"op\":\"INC\",\"var\":\"sys.luck\",\"value\":0}]}"));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"effects\":[{\"op\":\"INC\",\"var\":\"sys.luck\",\"value\":-1}]}"));
    }

    @Test
    void sysLuckWithSetOpIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"effects\":[{\"op\":\"SET\",\"var\":\"sys.luck\",\"value\":2}]}"));
    }

    @Test
    void otherSysVarsStayRejected() {
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"effects\":[{\"op\":\"INC\",\"var\":\"sys.hp\",\"value\":1}]}"));
    }

    /** R14 运行期缺口 3:sys.luck 作为只读系统变量可以在条件里引用(效果里只放行 INC 1–3)。 */
    @Test
    void sysLuckConditionIsReadableSinceR14() {
        assertNotNull(validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"requires\":[{\"var\":\"sys.luck\",\"op\":\"GTE\",\"value\":1}]}"));
    }

    @Test
    void stateKeyRefsDoesNotCollectSysLuckButKeepsOtherKeys() {
        java.util.Set<String> keys = StateKeyRefs.collect(
                "{\"effects\":[{\"op\":\"INC\",\"var\":\"sys.luck\",\"value\":2},"
                        + "{\"op\":\"INC\",\"var\":\"counter.visits\",\"value\":1}]}");

        assertFalse(keys.contains("sys.luck"), "sys.luck 是服务端保留变量,不进键注册表");
        assertTrue(keys.contains("counter.visits"));
    }
}
