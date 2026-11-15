package com.chengyinhub.business.locationtrust;

/** Minimal geohash decoder (same alphabet as the mini-program's utils/roam-geo.js geohash7). */
public final class Geohash {
    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";

    private Geohash() {
    }

    /** @return {lat, lng} of the cell centre, or null for an invalid hash */
    public static double[] decodeCenter(String hash) {
        if (hash == null || hash.isEmpty() || hash.length() > 12) return null;
        double latMin = -90d, latMax = 90d, lngMin = -180d, lngMax = 180d;
        boolean evenBit = true;
        for (int i = 0; i < hash.length(); i++) {
            int cd = BASE32.indexOf(hash.charAt(i));
            if (cd < 0) return null;
            for (int mask = 16; mask > 0; mask >>= 1) {
                boolean bit = (cd & mask) != 0;
                if (evenBit) {
                    double mid = (lngMin + lngMax) / 2d;
                    if (bit) lngMin = mid; else lngMax = mid;
                } else {
                    double mid = (latMin + latMax) / 2d;
                    if (bit) latMin = mid; else latMax = mid;
                }
                evenBit = !evenBit;
            }
        }
        return new double[] {(latMin + latMax) / 2d, (lngMin + lngMax) / 2d};
    }
}
