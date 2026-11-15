package com.chengyinhub.business.service.support.rpg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * TEST-only {@link TopicWeatherProvider} active only under profile {@code e2e-test} AND
 * {@code chengyin.e2e.test-providers=true}. Makes zero outbound HTTP calls and deterministically
 * maps topic city/address hints to {@link TopicEnvironmentService#WEATHER_VALUES}.
 */
@Component
@Primary
@Profile("e2e-test")
@ConditionalOnProperty(name = "chengyin.e2e.test-providers", havingValue = "true")
public class E2eTestTopicWeatherProvider implements TopicWeatherProvider {

    private static final Logger log = LoggerFactory.getLogger(E2eTestTopicWeatherProvider.class);
    private static final Set<String> VALID = new LinkedHashSet<String>(TopicEnvironmentService.WEATHER_VALUES);

    private final String fixedOverride;

    public E2eTestTopicWeatherProvider(@Value("${chengyin.rpg.weather.fixed:}") String fixedOverride) {
        this.fixedOverride = fixedOverride == null ? "" : fixedOverride.trim();
        log.warn("[e2e-test] TEST-only TopicWeatherProvider active: offline deterministic weather enabled");
    }

    @Override
    public String currentWeather(String topicCity) {
        if (topicCity == null || topicCity.trim().isEmpty()) {
            return null;
        }
        if (!fixedOverride.isEmpty()) {
            return VALID.contains(fixedOverride) ? fixedOverride : null;
        }
        String city = topicCity.trim().toLowerCase(java.util.Locale.ROOT);
        if (city.contains("storm") || city.contains("暴") || city.contains("雷")) {
            return "storm";
        }
        if (city.contains("rain") || city.contains("雨")) {
            return "rain";
        }
        if (city.contains("snow") || city.contains("雪")) {
            return "snow";
        }
        if (city.contains("fog") || city.contains("雾") || city.contains("霾")) {
            return "fog";
        }
        if (city.contains("wind") || city.contains("风")) {
            return "wind";
        }
        if (city.contains("cloud") || city.contains("云") || city.contains("阴")) {
            return "cloudy";
        }
        return "clear";
    }
}
