package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JourneyInitialStateTest {
    private static final String CONFIG = "{\"schemaVersion\":1,\"stateEnabled\":true,\"attributes\":["
            + "{\"key\":\"counter.focus\",\"label\":\"专注\",\"initial\":2,\"min\":0,\"max\":8},"
            + "{\"key\":\"tag.awake\",\"label\":\"清醒\",\"initial\":1,\"visible\":false}]}";

    @Test
    void existingEffectsRespectAttributeBoundsAndTagsShareExistingConditionEngine() throws Exception {
        JourneyRules rules = JourneyRules.parse(CONFIG);
        TopicRouteEngine engine = new TopicRouteEngine();
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        JourneyInitialState.initialize(rules, state);
        ObjectMapper json = new ObjectMapper();
        assertTrue(engine.conditionsPass(json.readTree("[{\"op\":\"HAS_TAG\",\"value\":\"tag.awake\"}]"), state));
        engine.applyEffects(json.readTree("[{\"op\":\"INC\",\"var\":\"counter.focus\",\"value\":20}]"), state);
        JourneyInitialState.clamp(rules, state);
        assertEquals(8, state.getVariables().get("counter.focus").intValue());
        engine.applyEffects(json.readTree("[{\"op\":\"SET\",\"var\":\"counter.focus\",\"value\":-20}]"), state);
        JourneyInitialState.clamp(rules, state);
        assertEquals(0, state.getVariables().get("counter.focus").intValue());
    }

    @Test
    void publicConfigurationAndPlayerStateOmitHiddenDefinitions() throws Exception {
        String publicRules = JourneyInitialState.publicRules(CONFIG);
        assertFalse(publicRules.contains("tag.awake"));
        assertFalse(publicRules.contains("清醒"));
        assertTrue(publicRules.contains("专注"));
        JourneyRules rules = JourneyRules.parse(CONFIG);
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        JourneyInitialState.initialize(rules, state);
        assertEquals(1, JourneyInitialState.playerView(rules, state).size());
        assertFalse(JourneyInitialState.publicRules(CONFIG.replace("true", "false")).contains("清醒"));
    }
}
