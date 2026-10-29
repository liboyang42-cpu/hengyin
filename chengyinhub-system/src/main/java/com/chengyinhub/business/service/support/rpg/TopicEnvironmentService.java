package com.chengyinhub.business.service.support.rpg;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.service.support.JourneyRpg;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

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
 *
 * <p>RV4 S533-2:刷新发生在检定 / 推进的写事务里,天气源调用一律放到小的有界守护线程池里执行,
 * 最多等 {@code chengyin.rpg.weather.timeout-ms}(默认 {@value #DEFAULT_WEATHER_TIMEOUT_MILLIS}ms);
 * 超时 / 线程池满 / 抛异常都按 unknown 走(与天气源抛异常同一条 fail-open 路径),不占着 DB 连接干等。
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
    public static final long DEFAULT_WEATHER_TIMEOUT_MILLIS = 800L;
    /** 有界:最多 4 个线程、16 个排队;满了直接拒绝 → fail-open,慢天气源不会无限堆积线程。 */
    private static final ThreadPoolExecutor WEATHER_EXECUTOR = newWeatherExecutor();

    private final TopicWeatherProvider weatherProvider;
    private final Clock clock;
    private final long weatherTimeoutMillis;
    private final Map<String, Cached> cache = new ConcurrentHashMap<String, Cached>();

    public TopicEnvironmentService(TopicWeatherProvider weatherProvider) {
        this(weatherProvider, Clock.systemUTC());
    }

    @Autowired
    public TopicEnvironmentService(TopicWeatherProvider weatherProvider,
                                   @Value("${chengyin.rpg.weather.timeout-ms:" + DEFAULT_WEATHER_TIMEOUT_MILLIS + "}")
                                   long weatherTimeoutMillis) {
        this(weatherProvider, Clock.systemUTC(), weatherTimeoutMillis);
    }

    public TopicEnvironmentService(TopicWeatherProvider weatherProvider, Clock clock) {
        this(weatherProvider, clock, DEFAULT_WEATHER_TIMEOUT_MILLIS);
    }

    public TopicEnvironmentService(TopicWeatherProvider weatherProvider, Clock clock, long weatherTimeoutMillis) {
        this.weatherProvider = weatherProvider;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.weatherTimeoutMillis = weatherTimeoutMillis > 0L ? weatherTimeoutMillis : DEFAULT_WEATHER_TIMEOUT_MILLIS;
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
            String raw = weatherProvider == null ? null : boundedLookup(city);
            value = raw != null && WEATHER.contains(raw.trim()) ? raw.trim() : JourneyRpg.ENV_UNKNOWN;
        } catch (RuntimeException providerDown) {
            // fail-open:天气源挂了 / 超时不拦玩法,按 unknown 走;留痕便于排查。
            log.warn("rpg weather provider failed, fallback unknown: {}", providerDown.toString());
            value = JourneyRpg.ENV_UNKNOWN;
        }
        synchronized (cache) {
            if (cache.size() >= MAX_CACHE) cache.clear();
            cache.put(city, new Cached(value, nowMillis));
        }
        return value;
    }

    /** 有界等待天气源;超时取消任务并抛出,由 {@link #weather} 统一 fail-open。 */
    private String boundedLookup(String city) {
        Future<String> future;
        try {
            future = WEATHER_EXECUTOR.submit(() -> weatherProvider.currentWeather(city));
        } catch (RejectedExecutionException busy) {
            throw new IllegalStateException("weather executor saturated", busy);
        }
        try {
            return future.get(weatherTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException slow) {
            future.cancel(true);
            throw new IllegalStateException("weather provider timed out after " + weatherTimeoutMillis + "ms", slow);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("weather lookup interrupted", interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            throw new IllegalStateException("weather provider failed: " + cause, cause);
        }
    }

    private static ThreadPoolExecutor newWeatherExecutor() {
        AtomicInteger seq = new AtomicInteger();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(4, 4, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(16), runnable -> {
                    Thread t = new Thread(runnable, "rpg-weather-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
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
