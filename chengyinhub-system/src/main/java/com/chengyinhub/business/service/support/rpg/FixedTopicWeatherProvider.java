package com.chengyinhub.business.service.support.rpg;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 默认天气源:不联网、不付费、无密钥。
 *
 * <p>{@code chengyin.rpg.weather.fixed} 留空(默认)= 一律未知(no-op);配成某个天气代码 =
 * 所有主题都返回这个固定值(联调 / 演示用)。要接真实天气源时另写一个 {@link TopicWeatherProvider}
 * 实现并标 {@code @Primary},本类不动。
 */
@Component
public class FixedTopicWeatherProvider implements TopicWeatherProvider {

    private final String fixed;

    public FixedTopicWeatherProvider(@Value("${chengyin.rpg.weather.fixed:}") String fixed) {
        this.fixed = fixed == null ? "" : fixed.trim();
    }

    @Override
    public String currentWeather(String topicCity) {
        return fixed.isEmpty() ? null : fixed;
    }
}
