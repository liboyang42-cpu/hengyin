package com.chengyinhub.common.utils;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class DistanceCalculatorUtils {
    /**
     * 计算两个经纬度点之间的距离（单位：公里，保留两位小数）。
     * 参数经度在前；球面距离统一走 {@link GeoDistanceUtils}(O-02 收敛),本方法只保留 km 换算与
     * 「缺坐标/不可解析 → 0」的既有调用方合同。
     */
    public double calculateDistance(String lon1, String lat1, String lon2, String lat2) {
        // 缺坐标与不可解析坐标同义:都是「这个点没有可用坐标」,一律 0 公里。
        // 少这一行,Double.parseDouble(null) 抛的是 NPE 而不是 NumberFormatException,
        // 下面的 catch 接不住 ⇒ 它会一路冒到建题接口顶上,变成一句不指名的「主题发布失败」。
        if (lon1 == null || lat1 == null || lon2 == null || lat2 == null) {
            return 0.0;
        }
        try {
            double lng1 = Double.parseDouble(lon1);
            double lat1Val = Double.parseDouble(lat1);
            double lng2 = Double.parseDouble(lon2);
            double lat2Val = Double.parseDouble(lat2);

            double s = GeoDistanceUtils.haversineMeters(lat1Val, lng1, lat2Val, lng2) / 1000d;

            return Math.round(s * 100.0) / 100.0; // 保留两位小数
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
