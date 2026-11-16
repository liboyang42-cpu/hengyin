package com.chengyinhub.business.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MPP-7：一段步行导航（玩家当前位置 → 下一站），给小程序「在小程序里走过去」画线和算剩余距离。
 * 坐标一律 GCJ-02，与 wx.getLocation({type:'gcj02'}) 一致。
 */
public final class WalkRoute
{
    private final int distanceM;
    private final int durationS;
    private final List<double[]> polyline;

    public WalkRoute(int distanceM, int durationS, List<double[]> polyline)
    {
        this.distanceM = Math.max(0, distanceM);
        this.durationS = Math.max(0, durationS);
        this.polyline = polyline == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(polyline));
    }

    public int getDistanceM() { return distanceM; }

    public int getDurationS() { return durationS; }

    public List<double[]> getPolyline() { return polyline; }

    /** 接口回包：{distanceM, durationS, polyline: [[lat,lng], ...]} */
    public Map<String, Object> toView()
    {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("distanceM", distanceM);
        view.put("durationS", durationS);
        List<List<Double>> points = new ArrayList<>(polyline.size());
        for (double[] p : polyline)
        {
            List<Double> pair = new ArrayList<>(2);
            pair.add(p[0]);
            pair.add(p[1]);
            points.add(pair);
        }
        view.put("polyline", points);
        return view;
    }
}
