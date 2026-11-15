package com.chengyinhub.business.service.support.rpg;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.service.TencentMapClient;
import com.chengyinhub.common.config.RestoreDispatchGuard;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 生产环境真实天气源：复用 {@link TencentMapClient}，先按城市解析中心坐标并长期缓存，
 * 再调用腾讯天气实况（/ws/weather/v1/?type=now），并按城市缓存结果、映射为 {@link TopicEnvironmentService#WEATHER_VALUES}。
 *
 * <p>在 TEST / {@code e2e-test} 激活时由 {@code E2eTestTopicWeatherProvider} 接管，避免无 Key 时重复告警。
 */
@Component
@Primary
@Profile("!e2e-test")
@ConditionalOnProperty(name = "tencent.map.enabled", havingValue = "true")
public class TencentTopicWeatherProvider implements TopicWeatherProvider {

    private static final Logger log = LoggerFactory.getLogger(TencentTopicWeatherProvider.class);

    private static final String GEOCODE_URL = "https://apis.map.qq.com/ws/geocoder/v1/?address=";
    private static final String WEATHER_URL = "https://apis.map.qq.com/ws/weather/v1/?type=now&location=";
    private static final String WEATHER_CACHE_PREFIX = "rpg:weather:city:";
    private static final String COORDS_CACHE_PREFIX = "rpg:weather:coords:";
    private static final int MAX_CITY_LENGTH = 128;
    private static final int MAX_WEATHER_TEXT = 32;
    private static final int MAX_MEMORY_ENTRIES = 512;
    static final long WEATHER_TTL_MILLIS = 12L * 60L * 1000L;
    static final long FAILURE_TTL_MILLIS = 3L * 60L * 1000L;
    static final long COORDS_TTL_MILLIS = 24L * 60L * 60L * 1000L;
    private static final Set<String> VALID_CODES = new LinkedHashSet<String>(TopicEnvironmentService.WEATHER_VALUES);

    private final TencentMapClient tencentMapClient;
    private final RedisCache redisCache;
    private final String fixedOverride;
    private final Clock clock;

    private final Map<String, CachedString> weatherMemory = new ConcurrentHashMap<String, CachedString>();
    private final Map<String, CachedCoords> coordsMemory = new ConcurrentHashMap<String, CachedCoords>();

    @Autowired
    public TencentTopicWeatherProvider(
            TencentMapClient tencentMapClient,
            @Autowired(required = false) RedisCache redisCache,
            @Value("${chengyin.rpg.weather.fixed:}") String fixedOverride) {
        this(tencentMapClient, redisCache, fixedOverride, Clock.systemUTC());
    }

    TencentTopicWeatherProvider(
            TencentMapClient tencentMapClient,
            RedisCache redisCache,
            String fixedOverride,
            Clock clock) {
        this.tencentMapClient = tencentMapClient;
        this.redisCache = redisCache;
        this.fixedOverride = fixedOverride == null ? "" : fixedOverride.trim();
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public String currentWeather(String topicCity) {
        String city = normalizeCity(topicCity);
        if (city == null) {
            return null;
        }
        if (!fixedOverride.isEmpty()) {
            return normalizeWeatherCode(fixedOverride);
        }
        if (RestoreDispatchGuard.isRestoreMode() || tencentMapClient == null || !tencentMapClient.isReady()) {
            return null;
        }

        long now = clock.millis();
        CachedString memHit = weatherMemory.get(city);
        if (memHit != null && now < memHit.expiresAtMillis) {
            return memHit.value;
        }

        String redisHit = readRedisString(WEATHER_CACHE_PREFIX + city);
        if (redisHit != null) {
            String value = "__FAIL__".equals(redisHit) ? null : normalizeWeatherCode(redisHit);
            putWeatherMemory(city, value, now + (value == null ? FAILURE_TTL_MILLIS : WEATHER_TTL_MILLIS));
            return value;
        }

        double[] coords = resolveCityCoords(city, now);
        if (coords == null) {
            cacheWeather(city, null, FAILURE_TTL_MILLIS, now);
            return null;
        }

        String resolved = fetchWeatherAt(coords[0], coords[1]);
        long ttl = resolved == null ? FAILURE_TTL_MILLIS : WEATHER_TTL_MILLIS;
        cacheWeather(city, resolved, ttl, now);
        return resolved;
    }

    /** 解析主题城市坐标（市中心可信度低于门牌级，因此不套用 suggestCoords 的 >=7 门槛）。 */
    private double[] resolveCityCoords(String city, long now) {
        CachedCoords memHit = coordsMemory.get(city);
        if (memHit != null && now < memHit.expiresAtMillis) {
            return new double[] { memHit.lat, memHit.lng };
        }
        String redisCoords = readRedisString(COORDS_CACHE_PREFIX + city);
        if (redisCoords != null) {
            double[] parsed = parseCoordsString(redisCoords);
            if (parsed != null) {
                putCoordsMemory(city, parsed[0], parsed[1], now + COORDS_TTL_MILLIS);
                return parsed;
            }
        }
        try {
            String raw = requestMap(GEOCODE_URL + urlEncode(city));
            double[] parsed = parseGeocodeLocation(raw);
            if (parsed != null) {
                putCoordsMemory(city, parsed[0], parsed[1], now + COORDS_TTL_MILLIS);
                writeRedisString(COORDS_CACHE_PREFIX + city,
                        String.format(Locale.ROOT, "%.5f,%.5f", parsed[0], parsed[1]),
                        COORDS_TTL_MILLIS);
            }
            return parsed;
        } catch (Exception e) {
            log.warn("[TencentTopicWeather] geocode failed for city={}: {}", city, e.getClass().getSimpleName());
            return null;
        }
    }

    private String fetchWeatherAt(double lat, double lng) {
        try {
            String location = String.format(Locale.ROOT, "%.4f,%.4f", lat, lng);
            String raw = requestMap(WEATHER_URL + location);
            return parseWeatherCode(raw);
        } catch (Exception e) {
            log.warn("[TencentTopicWeather] weather lookup failed: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** 可替换出站点，单测无需真实外网。 */
    protected String requestMap(String url) {
        return tencentMapClient.get(url);
    }

    static double[] parseGeocodeLocation(String response) {
        if (StringUtils.isEmpty(response) || response.length() > TencentMapClient.MAX_RESPONSE_BYTES) {
            return null;
        }
        JSONObject root = JSONObject.parseObject(response);
        if (root == null || root.getIntValue("status", -1) != 0) {
            return null;
        }
        JSONObject result = root.getJSONObject("result");
        JSONObject location = result == null ? null : result.getJSONObject("location");
        if (location == null) {
            return null;
        }
        Double lat = location.getDouble("lat");
        Double lng = location.getDouble("lng");
        if (lat == null || lng == null || !Double.isFinite(lat) || !Double.isFinite(lng)
                || lat < -90D || lat > 90D || lng < -180D || lng > 180D) {
            return null;
        }
        return new double[] { lat, lng };
    }

    static String parseWeatherCode(String response) {
        if (StringUtils.isEmpty(response) || response.length() > TencentMapClient.MAX_RESPONSE_BYTES) {
            return null;
        }
        JSONObject root = JSONObject.parseObject(response);
        if (root == null || root.getIntValue("status", -1) != 0) {
            return null;
        }
        JSONObject result = root.getJSONObject("result");
        JSONArray realtime = result == null ? null : result.getJSONArray("realtime");
        JSONObject first = realtime == null || realtime.isEmpty() ? null : realtime.getJSONObject(0);
        JSONObject infos = first == null ? null : first.getJSONObject("infos");
        String text = infos == null ? null : infos.getString("weather");
        return mapWeatherText(text);
    }

    /** 将腾讯位置服务中文实况文本映射到 {@link TopicEnvironmentService#WEATHER_VALUES}。 */
    public static String mapWeatherText(String rawText) {
        if (StringUtils.isEmpty(rawText)) {
            return null;
        }
        String text = rawText.trim();
        if (text.isEmpty() || text.length() > MAX_WEATHER_TEXT) {
            return null;
        }
        if (VALID_CODES.contains(text)) {
            return text;
        }
        if (text.contains("雷") || text.contains("台风") || text.contains("飓风")
                || text.contains("龙卷") || text.contains("冰雹") || text.contains("暴雨")
                || text.contains("暴风") || text.contains("沙尘暴")) {
            return "storm";
        }
        if (text.contains("雪") || text.contains("霰") || text.contains("霜")) {
            return "snow";
        }
        if (text.contains("雨")) {
            return "rain";
        }
        if (text.contains("雾") || text.contains("霾") || text.contains("浮尘")
                || text.contains("扬沙") || text.contains("沙尘")) {
            return "fog";
        }
        if (text.contains("风")) {
            return "wind";
        }
        if (text.contains("阴") || text.contains("多云") || text.contains("少云")) {
            return "cloudy";
        }
        if (text.contains("晴")) {
            return "clear";
        }
        return null;
    }

    static String normalizeCity(String topicCity) {
        if (StringUtils.isEmpty(topicCity)) {
            return null;
        }
        String clean = topicCity.trim();
        if (clean.isEmpty() || clean.length() > MAX_CITY_LENGTH) {
            return null;
        }
        return clean;
    }

    static String normalizeWeatherCode(String code) {
        if (code == null) {
            return null;
        }
        String clean = code.trim();
        return VALID_CODES.contains(clean) ? clean : null;
    }

    private void cacheWeather(String city, String value, long ttlMillis, long now) {
        putWeatherMemory(city, value, now + ttlMillis);
        writeRedisString(WEATHER_CACHE_PREFIX + city, value == null ? "__FAIL__" : value, ttlMillis);
    }

    private void putWeatherMemory(String city, String value, long expiresAtMillis) {
        synchronized (weatherMemory) {
            if (weatherMemory.size() >= MAX_MEMORY_ENTRIES) {
                weatherMemory.clear();
            }
            weatherMemory.put(city, new CachedString(value, expiresAtMillis));
        }
    }

    private void putCoordsMemory(String city, double lat, double lng, long expiresAtMillis) {
        synchronized (coordsMemory) {
            if (coordsMemory.size() >= MAX_MEMORY_ENTRIES) {
                coordsMemory.clear();
            }
            coordsMemory.put(city, new CachedCoords(lat, lng, expiresAtMillis));
        }
    }

    private String readRedisString(String key) {
        if (redisCache == null) {
            return null;
        }
        try {
            Object cached = redisCache.getCacheObject(key);
            return cached instanceof String ? (String) cached : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void writeRedisString(String key, String value, long ttlMillis) {
        if (redisCache == null) {
            return;
        }
        try {
            int seconds = (int) Math.max(1L, ttlMillis / 1000L);
            redisCache.setCacheObject(key, value, seconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[TencentTopicWeather] redis cache write skipped: {}", e.getClass().getSimpleName());
        }
    }

    private static double[] parseCoordsString(String raw) {
        int comma = raw.indexOf(',');
        if (comma <= 0 || comma >= raw.length() - 1) {
            return null;
        }
        try {
            double lat = Double.parseDouble(raw.substring(0, comma));
            double lng = Double.parseDouble(raw.substring(comma + 1));
            if (!Double.isFinite(lat) || !Double.isFinite(lng)
                    || lat < -90D || lat > 90D || lng < -180D || lng > 180D) {
                return null;
            }
            return new double[] { lat, lng };
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String urlEncode(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    private static final class CachedString {
        final String value;
        final long expiresAtMillis;

        CachedString(String value, long expiresAtMillis) {
            this.value = value;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    private static final class CachedCoords {
        final double lat;
        final double lng;
        final long expiresAtMillis;

        CachedCoords(double lat, double lng, long expiresAtMillis) {
            this.lat = lat;
            this.lng = lng;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
