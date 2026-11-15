package com.chengyinhub.business.service.support.rpg;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.WeatherEventRule;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.WeatherEventRuleMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopicWeatherEventServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void evaluatesPlayerEnvironmentEventsAndWeatherLockReasons() {
        // 2026-11-14T12:00:00Z = Beijing 20:00 (evening), Saturday (weekend), November (autumn)
        Clock evening = Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), java.time.ZoneOffset.UTC);
        TopicEnvironmentService envService = new TopicEnvironmentService(city -> "rain", evening);
        WeatherEventRuleMapper ruleMapper = mock(WeatherEventRuleMapper.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);

        WeatherEventRule hitRule = new WeatherEventRule();
        hitRule.setId(1L);
        hitRule.setTopicId(101L);
        hitRule.setRuleName("雨夜彩蛋");
        hitRule.setConditionKey("weather");
        hitRule.setConditionValues("[\"rain\",\"storm\"]");
        hitRule.setUnlockNodeId(5001L);
        hitRule.setTaskLabel("雨夜茶馆支线");
        hitRule.setWindowStart("18:00");
        hitRule.setWindowEnd("02:00");
        hitRule.setEnabled(1);

        WeatherEventRule lockedRule = new WeatherEventRule();
        lockedRule.setId(2L);
        lockedRule.setTopicId(101L);
        lockedRule.setRuleName("晴天露天点位");
        lockedRule.setConditionKey("weather");
        lockedRule.setConditionValues("[\"clear\"]");
        lockedRule.setUnlockNodeId(5002L);
        lockedRule.setTaskLabel("露台集章");
        lockedRule.setEnabled(1);

        when(ruleMapper.selectActiveByTopicId(101L)).thenReturn(List.of(hitRule, lockedRule));

        CmsTopic topic = new CmsTopic();
        topic.setId(101L);
        topic.setAddressName("杭州");
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"stateEnabled\":true,\"rpg\":{\"environment\":{\"enabled\":true,\"keys\":[\"weather\",\"daypart\",\"weekpart\",\"season\"]}}}");

        TopicWeatherEventService service = new TopicWeatherEventService(envService, ruleMapper, topicMapper, evening);
        Map<String, Object> view = service.playerEnvironmentView(topic);
        assertEquals("AVAILABLE", view.get("sourceStatus"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) view.get("events");
        assertEquals(2, events.size());
        assertEquals(Boolean.TRUE, events.get(0).get("active"));
        assertEquals(Boolean.FALSE, events.get(1).get("active"));

        Map<Long, String> locks = service.weatherLockedNodes(topic);
        assertFalse(locks.containsKey(5001L));
        assertTrue(locks.containsKey(5002L));
        assertTrue(locks.get(5002L).contains("晴"));
    }

    @Test
    void marksFallbackUnknownWhenWeatherProviderFails() {
        Clock noon = Clock.fixed(Instant.parse("2026-11-14T04:00:00Z"), java.time.ZoneOffset.UTC);
        TopicEnvironmentService envService = new TopicEnvironmentService(city -> null, noon);
        WeatherEventRuleMapper ruleMapper = mock(WeatherEventRuleMapper.class);
        when(ruleMapper.selectActiveByTopicId(102L)).thenReturn(List.of());
        CmsTopic topic = new CmsTopic();
        topic.setId(102L);
        topic.setAddressName("杭州");
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"stateEnabled\":true,\"rpg\":{\"environment\":{\"enabled\":true}}}");

        TopicWeatherEventService service = new TopicWeatherEventService(envService, ruleMapper, mock(CmsTopicMapper.class), noon);
        Map<String, Object> view = service.playerEnvironmentView(topic);
        assertEquals("FALLBACK_UNKNOWN", view.get("sourceStatus"));
        assertTrue(String.valueOf(view.get("fallbackReason")).contains("不可用"));
        @SuppressWarnings("unchecked")
        Map<String, String> env = (Map<String, String>) view.get("environment");
        assertEquals("unknown", env.get("weather"));
    }

    @Test
    void adminSaveConfigValidatesAndPersistsJourneyRulesAndRules() throws Exception {
        Clock noon = Clock.fixed(Instant.parse("2026-11-14T04:00:00Z"), java.time.ZoneOffset.UTC);
        TopicEnvironmentService envService = new TopicEnvironmentService(city -> "clear", noon);
        WeatherEventRuleMapper ruleMapper = mock(WeatherEventRuleMapper.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        when(topicMapper.updateCmsTopic(any(CmsTopic.class))).thenAnswer(inv -> {
            return 1;
        });

        CmsTopic topic = new CmsTopic();
        topic.setId(103L);
        topic.setAddressName("苏州");
        topic.setJourneyRulesJson("");

        TopicWeatherEventService service = new TopicWeatherEventService(envService, ruleMapper, topicMapper, noon);
        TopicWeatherEventService.RuleInput rule = new TopicWeatherEventService.RuleInput();
        rule.setRuleName("雨天茶馆");
        rule.setConditionKey("weather");
        rule.setConditionValues(List.of("rain", "storm"));
        rule.setUnlockNodeId(88L);
        rule.setTaskLabel("限定线索");
        rule.setWindowStart("09:00");
        rule.setWindowEnd("18:00");
        rule.setEnabled(true);

        TopicWeatherEventService.SaveRequest req = new TopicWeatherEventService.SaveRequest();
        req.setEnabled(true);
        req.setKeys(List.of("weather", "daypart"));
        req.setRules(List.of(rule));

        service.adminSaveConfig(topic, req);
        verify(ruleMapper).softDeleteByTopicId(103L);
        verify(ruleMapper).insertRule(any(WeatherEventRule.class));
        JsonNode savedRules = JSON.readTree(topic.getJourneyRulesJson());
        assertTrue(savedRules.path("rpg").path("environment").path("enabled").asBoolean());
        assertEquals(2, savedRules.path("rpg").path("environment").path("keys").size());

        TopicWeatherEventService.RuleInput bad = new TopicWeatherEventService.RuleInput();
        bad.setConditionKey("weather");
        bad.setConditionValues(List.of("lava"));
        TopicWeatherEventService.SaveRequest badReq = new TopicWeatherEventService.SaveRequest();
        badReq.setEnabled(true);
        badReq.setKeys(List.of("weather"));
        badReq.setRules(List.of(bad));
        assertThrows(IllegalArgumentException.class, () -> service.adminSaveConfig(topic, badReq));
    }

    @Test
    void matchesOvernightAndSameDayWindows() {
        assertTrue(TopicWeatherEventService.matchesWindow(LocalTime.of(23, 30), "20:00", "04:00"));
        assertTrue(TopicWeatherEventService.matchesWindow(LocalTime.of(2, 0), "20:00", "04:00"));
        assertFalse(TopicWeatherEventService.matchesWindow(LocalTime.of(12, 0), "20:00", "04:00"));
        assertTrue(TopicWeatherEventService.matchesWindow(LocalTime.of(12, 0), "09:00", "17:00"));
        assertFalse(TopicWeatherEventService.matchesWindow(LocalTime.of(18, 0), "09:00", "17:00"));
    }
}
