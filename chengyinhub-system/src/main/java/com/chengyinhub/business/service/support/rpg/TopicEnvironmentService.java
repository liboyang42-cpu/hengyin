package com.chengyinhub.business.service.support.rpg;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.service.support.JourneyRpg;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * F6 T06 环境键:把「此时此地」写成本局状态里的只读快照,供 ENV_IN 条件读取。
 *
 * <ul>
 *   <li>weather:只按主题城市问 {@link TopicWeatherProvider};失败 / 超出取值表 / 没城市 → unknown(fail-open);</li>
 *   <li>daypart / weekpart / season:服务器时钟,按 Asia/Shanghai 计算,不读客户端时间;</li>
 *   <li>主题没开 {@code journeyRules.rpg.environment} → 什么都不写,state_json 不变。</li>
 * </ul>
 *
 * <p>天气按城市缓存 {@link #WEATHER_TTL_MILLIS},避免每个动作都打天气源;缓存只在内存,不落库。
 */
@Service
public class TopicEnvironmentService {
    private static final Logger log = LoggerFactory.getLogger(TopicEnvironmentService.class);

    public static final List<String> WEATHER_VALUES = Collections.unmodifiableList(
            Arrays.asList("clear", "cloudy", "rain", "snow", "fog", "wind", "storm"));
    public static final long WEATHER_TTL_MILLIS = 30L * 60L * 1000L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_CACHE = 512;
    private static final Set<String> WEATHER = new LinkedHashSet<String>(WEATHER_VALUES);

    private final TopicWeatherProvider weatherProvider;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<String, Cached>();

    @Autowired
    public TopicEnvironmentService(TopicWeatherProvider weatherProvider) {
        this(weatherProvider, Clock.systemUTC());
    }

    public TopicEnvironmentService(TopicWeatherProvider weatherProvider, Clock clock) {
        this.weatherProvider = weatherProvider;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** 主题开了环境键时刷新本局快照;没开时原样返回,不碰 state。 */
    public void refresh(CmsTopic topic, JourneyRules rules, TopicRouteEngine.State state) {
        if (state == null || !JourneyRpg.environmentEnabled(rules)) return;
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(ZONE);
        for (String key : JourneyRpg.environmentKeys(rules)) {
            String value;
            switch (key) {
                case JourneyRpg.ENV_WEATHER: value = weather(topic == null ? null : topic.getAddressName()); break;
                case JourneyRpg.ENV_DAYPART: value = daypart(now.getHour()); break;
                case JourneyRpg.ENV_WEEKPART: value = weekpart(now.getDayOfWeek()); break;
                case JourneyRpg.ENV_SEASON: value = season(now.getMonthValue()); break;
                default: value = JourneyRpg.ENV_UNKNOWN;
            }
            state.getEnvironment().put(key, value);
        }
    }

    String weather(String topicCity) {
        String city = topicCity == null ? "" : topicCity.trim();
        if (city.isEmpty()) return JourneyRpg.ENV_UNKNOWN;
        long nowMillis = clock.millis();
        Cached hit = cache.get(city);
        if (hit != null && nowMillis - hit.at < WEATHER_TTL_MILLIS) return hit.value;
        String value;
        try {
            String raw = weatherProvider == null ? null : weatherProvider.currentWeather(city);
            value = raw != null && WEATHER.contains(raw.trim()) ? raw.trim() : JourneyRpg.ENV_UNKNOWN;
        } catch (RuntimeException providerDown) {
            // fail-open:天气源挂了不拦玩法,按 unknown 走;留痕便于排查。
            log.warn("rpg weather provider failed, fallback unknown: {}", providerDown.toString());
            value = JourneyRpg.ENV_UNKNOWN;
        }
        if (cache.size() >= MAX_CACHE) cache.clear();
        cache.put(city, new Cached(value, nowMillis));
        return value;
    }

    static String daypart(int hour) {
        if (hour >= 5 && hour < 8) return "dawn";
        if (hour >= 8 && hour < 12) return "morning";
        if (hour >= 12 && hour < 17) return "afternoon";
        if (hour >= 17 && hour < 20) return "evening";
        return "night";
    }

    static String weekpart(DayOfWeek day) {
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY ? "weekend" : "weekday";
    }

    static String season(int month) {
        if (month >= 3 && month <= 5) return "spring";
        if (month >= 6 && month <= 8) return "summer";
        if (month >= 9 && month <= 11) return "autumn";
        return "winter";
    }

    private static final class Cached {
        private final String value;
        private final long at;
        private Cached(String value, long at) { this.value = value; this.at = at; }
    }
}
