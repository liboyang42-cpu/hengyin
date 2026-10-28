package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.service.IMapGeocodeService;
import com.chengyinhub.business.service.TencentMapClient;
import com.chengyinhub.common.utils.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 腾讯位置服务逆地理编码实现。密钥仅从服务端环境配置读取。
 */
@Service
public class MapGeocodeServiceImpl implements IMapGeocodeService {

    private static final Logger log = LoggerFactory.getLogger(MapGeocodeServiceImpl.class);
    private static final String GEOCODE_URL = "https://apis.map.qq.com/ws/geocoder/v1/?location=";
    private static final String FORWARD_GEOCODE_URL = "https://apis.map.qq.com/ws/geocoder/v1/?address=";
    /** 供应商可信度门槛:>=7 才是门牌/道路级定位,以下是区县或城市中心的回退值。 */
    private static final int MIN_RELIABILITY = 7;
    static final long CITY_CACHE_BYTES = 512L * 1024;
    static final long CITY_ENTRY_BYTES = 1024;
    private static final int MAX_ADDRESS_CHARACTERS = 512;
    private static final int MAX_REGION_CHARACTERS = 128;

    private final MapResponseCache cityCache = new MapResponseCache(CITY_CACHE_BYTES, CITY_ENTRY_BYTES,
            TimeUnit.MINUTES.toNanos(5), this::nanoTime);
    /** Shared reverse/forward work budget, not a thread pool. Cold duplicates never wait. */
    private final Set<String> inFlight = new HashSet<>();

    @Autowired
    private TencentMapClient tencentMapClient;

    @Override
    public String resolveCity(double longitude, double latitude) {
        if (!tencentMapClient.isReady()) {
            return null;
        }

        String cacheKey = coordinateGrid(longitude, latitude);
        String cached = cityCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String requestKey = "C|" + cacheKey;
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        if (!claim(requestKey)) return null;
        try {
            cached = cityCache.get(cacheKey);
            if (cached != null) return cached;
            String city = requestCity(longitude, latitude);
            if (StringUtils.isEmpty(city) || !cityCache.accepts(cacheKey, city)) return null;
            cityCache.put(cacheKey, city);
            return city;
        } catch (Exception e) {
            log.warn("[MapGeocode] reverse geocode request failed: {}", e.getClass().getSimpleName());
            return null;
        } finally {
            synchronized (inFlight) { inFlight.remove(requestKey); }
        }
    }

    @Override
    public double[] suggestCoords(String address, String region) {
        if (!tencentMapClient.isReady() || address == null || address.length() > MAX_ADDRESS_CHARACTERS
                || (region != null && region.length() > MAX_REGION_CHARACTERS) || StringUtils.isEmpty(address)) {
            return null;
        }
        String cleanAddress = address.trim(), cleanRegion = region == null ? "" : region.trim();
        String requestKey = "F|" + cleanAddress.length() + ":" + cleanAddress + cleanRegion.length() + ":" + cleanRegion;
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        if (!claim(requestKey)) return null;
        try {
            StringBuilder url = new StringBuilder(FORWARD_GEOCODE_URL).append(urlEncode(address.trim()));
            if (!StringUtils.isEmpty(region)) {
                url.append("&region=").append(urlEncode(region.trim()));
            }
            JSONObject root = parseProviderResponse(requestGeocode(url.toString()));
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
        } finally {
            synchronized (inFlight) { inFlight.remove(requestKey); }
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
        JSONObject root = parseProviderResponse(requestGeocode(url));
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

    private boolean claim(String key) {
        synchronized (inFlight) {
            return inFlight.size() < TencentMapClient.MAX_IN_FLIGHT && inFlight.add(key);
        }
    }

    private JSONObject parseProviderResponse(String response) {
        return response == null || response.length() > TencentMapClient.MAX_RESPONSE_BYTES
                ? null : JSONObject.parseObject(response);
    }

    private String coordinateGrid(double longitude, double latitude) {
        return Math.round(longitude * 1000D) + ":" + Math.round(latitude * 1000D);
    }

    /** 保持 HTTP 调用可替换，以便离线验证缓存和错误处理。 */
    protected String requestGeocode(String url) {
        return tencentMapClient.get(url);
    }

    protected long nanoTime() {
        return System.nanoTime();
    }
}
