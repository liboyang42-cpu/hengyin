package com.chengyinhub.business.service.support;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 漫游简化轨迹(2026-10-31 业主批准 P-7 ③:漫游记录卡片画这次的轨迹,轨迹存服务端)。
 *
 * <p>只收客户端结算时上送的 [[lat,lng],...]:坐标越界 / 非数字的点丢掉,超过 {@link #MAX_POINTS} 均匀抽稀,
 * 坐标保留 5 位小数(约 1 米)。不足 2 个有效点不存 —— 一个点画不成线,卡片按「没有轨迹」处理。
 * 轨迹是展示用的,不参与里程 / 探索值结算(那两样只认服务端事实)。
 */
public final class RoamTrackSimplifier {
    public static final int MAX_POINTS = 300;
    /** 原始上送上限:超过直接不收,不替客户端兜一个超大 payload */
    public static final int MAX_RAW_CHARS = 200_000;

    private RoamTrackSimplifier() {}

    /** 客户端上送 → 落库 JSON;无效返回 null(不存) */
    public static String simplify(String raw) {
        List<double[]> points = read(raw);
        if (points.size() < 2) return null;
        List<double[]> kept = points;
        if (points.size() > MAX_POINTS) {
            kept = new ArrayList<>(MAX_POINTS);
            for (int i = 0; i < MAX_POINTS; i++) {
                kept.add(points.get((int) Math.round(i * (points.size() - 1) / (double) (MAX_POINTS - 1))));
            }
        }
        StringBuilder out = new StringBuilder(kept.size() * 24).append('[');
        for (int i = 0; i < kept.size(); i++) {
            if (i > 0) out.append(',');
            out.append('[').append(round(kept.get(i)[0])).append(',').append(round(kept.get(i)[1])).append(']');
        }
        return out.append(']').toString();
    }

    /** 落库 JSON → 读出给护照历史;坏数据按没有轨迹(null) */
    public static List<List<Double>> parse(String stored) {
        List<double[]> points = read(stored);
        if (points.size() < 2) return null;
        List<List<Double>> out = new ArrayList<>(points.size());
        for (double[] p : points) {
            List<Double> pair = new ArrayList<>(2);
            pair.add(p[0]);
            pair.add(p[1]);
            out.add(pair);
        }
        return out;
    }

    private static List<double[]> read(String raw) {
        List<double[]> points = new ArrayList<>();
        if (raw == null || raw.isEmpty() || raw.length() > MAX_RAW_CHARS) return points;
        JSONArray rows;
        try {
            rows = JSON.parseArray(raw);
        } catch (RuntimeException e) {
            return points;
        }
        if (rows == null) return points;
        for (Object row : rows) {
            if (!(row instanceof List<?> pair) || pair.size() < 2) continue;
            if (!(pair.get(0) instanceof Number lat) || !(pair.get(1) instanceof Number lng)) continue;
            double a = lat.doubleValue(), b = lng.doubleValue();
            if (!Double.isFinite(a) || !Double.isFinite(b) || Math.abs(a) > 90 || Math.abs(b) > 180) continue;
            points.add(new double[]{a, b});
        }
        return points;
    }

    private static String round(double v) {
        return BigDecimal.valueOf(v).setScale(5, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
