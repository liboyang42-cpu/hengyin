package com.chengyinhub.business.support;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Geohash helpers for the fog bounding-box read. Pure math, same alphabet and bit order as the reveal tiles
 * (the app's RoamExperienceMath.tileKey). Coordinates are in the datum the tiles were written in (GCJ-02); nothing
 * here re-projects.
 */
public final class RoamGeohashCover {
    public static final String ALPHABET = "0123456789bcdefghjkmnpqrstuvwxyz";
    /** Prefix length used to look cells up. A precision-5 cell is about 4.9 km x 4.9 km. */
    public static final int PREFIX_LENGTH = 5;
    /** Upper bound on prefixes per query: about a 50 km x 50 km box. Larger boxes are refused, never truncated. */
    public static final int MAX_PREFIXES = 120;

    private RoamGeohashCover() {}

    public static boolean isCell(String code, int length) {
        if (code == null || code.length() != length) return false;
        for (int i = 0; i < code.length(); i++) if (ALPHABET.indexOf(code.charAt(i)) < 0) return false;
        return true;
    }

    public static String encode(double latitude, double longitude, int precision) {
        double[] lat = {-90, 90}, lng = {-180, 180};
        StringBuilder out = new StringBuilder(precision);
        boolean even = true;
        int bit = 0, value = 0;
        while (out.length() < precision) {
            double[] range = even ? lng : lat;
            double point = even ? longitude : latitude;
            double mid = (range[0] + range[1]) / 2;
            if (point >= mid) { value = (value << 1) | 1; range[0] = mid; } else { value <<= 1; range[1] = mid; }
            even = !even;
            if (++bit == 5) { out.append(ALPHABET.charAt(value)); bit = 0; value = 0; }
        }
        return out.toString();
    }

    /** {south, west, north, east} of a geohash cell, or null for a malformed code. */
    public static double[] bounds(String code) {
        if (code == null || code.isEmpty()) return null;
        double[] lat = {-90, 90}, lng = {-180, 180};
        boolean even = true;
        for (int i = 0; i < code.length(); i++) {
            int v = ALPHABET.indexOf(Character.toLowerCase(code.charAt(i)));
            if (v < 0) return null;
            for (int shift = 4; shift >= 0; shift--) {
                boolean upper = ((v >> shift) & 1) == 1;
                double[] range = even ? lng : lat;
                double mid = (range[0] + range[1]) / 2;
                if (upper) range[0] = mid; else range[1] = mid;
                even = !even;
            }
        }
        return new double[] {lat[0], lng[0], lat[1], lng[1]};
    }

    /** True when the box is finite, ordered and inside the world. Boxes across the antimeridian are not accepted. */
    public static boolean validBox(Double south, Double west, Double north, Double east) {
        if (south == null || west == null || north == null || east == null) return false;
        if (!Double.isFinite(south) || !Double.isFinite(west) || !Double.isFinite(north) || !Double.isFinite(east)) return false;
        return south >= -90 && north <= 90 && west >= -180 && east <= 180 && south < north && west < east;
    }

    /**
     * Every precision-{@code PREFIX_LENGTH} cell that intersects the box, or null when that is more than
     * {@code MAX_PREFIXES} cells.
     */
    public static Set<String> prefixes(double south, double west, double north, double east) {
        double cellLat = 180.0 / (1L << (PREFIX_LENGTH * 5 / 2));          // 12 latitude bits at precision 5
        double cellLng = 360.0 / (1L << (PREFIX_LENGTH * 5 - PREFIX_LENGTH * 5 / 2)); // 13 longitude bits
        long rows = (long) Math.floor((north - south) / cellLat) + 2, cols = (long) Math.floor((east - west) / cellLng) + 2;
        if (rows * cols > MAX_PREFIXES * 4L) return null;
        Set<String> out = new TreeSet<>();
        for (long r = 0; r <= rows; r++) {
            double lat = Math.min(north, south + r * cellLat);
            for (long c = 0; c <= cols; c++) {
                double lng = Math.min(east, west + c * cellLng);
                out.add(encode(lat, lng, PREFIX_LENGTH));
                if (out.size() > MAX_PREFIXES) return null;
            }
        }
        return out;
    }

    /** True when the cell rectangle intersects the box (edges touching count). */
    public static boolean intersects(String code, double south, double west, double north, double east) {
        double[] b = bounds(code);
        return b != null && b[0] <= north && b[2] >= south && b[1] <= east && b[3] >= west;
    }

    public static String normalize(String code) {
        return code == null ? null : code.trim().toLowerCase(Locale.ROOT);
    }
}
