package com.chengyinhub.business.begapmisc.home;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Server-versioned policy for the private home and the "I'm back" HP restore (app {@code NativeHomeReturnPolicy}). Every
 * number is configuration; the app never supplies a default. Both features are off unless enabled:
 * {@code chengyin.native.home.enabled} (owner home CRUD) and {@code chengyin.native.home.return.enabled} (the six return
 * routes, which also need the home feature).
 */
@Component
public class NativeHomePolicy {
    final boolean homeEnabled;
    final boolean returnEnabled;
    final String version;
    final long changeCooldownSeconds;
    final long activationDelaySeconds;
    final double radiusMeters;
    final double maxAccuracyMeters;
    final double maxFixAgeSeconds;
    final long dwellSeconds;
    final long proofTtlSeconds;
    final long restoreCooldownSeconds;

    public NativeHomePolicy(@Value("${chengyin.native.home.enabled:false}") boolean homeEnabled,
                            @Value("${chengyin.native.home.return.enabled:false}") boolean returnEnabled,
                            @Value("${chengyin.native.home.policy-version:home-return-v1}") String version,
                            @Value("${chengyin.native.home.change-cooldown-seconds:86400}") long changeCooldownSeconds,
                            @Value("${chengyin.native.home.activation-delay-seconds:0}") long activationDelaySeconds,
                            @Value("${chengyin.native.home.return.radius-meters:150}") double radiusMeters,
                            @Value("${chengyin.native.home.return.max-accuracy-meters:100}") double maxAccuracyMeters,
                            @Value("${chengyin.native.home.return.max-fix-age-seconds:120}") double maxFixAgeSeconds,
                            @Value("${chengyin.native.home.return.dwell-seconds:0}") long dwellSeconds,
                            @Value("${chengyin.native.home.return.proof-ttl-seconds:120}") long proofTtlSeconds,
                            @Value("${chengyin.native.home.return.restore-cooldown-seconds:21600}") long restoreCooldownSeconds) {
        this.homeEnabled = homeEnabled;
        this.returnEnabled = homeEnabled && returnEnabled;
        this.version = version;
        this.changeCooldownSeconds = Math.max(0, changeCooldownSeconds);
        this.activationDelaySeconds = Math.max(0, activationDelaySeconds);
        this.radiusMeters = radiusMeters;
        this.maxAccuracyMeters = maxAccuracyMeters;
        this.maxFixAgeSeconds = maxFixAgeSeconds;
        this.dwellSeconds = Math.max(0, dwellSeconds);
        this.proofTtlSeconds = Math.max(1, proofTtlSeconds);
        this.restoreCooldownSeconds = Math.max(0, restoreCooldownSeconds);
    }

    public boolean homeEnabled() { return homeEnabled; }

    public boolean returnEnabled() { return returnEnabled; }
}
