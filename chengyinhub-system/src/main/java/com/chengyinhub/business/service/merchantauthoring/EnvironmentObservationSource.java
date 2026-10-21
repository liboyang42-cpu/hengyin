package com.chengyinhub.business.service.merchantauthoring;

/**
 * Approved environment provider. No production bean is registered; a missing bean is not weather.
 */
public interface EnvironmentObservationSource {
    String providerId();

    Observation current(Long merchantId);

    final class Observation {
        public final String source;
        public final Long observedAtMs;
        public final Long expiresAtMs;
        public final String timezone;
        public final String accuracy;
        public final String condition;

        public Observation(String source, Long observedAtMs, Long expiresAtMs, String timezone,
                String accuracy, String condition) {
            this.source = source;
            this.observedAtMs = observedAtMs;
            this.expiresAtMs = expiresAtMs;
            this.timezone = timezone;
            this.accuracy = accuracy;
            this.condition = condition;
        }
    }
}
