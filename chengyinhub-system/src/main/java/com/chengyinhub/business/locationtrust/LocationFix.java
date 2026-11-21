package com.chengyinhub.business.locationtrust;

/**
 * One player-reported location fix plus the quality signals the client sent with it.
 *
 * <p>Every field except the coordinates is optional: the mini-program and older app builds send only
 * lat/lng, and a missing signal never counts against the player.
 *
 * @param lat               WGS84/GCJ02 latitude as the client sent it
 * @param lng               longitude
 * @param accuracyMeters    horizontal accuracy in metres; null = not sent
 * @param clientTimestampMs fix time on the device (epoch ms); null = not sent
 * @param simulated         iOS {@code isSimulatedBySoftware}; null = not sent
 * @param accessory         iOS {@code isProducedByAccessory}; null = not sent
 */
public record LocationFix(double lat, double lng, Double accuracyMeters, Long clientTimestampMs,
                          Boolean simulated, Boolean accessory) {

    public static LocationFix of(double lat, double lng) {
        return new LocationFix(lat, lng, null, null, null, null);
    }

    public boolean validCoordinates() {
        return Double.isFinite(lat) && Double.isFinite(lng) && lat >= -90d && lat <= 90d && lng >= -180d && lng <= 180d;
    }
}
