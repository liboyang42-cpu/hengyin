package com.chengyinhub.business.home;

import com.chengyinhub.common.exception.ServiceException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Required versioned return-home rules. Missing or invalid values stay closed.
 * This bean does not enable the authority and ships no production numbers.
 */
@Component
@ConfigurationProperties(prefix = "app.private-home.location-policy")
public class PrivateHomeLocationPolicy {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern FUNDS = Pattern.compile("money|wallet|cash|sponsor|budget|entitlement|purchase|fund");
    private boolean enabled;
    private String version;
    private String resourceScope;
    private String resourceType;
    private String placementRuleVersion;
    private String arrivalGrantMode;
    private Long resourceCap;
    private Long radiusMeters;
    private Long dwellMillis;
    private Long freshnessMillis;
    private Long accuracyMaxMeters;
    private Long verifyCooldownMillis;
    private Long changeCooldownMillis;
    private Long restoreCooldownMillis;
    private Long proofTtlMillis;
    private Long locationRetentionTtlMillis;
    private Long activationDelayMillis;

    public synchronized boolean isEnabled() { return enabled; }
    public synchronized void setEnabled(boolean enabled) { this.enabled = enabled; }
    public synchronized String getVersion() { return version; }
    public synchronized void setVersion(String version) { this.version = version; }
    public synchronized String getResourceScope() { return resourceScope; }
    public synchronized void setResourceScope(String resourceScope) { this.resourceScope = resourceScope; }
    public synchronized String getResourceType() { return resourceType; }
    public synchronized void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public synchronized String getPlacementRuleVersion() { return placementRuleVersion; }
    public synchronized void setPlacementRuleVersion(String placementRuleVersion) { this.placementRuleVersion = placementRuleVersion; }
    public synchronized String getArrivalGrantMode() { return arrivalGrantMode; }
    public synchronized void setArrivalGrantMode(String arrivalGrantMode) { this.arrivalGrantMode = arrivalGrantMode; }
    public synchronized Long getResourceCap() { return resourceCap; }
    public synchronized void setResourceCap(Long resourceCap) { this.resourceCap = resourceCap; }
    public synchronized Long getRadiusMeters() { return radiusMeters; }
    public synchronized void setRadiusMeters(Long radiusMeters) { this.radiusMeters = radiusMeters; }
    public synchronized Long getDwellMillis() { return dwellMillis; }
    public synchronized void setDwellMillis(Long dwellMillis) { this.dwellMillis = dwellMillis; }
    public synchronized Long getFreshnessMillis() { return freshnessMillis; }
    public synchronized void setFreshnessMillis(Long freshnessMillis) { this.freshnessMillis = freshnessMillis; }
    public synchronized Long getAccuracyMaxMeters() { return accuracyMaxMeters; }
    public synchronized void setAccuracyMaxMeters(Long accuracyMaxMeters) { this.accuracyMaxMeters = accuracyMaxMeters; }
    public synchronized Long getVerifyCooldownMillis() { return verifyCooldownMillis; }
    public synchronized void setVerifyCooldownMillis(Long verifyCooldownMillis) { this.verifyCooldownMillis = verifyCooldownMillis; }
    public synchronized Long getChangeCooldownMillis() { return changeCooldownMillis; }
    public synchronized void setChangeCooldownMillis(Long changeCooldownMillis) { this.changeCooldownMillis = changeCooldownMillis; }
    public synchronized Long getRestoreCooldownMillis() { return restoreCooldownMillis; }
    public synchronized void setRestoreCooldownMillis(Long restoreCooldownMillis) { this.restoreCooldownMillis = restoreCooldownMillis; }
    public synchronized Long getProofTtlMillis() { return proofTtlMillis; }
    public synchronized void setProofTtlMillis(Long proofTtlMillis) { this.proofTtlMillis = proofTtlMillis; }
    public synchronized Long getLocationRetentionTtlMillis() { return locationRetentionTtlMillis; }
    public synchronized void setLocationRetentionTtlMillis(Long locationRetentionTtlMillis) { this.locationRetentionTtlMillis = locationRetentionTtlMillis; }
    public synchronized Long getActivationDelayMillis() { return activationDelayMillis; }
    public synchronized void setActivationDelayMillis(Long activationDelayMillis) { this.activationDelayMillis = activationDelayMillis; }

    public static boolean deniesFunds(String scope, String type) {
        String haystack = ((scope == null ? "" : scope) + " " + (type == null ? "" : type)).toLowerCase(Locale.ROOT);
        return FUNDS.matcher(haystack).find();
    }

    public synchronized Snapshot requireComplete() {
        if (!enabled || !token(version)) fail("HOME_RULES_UNAVAILABLE");
        if (!positive(radiusMeters) || dwellMillis == null || dwellMillis < 0 || !positive(freshnessMillis)
                || !positive(accuracyMaxMeters) || !nonnegative(verifyCooldownMillis) || !nonnegative(changeCooldownMillis)
                || !nonnegative(restoreCooldownMillis) || !positive(proofTtlMillis) || !positive(locationRetentionTtlMillis)
                || !nonnegative(activationDelayMillis)) fail("HOME_RULES_UNAVAILABLE");
        if (!token(resourceScope) || resourceScope.length() > 64 || !("STAMINA".equals(resourceType) || "ENERGY".equals(resourceType))
                || resourceCap == null || resourceCap <= 0 || deniesFunds(resourceScope, resourceType)) fail("RESOURCE_NOT_AUTHORIZED");
        return new Snapshot(version, resourceScope, resourceType, resourceCap, radiusMeters, dwellMillis, freshnessMillis,
                accuracyMaxMeters, verifyCooldownMillis, changeCooldownMillis, restoreCooldownMillis, proofTtlMillis,
                locationRetentionTtlMillis, activationDelayMillis);
    }

    private static boolean token(String value) { return value != null && TOKEN.matcher(value).matches(); }
    private static boolean positive(Long value) { return value != null && value > 0; }
    private static boolean nonnegative(Long value) { return value != null && value >= 0; }
    private static void fail(String code) { throw new ServiceException(code); }

    public static final class Snapshot extends PrivateHomeContracts.Redacted {
        public final String version, resourceScope, resourceType;
        public final long resourceCap, radiusMeters, dwellMillis, freshnessMillis, accuracyMaxMeters;
        public final long verifyCooldownMillis, changeCooldownMillis, restoreCooldownMillis, proofTtlMillis;
        public final long locationRetentionTtlMillis, activationDelayMillis;
        private Snapshot(String version, String resourceScope, String resourceType, long resourceCap, long radiusMeters,
                         long dwellMillis, long freshnessMillis, long accuracyMaxMeters, long verifyCooldownMillis,
                         long changeCooldownMillis, long restoreCooldownMillis, long proofTtlMillis,
                         long locationRetentionTtlMillis, long activationDelayMillis) {
            this.version = version; this.resourceScope = resourceScope; this.resourceType = resourceType;
            this.resourceCap = resourceCap; this.radiusMeters = radiusMeters; this.dwellMillis = dwellMillis;
            this.freshnessMillis = freshnessMillis; this.accuracyMaxMeters = accuracyMaxMeters;
            this.verifyCooldownMillis = verifyCooldownMillis; this.changeCooldownMillis = changeCooldownMillis;
            this.restoreCooldownMillis = restoreCooldownMillis; this.proofTtlMillis = proofTtlMillis;
            this.locationRetentionTtlMillis = locationRetentionTtlMillis; this.activationDelayMillis = activationDelayMillis;
        }
    }
}
