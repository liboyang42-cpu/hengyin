package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.service.IMapGeocodeService;
import com.chengyinhub.business.service.TencentMapClient;
import com.chengyinhub.common.utils.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 腾讯位置服务逆地理编码实现。密钥仅从服务端环境配置读取。
 */
@Service
public class MapGeocodeServiceImpl implements IMapGeocodeService {

    private static final Logger log = LoggerFactory.getLogger(MapGeocodeServiceImpl.class);
    private static final String GEOCODE_URL = "https://apis.map.qq.com/ws/geocoder/v1/?location=";
    private static final String FORWARD_GEOCODE_URL = "https://apis.map.qq.com/ws/geocoder/v1/?address=";
    private static final String PLACE_SUGGESTION_URL = "https://apis.map.qq.com/ws/place/v1/suggestion/?page_size=10&keyword=";
    /** 搜索结果上限:前端面板最多展示 5 条,多拿一点给去重留余量 */
    private static final int MAX_PLACES = 10;
    /** 供应商可信度门槛:>=7 才是门牌/道路级定位,以下是区县或城市中心的回退值。 */
    private static final int MIN_RELIABILITY = 7;
    private static final long CACHE_TTL_MILLIS = 5 * 60 * 1000L;
    private static final int MAX_CACHE_SIZE = 2000;

    private final Map<String, CityCacheEntry> cityCache = new ConcurrentHashMap<>();
    private final Map<String, Object> requestLocks = new ConcurrentHashMap<>();

    @Autowired
    private TencentMapClient tencentMapClient;

    @Override
    public String resolveCity(double longitude, double latitude) {
        if (!tencentMapClient.isReady()) {
            return null;
        }

        String cacheKey = coordinateGrid(longitude, latitude);
        String cached = readCachedCity(cacheKey);
        if (cached != null) {
            return cached;
        }

        Object newLock = new Object();
        Object lock = requestLocks.putIfAbsent(cacheKey, newLock);
        if (lock == null) {
            lock = newLock;
        }
        try {
            synchronized (lock) {
                cached = readCachedCity(cacheKey);
                if (cached != null) {
                    return cached;
                }
                String city = requestCity(longitude, latitude);
                if (!StringUtils.isEmpty(city)) {
                    cacheCity(cacheKey, city);
                }
                return city;
            }
        } catch (Exception e) {
            log.warn("[MapGeocode] reverse geocode request failed: {}", e.getClass().getSimpleName());
            return null;
        } finally {
            requestLocks.remove(cacheKey, lock);
        }
    }

    @Override
    public double[] suggestCoords(String address, String region) {
        if (!tencentMapClient.isReady() || StringUtils.isEmpty(address)) {
            return null;
        }
        try {
            StringBuilder url = new StringBuilder(FORWARD_GEOCODE_URL).append(urlEncode(address.trim()));
            if (!StringUtils.isEmpty(region)) {
                url.append("&region=").append(urlEncode(region.trim()));
            }
            JSONObject root = JSONObject.parseObject(requestGeocode(url.toString()));
            int status = root == null ? -1 : root.getIntValue("status", -1);
            if (status != 0) {
                log.warn("[MapGeocode] provider rejected forward geocode, status={}", status);
                return null;
            }
            JSONObject result = root.getJSONObject("result");
            JSONObject location = result == null ? null : result.getJSONObject("location");
            if (location == null) {
                return null;
            }
            // 可信度 1-10。低于门槛说明供应商没匹配到门牌/道路，而是回退到了区或市中心 ——
            // 这种坐标看起来完全正常,采信就是静默把据点插到错误位置,只能当失败。
            int reliability = result.getIntValue("reliability", 0);
            if (reliability < MIN_RELIABILITY) {
                log.info("[MapGeocode] forward geocode too coarse, reliability={}", reliability);
                return null;
            }
            Double lng = location.getDouble("lng");
            Double lat = location.getDouble("lat");
            if (lng == null || lat == null) {
                return null;
            }
            return new double[]{lng, lat};
        } catch (Exception e) {
            log.warn("[MapGeocode] forward geocode request failed: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    public List<Map<String, Object>> searchPlaces(String keyword, double longitude, double latitude) {
        if (!tencentMapClient.isReady() || StringUtils.isEmpty(keyword) || StringUtils.isEmpty(keyword.trim())) {
            return Collections.emptyList();
        }
        try {
            String url = PLACE_SUGGESTION_URL + urlEncode(keyword.trim()) + "&location=" + latitude + "," + longitude;
            String city = resolveCity(longitude, latitude);
            if (!StringUtils.isEmpty(city)) {
                url += "&region=" + urlEncode(city);
            }
            JSONObject root = JSONObject.parseObject(requestGeocode(url));
            int status = root == null ? -1 : root.getIntValue("status", -1);
            if (status != 0) {
                log.warn("[MapGeocode] provider rejected place suggestion, status={}", status);
                return Collections.emptyList();
            }
            com.alibaba.fastjson2.JSONArray data = root.getJSONArray("data");
            if (data == null) {
                return Collections.emptyList();
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = 0; i < data.size() && out.size() < MAX_PLACES; i++) {
                JSONObject row = data.getJSONObject(i);
                JSONObject location = row == null ? null : row.getJSONObject("location");
                Double lat = location == null ? null : location.getDouble("lat");
                Double lng = location == null ? null : location.getDouble("lng");
                String title = row == null ? null : row.getString("title");
                if (lat == null || lng == null || StringUtils.isEmpty(title)) {
                    continue;
                }
                Map<String, Object> place = new LinkedHashMap<>();
                place.put("name", title);
                place.put("address", StringUtils.nvl(row.getString("address"), ""));
                place.put("lat", lat);
                place.put("lng", lng);
                Double distance = row.getDouble("_distance");
                if (distance != null) {
                    place.put("distance", Math.round(distance));
                }
                out.add(place);
            }
            return out;
        } catch (Exception e) {
            log.warn("[MapGeocode] place suggestion request failed: {}", e.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    private static String urlEncode(String raw) {
        try {
            return java.net.URLEncoder.encode(raw, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String requestCity(double longitude, double latitude) {
        String url = GEOCODE_URL + latitude + "," + longitude;
        JSONObject root = JSONObject.parseObject(requestGeocode(url));
        int status = root == null ? -1 : root.getIntValue("status", -1);
        if (status != 0) {
            log.warn("[MapGeocode] provider rejected reverse geocode, status={}", status);
            return null;
        }
        JSONObject result = root.getJSONObject("result");
        JSONObject component = result == null ? null : result.getJSONObject("address_component");
        if (component == null) {
            return null;
        }
        String city = component.getString("city");
        return StringUtils.isEmpty(city) ? component.getString("province") : city;
    }

    private String readCachedCity(String cacheKey) {
        CityCacheEntry entry = cityCache.get(cacheKey);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt < System.currentTimeMillis()) {
            cityCache.remove(cacheKey, entry);
            return null;
        }
        return entry.city;
    }

    private void cacheCity(String cacheKey, String city) {
        if (cityCache.size() >= MAX_CACHE_SIZE) {
            removeExpiredEntries();
        }
        if (cityCache.size() < MAX_CACHE_SIZE) {
            cityCache.put(cacheKey, new CityCacheEntry(city, System.currentTimeMillis() + CACHE_TTL_MILLIS));
        }
    }

    private void removeExpiredEntries() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, CityCacheEntry> entry : cityCache.entrySet()) {
            if (entry.getValue().expiresAt < now) {
                cityCache.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private String coordinateGrid(double longitude, double latitude) {
        return Math.round(longitude * 1000D) + ":" + Math.round(latitude * 1000D);
    }

    /** 保持 HTTP 调用可替换，以便离线验证缓存和错误处理。 */
    protected String requestGeocode(String url) {
        return tencentMapClient.get(url);
    }

    private static final class CityCacheEntry {
        private final String city;
        private final long expiresAt;

        private CityCacheEntry(String city, long expiresAt) {
            this.city = city;
            this.expiresAt = expiresAt;
        }
    }
}
