package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class A03RuntimeEndingCompatibilityTest {
    private ObjectNode config(String cases) {
        return ThemeRuntimeConfig.parse("{\"schemaVersion\":1,\"endings\":[" + cases
                + ",{\"id\":\"fallback\",\"title\":\"旧兜底\",\"fallback\":true}]}");
    }
    private String end(ObjectNode config) {
        ThemeRuntimeEngine engine = new ThemeRuntimeEngine();
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        engine.initialize(config, state);
        engine.terminal(config, state, new JourneyRules(), "COMPLETED", 1L);
        return state.getThemeRuntime().path("result").path("endingId").asText();
    }
    @Test void missingPriorityKeepsLastMatchAndDoesNotGainArrayIndexPriority() {
        ObjectNode config = config("{\"id\":\"first\",\"title\":\"先\"},{\"id\":\"last\",\"title\":\"后\"}");
        assertEquals("last", end(config));
        assertFalse(config.path("endings").get(0).has("priority"));
    }
    @Test void explicitPriorityWinsAndEqualPriorityKeepsLastMatch() {
        assertEquals("first", end(config("{\"id\":\"first\",\"title\":\"先\",\"priority\":2},{\"id\":\"last\",\"title\":\"后\",\"priority\":1}")));
        assertEquals("last", end(config("{\"id\":\"first\",\"title\":\"先\",\"priority\":2},{\"id\":\"last\",\"title\":\"后\",\"priority\":2}")));
    }
    @Test void noConditionMatchUsesFallback() {
        assertEquals("fallback", end(config("{\"id\":\"blocked\",\"title\":\"不命中\",\"when\":[{\"op\":\"GT\",\"var\":\"sys.hp\",\"value\":999}]}")));
    }
    @Test void frozenConfigDoesNotFollowAuthorChanges() {
        ObjectNode original = config("{\"id\":\"old\",\"title\":\"旧结局\"}");
        ObjectNode frozen = ThemeRuntimeConfig.freeze(original, 0, 0L);
        ((ObjectNode) original.path("endings").get(0)).put("id", "new");
        assertEquals("old", end(frozen));
        assertEquals("new", end(original));
    }
    @Test void unknownNewConditionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> config("{\"id\":\"bad\",\"title\":\"未知\",\"when\":[{\"op\":\"FUTURE\"}]}"));
    }
}
