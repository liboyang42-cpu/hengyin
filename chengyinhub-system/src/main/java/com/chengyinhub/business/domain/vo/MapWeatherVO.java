package com.chengyinhub.business.domain.vo;

import java.io.Serializable;

/**
 * 地图天气氛围(只给画面用):kind 为 rain/snow/fog/clear,intensity 为 0..1。
 * 不透传供应商原文、地名或温度。
 */
public class MapWeatherVO implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String RAIN = "rain";
    public static final String SNOW = "snow";
    public static final String FOG = "fog";
    public static final String CLEAR = "clear";

    private final String kind;
    private final double intensity;

    public MapWeatherVO(String kind, double intensity) {
        boolean known = RAIN.equals(kind) || SNOW.equals(kind) || FOG.equals(kind);
        this.kind = known ? kind : CLEAR;
        this.intensity = known ? Math.max(0D, Math.min(1D, intensity)) : 0D;
    }

    public static MapWeatherVO clear() {
        return new MapWeatherVO(CLEAR, 0D);
    }

    public String getKind() {
        return kind;
    }

    public double getIntensity() {
        return intensity;
    }
}
