package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.vo.MapWeatherVO;
import com.chengyinhub.business.service.IMapWeatherService;
import com.chengyinhub.business.service.TencentMapClient;
import com.chengyinhub.common.config.RestoreDispatchGuard;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 腾讯位置服务「天气查询」实况(/ws/weather/v1/?type=now)→ 地图氛围 rain/snow/fog/clear。
 * 只读供应商 result.realtime[0].infos.weather 这一段文字;按约 0.05° 网格缓存,失败一律 clear。
 */
@Service
public class MapWeatherServiceImpl implements IMapWeatherService {

    private static final Logger log = LoggerFactory.getLogger(MapWeatherServiceImpl.class);
    private static final String WEATHER_URL = "https://apis.map.qq.com/ws/weather/v1/?type=now&location=";
    static final String CACHE_PREFIX = "map:weather:";
    /** 每度 20 格 ≈ 5.5 km:同一片区的玩家共享一次供应商调用,也不把精确位置发给供应商。 */
    static final int CELLS_PER_DEGREE = 20;
    static final int TTL_MINUTES = 12;
    /** 供应商出错时短暂记为晴,避免同一片区每次刷新都再打一次供应商。 */
    static final int FAILURE_TTL_MINUTES = 3;
    private static final int MAX_WEATHER_TEXT = 32;

    @Autowired
    private TencentMapClient tencentMapClient;

    @Autowired
    private RedisCache redisCache;

    @Override
    public MapWeatherVO weatherAt(double latitude, double longitude) {
        if (!tencentMapClient.isReady() || RestoreDispatchGuard.isRestoreMode()) {
            return MapWeatherVO.clear();
        }
        long latCell = (long) Math.floor(latitude * CELLS_PER_DEGREE);
        long lngCell = (long) Math.floor(longitude * CELLS_PER_DEGREE);
        String cacheKey = CACHE_PREFIX + latCell + ":" + lngCell;
        MapWeatherVO cached = readCache(cacheKey);
        if (cached != null) {
            return cached;
        }
        MapWeatherVO weather;
        int ttl = TTL_MINUTES;
        try {
            String location = String.format(Locale.ROOT, "%.3f,%.3f",
                    (latCell + 0.5D) / CELLS_PER_DEGREE, (lngCell + 0.5D) / CELLS_PER_DEGREE);
            String response = requestWeather(WEATHER_URL + location);
            if (response == null) {
                // 客户端出站名额已满或未就绪:本次晴,不记缓存,下次照常再试。
                return MapWeatherVO.clear();
            }
            weather = parse(response);
            if (weather == null) {
                weather = MapWeatherVO.clear();
                ttl = FAILURE_TTL_MINUTES;
            }
        } catch (Exception e) {
            log.warn("[MapWeather] weather request failed: {}", e.getClass().getSimpleName());
            weather = MapWeatherVO.clear();
            ttl = FAILURE_TTL_MINUTES;
        }
        writeCache(cacheKey, weather, ttl);
        return weather;
    }

    /** null 表示供应商没给出可用实况(非 0 状态、缺字段、超限)。 */
    static MapWeatherVO parse(String response) {
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
        if (StringUtils.isEmpty(text) || text.length() > MAX_WEATHER_TEXT) {
            return null;
        }
        return fromWeatherText(text);
    }

    /** 中文天气现象 → 画面类别与强度。供应商无数字编码,只给「晴天/小雨/中雪/大雾」等文字。 */
    static MapWeatherVO fromWeatherText(String text) {
        if (text.contains("雪")) {
            return new MapWeatherVO(MapWeatherVO.SNOW, text.contains("雨") ? 0.4D : precipitation(text));
        }
        if (text.contains("雨") || text.contains("冰雹")) {
            return new MapWeatherVO(MapWeatherVO.RAIN, text.contains("冰雹") ? 0.8D : precipitation(text));
        }
        if (text.contains("雾")) {
            if (text.contains("强浓") || text.contains("特强")) return new MapWeatherVO(MapWeatherVO.FOG, 1D);
            if (text.contains("浓") || text.contains("大雾")) return new MapWeatherVO(MapWeatherVO.FOG, 0.85D);
            if (text.contains("轻") || text.contains("薄")) return new MapWeatherVO(MapWeatherVO.FOG, 0.35D);
            return new MapWeatherVO(MapWeatherVO.FOG, 0.6D);
        }
        if (text.contains("霾")) {
            if (text.contains("重") || text.contains("严重")) return new MapWeatherVO(MapWeatherVO.FOG, 0.75D);
            if (text.contains("中")) return new MapWeatherVO(MapWeatherVO.FOG, 0.55D);
            return new MapWeatherVO(MapWeatherVO.FOG, 0.4D);
        }
        if (text.contains("沙") || text.contains("尘")) {
            return new MapWeatherVO(MapWeatherVO.FOG, 0.5D);
        }
        return MapWeatherVO.clear();
    }

    private static double precipitation(String text) {
        if (text.contains("暴")) return text.contains("大暴") || text.contains("特大") ? 1D : 0.95D;
        if (text.contains("大")) return 0.8D;
        if (text.contains("雷")) return 0.7D;
        if (text.contains("中")) return 0.6D;
        if (text.contains("小") || text.contains("阵") || text.contains("毛毛") || text.contains("零星")) return 0.35D;
        return 0.5D;
    }

    private MapWeatherVO readCache(String key) {
        try {
            Object value = redisCache.getCacheObject(key);
            if (!(value instanceof String)) return null;
            String raw = (String) value;
            int split = raw.indexOf('|');
            if (split <= 0) return null;
            return new MapWeatherVO(raw.substring(0, split), Double.parseDouble(raw.substring(split + 1)));
        } catch (Exception e) {
            return null;
        }
    }

    private void writeCache(String key, MapWeatherVO weather, int ttlMinutes) {
        try {
            redisCache.setCacheObject(key, weather.getKind() + "|" + weather.getIntensity(), ttlMinutes, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("[MapWeather] cache write failed: {}", e.getClass().getSimpleName());
        }
    }

    /** 唯一出站点,测试替换为桩;真实调用经 TencentMapClient(Key 只在服务端拼接)。 */
    protected String requestWeather(String url) {
        return tencentMapClient.get(url);
    }
}
