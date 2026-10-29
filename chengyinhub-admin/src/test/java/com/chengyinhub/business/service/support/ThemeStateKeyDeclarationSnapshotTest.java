package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.ThemeStateKey;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class ThemeStateKeyDeclarationSnapshotTest {
    @Test void capturesTheFullAuthorDeclarationAndPreservesNullDefaults() {
        ThemeStateKey key = key("counter.energy");
        key.setInitialValue(3); key.setVisible(false); key.setRulesJson("{\"levels\":[]}");
        JsonNode row = ThemeStateKeyDeclarationSnapshot.capture(10L, Collections.singletonList(key)).get(0);
        assertEquals(3, row.path("initialValue").asInt());
        assertFalse(row.path("visible").asBoolean());
        for (String field : Arrays.asList("minValue", "maxValue", "displayType", "color", "icon", "sortOrder", "persistScope"))
            assertTrue(row.has(field) && row.path(field).isNull(), field);
        assertEquals("{\"levels\":[]}", row.path("rulesJson").asText());
        assertFalse(row.has("id")); assertFalse(row.has("createTime")); assertFalse(row.has("updateTime"));
        assertFalse(row.has("variables")); assertFalse(row.has("valuesJson"));
    }

    @Test void keepsOriginalDeclarationOrderWithoutHashingTechnicalIdsOrTimes() {
        ThemeStateKey first = key("counter.z"); ThemeStateKey second = key("counter.a");
        JsonNode original = ThemeStateKeyDeclarationSnapshot.capture(10L, Arrays.asList(first, second));
        first.setId(17L); first.setCreateTime(new Date()); first.setUpdateTime(new Date());
        assertEquals(original, ThemeStateKeyDeclarationSnapshot.capture(10L, Arrays.asList(first, second)));
        assertEquals("counter.z", original.get(0).path("stateKey").asText());
        first.setLabel("changed");
        assertNotEquals(original, ThemeStateKeyDeclarationSnapshot.capture(10L, Arrays.asList(first, second)));
    }

    @Test void rejectsMissingCrossTopicAndDuplicateDeclarations() {
        assertThrows(IllegalStateException.class, () -> ThemeStateKeyDeclarationSnapshot.capture(10L, null));
        ThemeStateKey key = key("counter.energy");
        assertThrows(IllegalStateException.class, () -> ThemeStateKeyDeclarationSnapshot.capture(11L, Collections.singletonList(key)));
        assertThrows(IllegalStateException.class, () -> ThemeStateKeyDeclarationSnapshot.capture(10L, Arrays.asList(key, key)));
        key.setStateKey(null);
        assertThrows(IllegalStateException.class, () -> ThemeStateKeyDeclarationSnapshot.capture(10L, Collections.singletonList(key)));
    }

    @Test void rulesLabelOverlayKeepsTheRegisteredTypeRangeAndPersistenceDefinition() {
        ThemeStateKey key = key("counter.energy"); key.setLabel("registry"); key.setInitialValue(7);
        key.setMinValue(-3); key.setMaxValue(21); key.setPersistScope("topic"); key.setVisible(false);
        var rules = com.chengyinhub.business.domain.JourneyRules.parse("{\"schemaVersion\":1,\"stateEnabled\":true,\"attributes\":["
                + "{\"key\":\"counter.energy\",\"label\":\"author label\",\"initial\":4,\"min\":0,\"max\":10},"
                + "{\"key\":\"counter.extra\",\"label\":\"extra\",\"initial\":2,\"min\":1,\"max\":9,\"visible\":false}]}");
        var complete = ThemeStateKeyDeclarationSnapshot.includingRuleAttributes(10L, Collections.singletonList(key), rules);
        assertEquals("author label", complete.get(0).getLabel());
        assertEquals("counter", complete.get(0).getKeyType()); assertEquals("run", complete.get(0).getScope());
        assertEquals(-3, complete.get(0).getMinValue()); assertEquals(21, complete.get(0).getMaxValue());
        assertEquals(7, complete.get(0).getInitialValue()); assertEquals("topic", complete.get(0).getPersistScope());
        assertFalse(complete.get(0).getVisible()); assertEquals("registry", key.getLabel());
        assertEquals("counter", complete.get(1).getKeyType()); assertEquals(2, complete.get(1).getInitialValue());
        assertEquals(1, complete.get(1).getMinValue()); assertEquals(9, complete.get(1).getMaxValue());
        assertFalse(complete.get(1).getVisible());
        var frozen = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        JourneyInitialState.freezeKeys(frozen, Collections.singletonList(key), rules, 10L);
        assertEquals(1, frozen.path("stateKeySnapshotVersion").asInt());
        assertEquals("counter.energy", frozen.path("stateKeys").get(0).path("key").asText());
        assertEquals("counter", frozen.path("stateKeys").get(0).path("keyType").asText());
        assertEquals("topic", frozen.path("stateKeys").get(0).path("persistScope").asText());
        assertEquals(-3, frozen.path("stateKeys").get(0).path("minValue").asInt());
    }

    private ThemeStateKey key(String name) {
        ThemeStateKey key = new ThemeStateKey(); key.setTopicId(10L); key.setStateKey(name);
        key.setKeyType("counter"); key.setScope("run"); key.setLabel(name); key.setInitialValue(0); return key;
    }
}
