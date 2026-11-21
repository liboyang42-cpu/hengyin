package com.chengyinhub.business.gamemech;

/** Small geometry helpers for G2 mechanics (GCJ-02 in, metres out; good enough below a few kilometres). */
public final class GeoDistance {
    private GeoDistance() {}

    public static double meters(double lat1, double lng1, double lat2, double lng2) {
        double r = 6_371_000d;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Rounds a coordinate to roughly 500 m so a player sees the search area, not the spot. */
    public static double area(double value) {
        return Math.round(value * 200d) / 200d;
    }

    public static Double number(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) return n.doubleValue();
        try {
            String text = raw.toString().trim();
            return text.isEmpty() ? null : Double.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean validChina(Double lat, Double lng) {
        return lat != null && lng != null && Double.isFinite(lat) && Double.isFinite(lng)
                && lat >= 3.0 && lat <= 54.0 && lng >= 73.0 && lng <= 136.0;
    }
}
