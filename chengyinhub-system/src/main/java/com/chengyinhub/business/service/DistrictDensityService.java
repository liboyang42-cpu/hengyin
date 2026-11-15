package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.seed.DensityQuery;
import com.chengyinhub.business.domain.dto.seed.SeedGeoPoint;
import com.chengyinhub.business.domain.dto.seed.SeedPlace;
import com.chengyinhub.business.mapper.SeedContentMapper;
import com.chengyinhub.common.exception.ServiceException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * POS-09: how full is a launch district? Counts places, playable nodes, routes, live shops and CITY board places
 * inside a box or drawn polygon (or a named CITY board area), plus city-wide daily missions and opening events,
 * against the launch target. A source that cannot be read is reported as unavailable, never as zero.
 */
@Service
public class DistrictDensityService {
    private static final Logger log = LoggerFactory.getLogger(DistrictDensityService.class);
    /** Largest box side, in degrees (~55 km): a district report, not a whole-province scan. */
    static final double MAX_SPAN_DEGREES = 0.5;

    private final SeedContentMapper mapper;

    public DistrictDensityService(SeedContentMapper mapper) { this.mapper = mapper; }

    public List<Map<String, Object>> areas() {
        try { return mapper.selectBoardAreas(); }
        catch (RuntimeException e) { log.warn("[Density] CITY board areas unavailable", e); return List.of(); }
    }

    public Map<String, Object> report(DensityQuery query) {
        Area area = Area.resolve(query, this::boardBox);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("area", area.describe());
        List<Map<String, Object>> metrics = new ArrayList<>();

        List<SeedPlace> pois = read("places", () -> mapper.selectPlacesInBox(area.minLat, area.maxLat, area.minLng, area.maxLng));
        List<SeedGeoPoint> nodes = read("routes", () -> mapper.selectRouteNodesInBox(area.minLat, area.maxLat, area.minLng, area.maxLng));
        List<SeedGeoPoint> shops = read("shops", () -> mapper.selectLiveShopsInBox(area.minLat, area.maxLat, area.minLng, area.maxLng));
        List<SeedGeoPoint> board = read("board", () -> mapper.selectBoardPointsInBox(area.minLat, area.maxLat, area.minLng, area.maxLng));

        if (pois == null) {
            metrics.add(unavailable("places", "官方地点（已上架）", 200, null));
            metrics.add(unavailable("playable", "可玩节点", 260, null));
        } else {
            int live = 0, total = 0, drafts = 0, pending = 0, livePlayable = 0;
            for (SeedPlace p : pois) {
                if (!area.contains(p.getLat(), p.getLng())) continue;
                boolean isLive = Integer.valueOf(1).equals(p.getStatus());
                if (isLive && p.getTemplateId() != null) livePlayable++;
                if (!Integer.valueOf(1).equals(p.getType()) || p.getMerchantId() != null) continue;
                total++;
                if (isLive) live++;
                if (Integer.valueOf(3).equals(p.getAuditStatus())) drafts++;
                if (Integer.valueOf(0).equals(p.getAuditStatus())) pending++;
            }
            metrics.add(metric("places", "官方地点（已上架）", live, 200, null, "AREA",
                    "共 " + total + " 个官方地点：导入草稿 " + drafts + "、待审核 " + pending));
            if (nodes == null) {
                metrics.add(unavailable("playable", "可玩节点", 260, null));
            } else {
                int routeNodes = 0;
                for (SeedGeoPoint n : nodes) if (area.contains(n.getLat(), n.getLng()) && one(n.getFlag1()) && one(n.getFlag2())) routeNodes++;
                metrics.add(metric("playable", "可玩节点", livePlayable + routeNodes, 260, null, "AREA",
                        "已上架带游戏的地点 " + livePlayable + " + 已上架路线里带游戏的站点 " + routeNodes));
            }
        }
        if (nodes == null) {
            metrics.add(unavailable("routes", "路线（已上架）", 5, null));
        } else {
            Set<String> live = new HashSet<>(), all = new HashSet<>();
            for (SeedGeoPoint n : nodes) {
                if (!area.contains(n.getLat(), n.getLng())) continue;
                all.add(n.getKey());
                if (one(n.getFlag1())) live.add(n.getKey());
            }
            metrics.add(metric("routes", "路线（已上架）", live.size(), 5, null, "AREA",
                    "有站点落在本片区的路线共 " + all.size() + " 条，未上架 " + (all.size() - live.size()) + " 条"));
        }
        if (shops == null) {
            metrics.add(unavailable("shops", "营业商家", 30, null));
            metrics.add(unavailable("shopGames", "有游戏的商家", 30, null));
        } else {
            int count = 0, withGame = 0;
            for (SeedGeoPoint s : shops) {
                if (!area.contains(s.getLat(), s.getLng())) continue;
                count++;
                if (one(s.getFlag1())) withGame++;
            }
            metrics.add(metric("shops", "营业商家", count, 30, null, "AREA", "已入驻、账号启用、已在地图定位的商家"));
            metrics.add(metric("shopGames", "有游戏的商家", withGame, 30, null, "AREA", "至少有一个已上架且带游戏的据点"));
        }
        if (board == null) {
            metrics.add(unavailable("boardPlaces", "CITY 棋盘地点", 20, 30));
        } else {
            int count = 0;
            Set<String> boards = new HashSet<>();
            for (SeedGeoPoint b : board) {
                if (!area.contains(b.getLat(), b.getLng())) continue;
                count++; boards.add(b.getKey());
            }
            metrics.add(metric("boardPlaces", "CITY 棋盘地点", count, 20, 30, "AREA", "来自 " + boards.size() + " 张未关闭的棋盘"));
        }
        Integer missions = read("missions", mapper::countActiveDailyMissions);
        metrics.add(missions == null ? unavailable("dailyMissions", "每日任务", 3, null)
                : metric("dailyMissions", "每日任务", missions, 3, null, "CITY", "全平台启用中的每日成长任务，不分片区"));
        String keyword = query.getCityKeyword() == null || query.getCityKeyword().isBlank() ? null : query.getCityKeyword().trim();
        Integer events = read("events", () -> mapper.countOpeningEvents(keyword));
        metrics.add(events == null ? unavailable("openingEvent", "开城活动", 1, null)
                : metric("openingEvent", "开城活动", events, 1, null, "CITY",
                        keyword == null ? "已排期、报名中或进行中的官方活动（全国）" : "城市含「" + keyword + "」或全国的官方活动"));
        out.put("metrics", metrics);
        return out;
    }

    private Area boardBox(String boardId) {
        for (Map<String, Object> board : areas()) {
            if (boardId.equals(String.valueOf(board.get("boardId")))) {
                return Area.box(dec(board.get("south")), dec(board.get("west")), dec(board.get("north")), dec(board.get("east")),
                        "CITY 棋盘：" + board.get("title"));
            }
        }
        throw new ServiceException("找不到这个棋盘片区，或它已关闭");
    }

    private static BigDecimal dec(Object value) { return value == null ? null : new BigDecimal(value.toString()); }

    private static boolean one(Integer flag) { return flag != null && flag == 1; }

    private static <T> T read(String what, Supplier<T> query) {
        try { return query.get(); }
        catch (RuntimeException e) { log.warn("[Density] {} unavailable", what, e); return null; }
    }

    static Map<String, Object> metric(String key, String label, int count, Integer target, Integer targetMax, String scope, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key); m.put("label", label); m.put("count", count);
        m.put("target", target); m.put("targetMax", targetMax); m.put("scope", scope);
        m.put("state", target == null ? "INFO" : count < target ? "BELOW" : targetMax != null && count > targetMax ? "ABOVE" : "MET");
        m.put("detail", detail);
        return m;
    }

    static Map<String, Object> unavailable(String key, String label, Integer target, Integer targetMax) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key); m.put("label", label); m.put("count", null);
        m.put("target", target); m.put("targetMax", targetMax); m.put("state", "UNAVAILABLE");
        m.put("detail", "数据暂时读不到，不按 0 计");
        return m;
    }

    /** Box plus optional polygon (ray casting on lat/lng; fine at district scale). */
    static final class Area {
        BigDecimal minLat, maxLat, minLng, maxLng;
        double[][] polygon;
        String source;

        static Area resolve(DensityQuery q, java.util.function.Function<String, Area> boards) {
            if (q == null) throw new ServiceException("请选择片区");
            if (q.getBoardId() != null && !q.getBoardId().isBlank()) return boards.apply(q.getBoardId().trim());
            if (q.getPolygon() != null && !q.getPolygon().isEmpty()) return polygon(q.getPolygon());
            return box(q.getSouth(), q.getWest(), q.getNorth(), q.getEast(), "矩形范围");
        }

        static Area box(BigDecimal south, BigDecimal west, BigDecimal north, BigDecimal east, String source) {
            if (south == null || west == null || north == null || east == null) throw new ServiceException("请填写完整的南、西、北、东边界");
            if (south.compareTo(north) >= 0 || west.compareTo(east) >= 0) throw new ServiceException("南边界要小于北边界，西边界要小于东边界");
            if (south.abs().doubleValue() > 90 || north.abs().doubleValue() > 90 || west.abs().doubleValue() > 180 || east.abs().doubleValue() > 180)
                throw new ServiceException("边界坐标无效");
            if (north.subtract(south).doubleValue() > MAX_SPAN_DEGREES || east.subtract(west).doubleValue() > MAX_SPAN_DEGREES)
                throw new ServiceException("片区太大：南北、东西跨度都不能超过 0.5 度（约 55 公里）");
            Area a = new Area();
            a.minLat = south; a.maxLat = north; a.minLng = west; a.maxLng = east; a.source = source;
            return a;
        }

        static Area polygon(List<List<BigDecimal>> points) {
            if (points.size() < 3 || points.size() > 200) throw new ServiceException("多边形需要 3 到 200 个顶点");
            double[][] poly = new double[points.size()][];
            BigDecimal s = null, w = null, n = null, e = null;
            for (int i = 0; i < points.size(); i++) {
                List<BigDecimal> pt = points.get(i);
                if (pt == null || pt.size() != 2 || pt.get(0) == null || pt.get(1) == null) throw new ServiceException("多边形顶点格式应为 [纬度，经度]");
                BigDecimal lat = pt.get(0), lng = pt.get(1);
                poly[i] = new double[] {lat.doubleValue(), lng.doubleValue()};
                s = s == null || lat.compareTo(s) < 0 ? lat : s; n = n == null || lat.compareTo(n) > 0 ? lat : n;
                w = w == null || lng.compareTo(w) < 0 ? lng : w; e = e == null || lng.compareTo(e) > 0 ? lng : e;
            }
            Area a = box(s, w, n, e, "自绘多边形（" + points.size() + " 个顶点）");
            a.polygon = poly;
            return a;
        }

        boolean contains(BigDecimal lat, BigDecimal lng) {
            if (lat == null || lng == null) return false;
            if (lat.compareTo(minLat) < 0 || lat.compareTo(maxLat) > 0 || lng.compareTo(minLng) < 0 || lng.compareTo(maxLng) > 0) return false;
            if (polygon == null) return true;
            double y = lat.doubleValue(), x = lng.doubleValue();
            boolean inside = false;
            for (int i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
                double yi = polygon[i][0], xi = polygon[i][1], yj = polygon[j][0], xj = polygon[j][1];
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside;
            }
            return inside;
        }

        Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", source);
            m.put("south", minLat); m.put("west", minLng); m.put("north", maxLat); m.put("east", maxLng);
            m.put("polygonPoints", polygon == null ? 0 : polygon.length);
            return m;
        }
    }
}
