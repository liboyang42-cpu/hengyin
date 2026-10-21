package com.chengyinhub.business.domain.map;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 调用方必须显式给出的公开地图查询。没有半径、层或 0.5° 缺省。 */
public class MapPublicQuery {

    private static final BigDecimal MIN_LAT = new BigDecimal("-90");
    private static final BigDecimal MAX_LAT = new BigDecimal("90");
    private static final BigDecimal MIN_LNG = new BigDecimal("-180");
    private static final BigDecimal MAX_LNG = new BigDecimal("180");

    private String datum;
    private BigDecimal minLat;
    private BigDecimal maxLat;
    private BigDecimal minLng;
    private BigDecimal maxLng;
    private Integer limit;
    private List<String> layers;
    private String cursor;

    public Normalized normalize() {
        if (datum == null || datum.isEmpty()) {
            throw new MapPublicException("DATUM_REQUIRED");
        }
        if (!MapPublicLimits.DATUM.equals(datum)) {
            throw new MapPublicException("DATUM_REJECTED");
        }
        BigDecimal lat0 = coordinate(minLat, MIN_LAT, MAX_LAT);
        BigDecimal lat1 = coordinate(maxLat, MIN_LAT, MAX_LAT);
        BigDecimal lng0 = coordinate(minLng, MIN_LNG, MAX_LNG);
        BigDecimal lng1 = coordinate(maxLng, MIN_LNG, MAX_LNG);
        if (lat0.compareTo(lat1) > 0 || lng0.compareTo(lng1) > 0) {
            throw new MapPublicException("BBOX_REJECTED");
        }
        BigDecimal area = lat1.subtract(lat0).multiply(lng1.subtract(lng0));
        if (area.compareTo(MapPublicLimits.effectiveMaxArea()) > 0) {
            throw new MapPublicException("AREA_REJECTED");
        }
        if (limit == null || limit < 1 || limit > MapPublicLimits.effectivePageLimit()) {
            throw new MapPublicException("LIMIT_REJECTED");
        }
        if (layers == null || layers.isEmpty() || layers.size() > MapPublicLimits.effectiveLayerCount()) {
            throw new MapPublicException("LAYERS_REJECTED");
        }
        List<String> names = new ArrayList<>();
        for (String layer : layers) {
            if (layer == null || !layer.matches("[a-z][a-z0-9_]{0,31}") || names.contains(layer)) {
                throw new MapPublicException("LAYERS_REJECTED");
            }
            names.add(layer);
        }
        return new Normalized(datum, lat0, lat1, lng0, lng1, limit, names);
    }

    private static BigDecimal coordinate(BigDecimal raw, BigDecimal min, BigDecimal max) {
        if (raw == null) {
            throw new MapPublicException("COORDINATE_REJECTED");
        }
        BigDecimal value = raw.stripTrailingZeros();
        if (value.scale() < 0) {
            value = value.setScale(0);
        }
        if (value.scale() > 8 || value.precision() > 12
                || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new MapPublicException("COORDINATE_REJECTED");
        }
        return value;
    }

    public String getDatum() { return datum; }
    public void setDatum(String datum) { this.datum = datum; }
    public BigDecimal getMinLat() { return minLat; }
    public void setMinLat(BigDecimal minLat) { this.minLat = minLat; }
    public BigDecimal getMaxLat() { return maxLat; }
    public void setMaxLat(BigDecimal maxLat) { this.maxLat = maxLat; }
    public BigDecimal getMinLng() { return minLng; }
    public void setMinLng(BigDecimal minLng) { this.minLng = minLng; }
    public BigDecimal getMaxLng() { return maxLng; }
    public void setMaxLng(BigDecimal maxLng) { this.maxLng = maxLng; }
    public Integer getLimit() { return limit; }
    public void setLimit(Integer limit) { this.limit = limit; }
    public List<String> getLayers() { return layers; }
    public void setLayers(List<String> layers) { this.layers = layers; }
    public String getCursor() { return cursor; }
    public void setCursor(String cursor) { this.cursor = cursor; }

    /** 规范化后的查询。指纹含 bbox 与层顺序，供游标绑定。 */
    public static final class Normalized {
        private final String datum;
        private final BigDecimal minLat;
        private final BigDecimal maxLat;
        private final BigDecimal minLng;
        private final BigDecimal maxLng;
        private final int limit;
        private final List<String> layers;

        Normalized(String datum, BigDecimal minLat, BigDecimal maxLat, BigDecimal minLng, BigDecimal maxLng,
                   int limit, List<String> layers) {
            this.datum = datum;
            this.minLat = minLat;
            this.maxLat = maxLat;
            this.minLng = minLng;
            this.maxLng = maxLng;
            this.limit = limit;
            this.layers = Collections.unmodifiableList(layers);
        }

        public String datum() { return datum; }
        public BigDecimal minLat() { return minLat; }
        public BigDecimal maxLat() { return maxLat; }
        public BigDecimal minLng() { return minLng; }
        public BigDecimal maxLng() { return maxLng; }
        public int limit() { return limit; }
        public List<String> layers() { return layers; }

        public String fingerprint() {
            return datum + "\n" + plain(minLat) + "\n" + plain(maxLat) + "\n"
                    + plain(minLng) + "\n" + plain(maxLng) + "\n" + limit + "\n"
                    + String.join(",", layers);
        }

        public static String plain(BigDecimal value) {
            BigDecimal stripped = value.stripTrailingZeros();
            if (stripped.scale() < 0) {
                stripped = stripped.setScale(0);
            }
            return stripped.toPlainString();
        }
    }
}
