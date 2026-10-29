package com.chengyinhub.business.service.impl;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.service.ITopicRouteService;
import com.chengyinhub.business.service.TencentMapClient;
import com.chengyinhub.business.service.support.TopicSizeLimits;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.http.HttpRequestException;

/**
 * 主题路线服务实现：调用腾讯位置服务「路线规划」，把相邻节点逐段算路，
 * 解压并拼接成一条沿路坐标，结果按节点坐标做内存缓存（路线相对固定）。
 *
 * 说明：
 * - 必须配置腾讯地图客户端才会真正算路；
 *   否则返回 null，前端用「节点直连」兜底显示。
 * - Key 只放后端，不下发到小程序前端（避免被盗刷配额）。
 * - 缓存是进程内的：重启后首个访问者会触发一次重算；如需持久化，
 *   可后续把缓存换成 cms_topic_route 表，对外契约（routeGeometry）不变。
 *
 * @author chengyinhub
 */
@Service
public class TopicRouteServiceImpl implements ITopicRouteService
{
    private static final Logger log = LoggerFactory.getLogger(TopicRouteServiceImpl.class);

    /** 腾讯路线规划基础地址，后面拼出行方式：walking/driving/bicycling/transit */
    private static final String DIRECTION_BASE = "https://apis.map.qq.com/ws/direction/v1/";

    /** 腾讯返回的 polyline 压缩系数 */
    private static final double COMPRESS = 1000000.0D;

    static final long MAX_CACHE_BYTES = MapResponseCache.MAX_CACHE_BYTES;
    static final long MAX_ENTRY_BYTES = MapResponseCache.MAX_ENTRY_BYTES;
    static final long MAX_TTL_SECONDS = MapResponseCache.MAX_TTL_SECONDS;
    static final int MAX_GEOMETRY_POINTS = 4096;
    private static final long BUILD_NANOS = TimeUnit.SECONDS.toNanos(10);

    /** 出行方式：walking(步行,默认) / driving / bicycling / transit */
    @Value("${tencent.map.mode:walking}")
    private String mode;

    @Autowired
    private TencentMapClient tencentMapClient;

    /** 只保留成功路线；容量、LRU 和 TTL 的元数据操作共用短锁，provider 调用不持锁。 */
    private final MapResponseCache cache;
    /** No waiting callers or unbounded key-lock map. A cold duplicate declines until the winner completes. */
    private final Set<String> inFlight = new HashSet<>();

    public TopicRouteServiceImpl()
    {
        this(true, 8L * 1024 * 1024, 256L * 1024, 300);
    }

    @Autowired
    public TopicRouteServiceImpl(
            @Value("${tencent.map.route-cache.enabled:true}") boolean cacheEnabled,
            @Value("${tencent.map.route-cache.max-bytes:8388608}") long maxBytes,
            @Value("${tencent.map.route-cache.max-entry-bytes:262144}") long maxEntryBytes,
            @Value("${tencent.map.route-cache.ttl-seconds:300}") long ttlSeconds)
    {
        if (maxBytes < 0 || maxBytes > MAX_CACHE_BYTES || maxEntryBytes > MAX_ENTRY_BYTES
                || ttlSeconds <= 0 || ttlSeconds > MAX_TTL_SECONDS)
        {
            throw new IllegalArgumentException("Invalid route cache budget or TTL");
        }
        cache = new MapResponseCache(cacheEnabled ? maxBytes : 0, maxEntryBytes,
                TimeUnit.SECONDS.toNanos(ttlSeconds), System::nanoTime);
    }

    @Override
    public String buildGeometryJson(List<? extends CmsTopicNode> orderedNodes)
    {
        if (!tencentMapClient.isReady() || orderedNodes == null || orderedNodes.size() > TopicSizeLimits.MAX_NODES)
        {
            return null;
        }

        // Every requested node must be valid before cache lookup; skipping a node changes the route identity.
        List<double[]> pts = new ArrayList<>();
        if (orderedNodes != null)
        {
            for (CmsTopicNode n : orderedNodes)
            {
                if (n == null) return null;
                Double lat = parse(n.getLatitude());
                Double lng = parse(n.getLongitude());
                if (lat == null || lng == null || !Double.isFinite(lat) || !Double.isFinite(lng)
                        || lat < -90D || lat > 90D || lng < -180D || lng > 180D)
                {
                    return null;
                }
                pts.add(new double[] { lat, lng });
            }
        }
        if (pts.size() < 2)
        {
            return null;
        }

        // 2) 缓存命中直接返回
        String routeMode = safeMode();
        if (routeMode == null) return null;
        String cacheKey = buildCacheKey(pts, routeMode);
        String cached = cache.get(cacheKey);
        if (cached != null)
        {
            return cached;
        }

        synchronized (inFlight)
        {
            if (inFlight.size() >= TencentMapClient.MAX_IN_FLIGHT || !inFlight.add(cacheKey)) return null;
        }
        try
        {
            cached = cache.get(cacheKey);
            if (cached != null) return cached;
            return computeGeometry(pts, cacheKey, routeMode);
        }
        finally
        {
            synchronized (inFlight) { inFlight.remove(cacheKey); }
        }
    }

    private String computeGeometry(List<double[]> pts, String cacheKey, String routeMode)
    {
        long started = nanoTime();
        // 3) 相邻两点逐段算路并拼接
        List<double[]> line = new ArrayList<>();
        for (int i = 0; i + 1 < pts.size(); i++)
        {
            if (nanoTime() - started >= BUILD_NANOS) return null;
            List<double[]> seg = null;
            try
            {
                seg = requestSegment(pts.get(i), pts.get(i + 1), routeMode);
            }
            catch (HttpRequestException error)
            {
                log.warn("[TopicRoute] provider network failure reason={}",
                        error.getClass().getSimpleName());
            }
            catch (com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) { throw paused; }
            catch (RuntimeException error)
            {
                log.warn("[TopicRoute] provider segment response rejected reason={}",
                        error.getClass().getSimpleName());
            }
            if (seg == null || seg.isEmpty())
            {
                // A failed/unknown segment is not a provider route. The view may draw labelled local preview lines.
                return null;
            }
            for (double[] p : seg)
            {
                if (!appendDedup(line, p)) return null;
            }
        }

        if (line.size() < 2 || nanoTime() - started >= BUILD_NANOS)
        {
            return null;
        }

        JSONArray arr = new JSONArray(line.size());
        for (double[] p : line)
        {
            JSONObject o = new JSONObject(2);
            o.put("latitude", p[0]);
            o.put("longitude", p[1]);
            arr.add(o);
        }
        String json = arr.toJSONString();
        cache.put(cacheKey, json);
        return json;
    }

    /** 调一段腾讯路线规划，返回解压后的坐标 [lat,lng] 列表 */
    private List<double[]> requestSegment(double[] from, double[] to, String routeMode)
    {
        String url = DIRECTION_BASE + routeMode + "/?from="
                + fmt(from[0]) + "," + fmt(from[1])
                + "&to=" + fmt(to[0]) + "," + fmt(to[1]);

        String result = tencentMapClient.get(url);
        if (StringUtils.isEmpty(result) || result.length() > TencentMapClient.MAX_RESPONSE_BYTES)
        {
            return null;
        }

        JSONObject root = JSONObject.parseObject(result);
        int status = root == null ? -1 : root.getIntValue("status", -1);
        if (status != 0)
        {
            log.warn("[TopicRoute] provider rejected route status={} responseBytes={}",
                    status, result.getBytes(StandardCharsets.UTF_8).length);
            return null;
        }
        JSONObject res = root.getJSONObject("result");
        if (res == null)
        {
            return null;
        }
        JSONArray routes = res.getJSONArray("routes");
        if (routes == null || routes.isEmpty())
        {
            return null;
        }
        JSONArray poly = routes.getJSONObject(0).getJSONArray("polyline");
        if (poly == null || poly.size() < 4)
        {
            return null;
        }
        return decompress(poly);
    }

    /**
     * 解压腾讯压缩坐标：从第 3 个数起，coor[i] = coor[i-2] + coor[i]/1000000。
     * 解压后按 (lat, lng) 两两成对。
     */
    private List<double[]> decompress(JSONArray poly)
    {
        int n = poly.size();
        if ((n & 1) != 0 || n > 2 * MAX_GEOMETRY_POINTS) throw new IllegalArgumentException("Route point budget exceeded");
        double[] c = new double[n];
        for (int i = 0; i < n; i++)
        {
            if (!(poly.get(i) instanceof Number)) throw new IllegalArgumentException("Invalid route coordinate");
            c[i] = poly.getDoubleValue(i);
            if (!Double.isFinite(c[i])) throw new IllegalArgumentException("Invalid route coordinate");
        }
        for (int i = 2; i < n; i++)
        {
            c[i] = c[i - 2] + c[i] / COMPRESS;
        }
        List<double[]> out = new ArrayList<>(n / 2);
        for (int i = 0; i + 1 < n; i += 2)
        {
            if (!Double.isFinite(c[i]) || !Double.isFinite(c[i + 1])
                    || c[i] < -90D || c[i] > 90D || c[i + 1] < -180D || c[i + 1] > 180D)
                throw new IllegalArgumentException("Invalid route coordinate");
            out.add(new double[] { c[i], c[i + 1] });
        }
        return out;
    }

    /** 追加点，去掉与上一个点重复的（段与段衔接处） */
    private boolean appendDedup(List<double[]> line, double[] p)
    {
        if (!line.isEmpty())
        {
            double[] last = line.get(line.size() - 1);
            if (Math.abs(last[0] - p[0]) < 1e-7 && Math.abs(last[1] - p[1]) < 1e-7)
            {
                return true;
            }
        }
        if (line.size() >= MAX_GEOMETRY_POINTS) return false;
        line.add(p);
        return true;
    }

    private String buildCacheKey(List<double[]> pts, String routeMode)
    {
        StringBuilder sb = new StringBuilder(routeMode).append('|');
        for (double[] p : pts)
        {
            sb.append(fmt(p[0])).append(',').append(fmt(p[1])).append(';');
        }
        return sb.toString();
    }

    private String safeMode()
    {
        if (StringUtils.isEmpty(mode))
        {
            return "walking";
        }
        return "walking".equals(mode) || "driving".equals(mode) || "bicycling".equals(mode) || "transit".equals(mode)
                ? mode : null;
    }

    /** 用 US locale 保证小数点是 ".", 不是 "," */
    private String fmt(double v)
    {
        return String.format(Locale.US, "%.6f", v);
    }

    private Double parse(String s)
    {
        if (StringUtils.isEmpty(s) || s.length() > 64)
        {
            return null;
        }
        try
        {
            double value = Double.parseDouble(s.trim());
            return Double.isFinite(value) ? value : null;
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    protected long nanoTime()
    {
        return System.nanoTime();
    }
}
