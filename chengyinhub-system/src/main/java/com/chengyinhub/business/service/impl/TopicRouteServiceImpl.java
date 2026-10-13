package com.chengyinhub.business.service.impl;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

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

    /** 出行方式：walking(步行,默认) / driving / bicycling / transit */
    @Value("${tencent.map.mode:walking}")
    private String mode;

    @Autowired
    private TencentMapClient tencentMapClient;

    /** 进程内缓存：key=出行方式+坐标指纹 value=路线坐标JSON */
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    @Override
    public String buildGeometryJson(List<? extends CmsTopicNode> orderedNodes)
    {
        if (!tencentMapClient.isReady())
        {
            return null;
        }

        // 1) 取出有合法经纬度的点
        List<double[]> pts = new ArrayList<>();
        if (orderedNodes != null)
        {
            for (CmsTopicNode n : orderedNodes)
            {
                Double lat = parse(n.getLatitude());
                Double lng = parse(n.getLongitude());
                if (lat != null && lng != null)
                {
                    pts.add(new double[] { lat, lng });
                }
            }
        }
        if (pts.size() < 2)
        {
            return null;
        }

        // 2) 缓存命中直接返回
        String cacheKey = buildCacheKey(pts);
        String cached = cache.get(cacheKey);
        if (cached != null)
        {
            return cached;
        }

        // 3) 相邻两点逐段算路并拼接
        List<double[]> line = new ArrayList<>();
        boolean degraded = false;
        for (int i = 0; i + 1 < pts.size(); i++)
        {
            List<double[]> seg = null;
            try
            {
                seg = requestSegment(pts.get(i), pts.get(i + 1));
            }
            catch (HttpRequestException error)
            {
                log.warn("[TopicRoute] provider network failure reason={}",
                        error.getClass().getSimpleName());
            }
            catch (RuntimeException error)
            {
                log.warn("[TopicRoute] provider segment response rejected reason={}",
                        error.getClass().getSimpleName());
            }
            if (seg == null || seg.isEmpty())
            {
                // 某段算路失败：退化为这两点直连，保证线不断
                degraded = true;
                appendDedup(line, pts.get(i));
                appendDedup(line, pts.get(i + 1));
                continue;
            }
            for (double[] p : seg)
            {
                appendDedup(line, p);
            }
        }

        if (line.size() < 2)
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
        if (!degraded)
        {
            cache.put(cacheKey, json);
        }
        return json;
    }

    /** 调一段腾讯路线规划，返回解压后的坐标 [lat,lng] 列表 */
    private List<double[]> requestSegment(double[] from, double[] to)
    {
        String url = DIRECTION_BASE + safeMode() + "/?from="
                + fmt(from[0]) + "," + fmt(from[1])
                + "&to=" + fmt(to[0]) + "," + fmt(to[1]);

        String result = tencentMapClient.get(url);
        if (StringUtils.isEmpty(result))
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
        double[] c = new double[n];
        for (int i = 0; i < n; i++)
        {
            c[i] = poly.getDoubleValue(i);
        }
        for (int i = 2; i < n; i++)
        {
            c[i] = c[i - 2] + c[i] / COMPRESS;
        }
        List<double[]> out = new ArrayList<>(n / 2);
        for (int i = 0; i + 1 < n; i += 2)
        {
            out.add(new double[] { c[i], c[i + 1] });
        }
        return out;
    }

    /** 追加点，去掉与上一个点重复的（段与段衔接处） */
    private void appendDedup(List<double[]> line, double[] p)
    {
        if (!line.isEmpty())
        {
            double[] last = line.get(line.size() - 1);
            if (Math.abs(last[0] - p[0]) < 1e-7 && Math.abs(last[1] - p[1]) < 1e-7)
            {
                return;
            }
        }
        line.add(p);
    }

    private String buildCacheKey(List<double[]> pts)
    {
        StringBuilder sb = new StringBuilder(safeMode()).append('|');
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
        return mode;
    }

    /** 用 US locale 保证小数点是 ".", 不是 "," */
    private String fmt(double v)
    {
        return String.format(Locale.US, "%.6f", v);
    }

    private Double parse(String s)
    {
        if (StringUtils.isEmpty(s))
        {
            return null;
        }
        try
        {
            return Double.parseDouble(s.trim());
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }
}
