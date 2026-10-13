package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfigDraftSourceTest {
    static final String RAW = "{\"schemaVersion\":1,\"capacity\":0,\"configSource\":{\"schemaVersion\":1,\"style\":{\"id\":1,\"updatedAt\":\"2026-10-13\",\"name\":\"通用风格\",\"toneTokens\":{\"--cy-color-text-primary\":\"#112233\"}},\"world\":{\"id\":2,\"updatedAt\":\"\",\"quests\":[{\"id\":3,\"name\":\"来源任务\",\"targetBizId\":7}]}}}";

    @Test void immutableSourceSurvivesRuntimeRemappingAndRunFreeze() {
        ObjectNode draft = ThemeRuntimeConfig.parseDraft(RAW);
        ObjectNode frozen = ThemeRuntimeConfig.freeze(draft, 1, 0);
        ((ObjectNode) frozen.path("configSource").path("style")).put("name", "独立副本");
        assertEquals("通用风格", draft.path("configSource").path("style").path("name").asText());
        String withReference = RAW.substring(0, RAW.length()-1) + ",\"events\":[{\"id\":\"hello\",\"content\":\"广播\",\"when\":[{\"op\":\"NODE_COMPLETED\",\"nodeId\":7}]}]}";
        ObjectNode remapped = ThemeRuntimeConfig.parseDraft(ThemeRuntimeConfig.remapNodes(withReference, Collections.singletonMap(7L, 70L)));
        assertEquals(70, remapped.path("events").get(0).path("when").get(0).path("nodeId").asInt());
        assertEquals(7, remapped.path("configSource").path("world").path("quests").get(0).path("targetBizId").asInt());
        assertEquals(draft.path("configSource"), remapped.path("configSource"));
    }
    @Test void onlyNewContentCanAdoptOrChangeSourceAndMissingFieldDoesNotEraseIt() {
        ConfigDraftSource.assertSourceUnchanged(RAW, null);
        ConfigDraftSource.assertSourceUnchanged(RAW, RAW);
        ConfigDraftSource.assertSourceUnchanged(null, "{\"schemaVersion\":1}");
        assertThrows(IllegalArgumentException.class, () -> ConfigDraftSource.assertSourceUnchanged(null, RAW));
        assertThrows(IllegalArgumentException.class, () -> ConfigDraftSource.assertSourceUnchanged(RAW, ""));
        assertThrows(IllegalArgumentException.class, () -> ConfigDraftSource.assertSourceUnchanged(RAW, RAW.replace("通用风格", "新风格")));
    }
    @Test void missingMalformedOversizedAndExecutableSourceFieldsAreRejected() {
        for (String raw : new String[]{RAW.replace("#112233", "url(x)"), RAW.replace("--cy-color-text-primary", "--cy-safe-top"),
                RAW.replace("\"style\":", "\"secret\":"), RAW.replace("\"id\":1", "\"id\":0"),
                "{\"schemaVersion\":1,\"configSource\":null}", "{\"schemaVersion\":1,\"configSource\":{\"schemaVersion\":1}}"})
            assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parseDraft(raw));
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parseDraft(RAW.replace("通用风格", String.join("", Collections.nCopies(10001, "x")))));
    }
}
