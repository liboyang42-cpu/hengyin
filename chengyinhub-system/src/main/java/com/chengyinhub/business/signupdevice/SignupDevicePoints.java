package com.chengyinhub.business.signupdevice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * SEC-11: sign-up points once per device for accounts created from the iOS app.
 *
 * <p>The app sends {@value #HEADER} on its login calls (phone, Apple, WeChat app): the identifierForVendor UUID, or
 * the App Attest key id where available. Only a SHA-256 of the normalized value is stored ({@code cy_signup_device}),
 * one row per device, claimed with {@code INSERT IGNORE} so two simultaneous sign-ups on one device cannot both win.
 *
 * <ul>
 *   <li>first account on a device: today's sign-up points;</li>
 *   <li>any later account on the same device: no sign-up points (the account is still created);</li>
 *   <li>no or malformed header: today's behaviour, unless {@code chengyin.signup-points.require-device-id=true}
 *       (default false, so app versions without the header are not penalised; turn on after the app release);</li>
 *   <li>a database error: today's behaviour, logged. Sign-up points are not cash; registration never fails here.</li>
 * </ul>
 * The mini-program paths ({@code /api/login/code}, {@code /api/register}) do not call this class.
 */
@Service
public class SignupDevicePoints {
    private static final Logger log = LoggerFactory.getLogger(SignupDevicePoints.class);

    public static final String HEADER = "X-Device-Id";
    private static final Pattern UUID = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    /** App Attest key ids are Base64 (44 chars for SHA-256); allow URL-safe too. */
    private static final Pattern KEY_ID = Pattern.compile("^[A-Za-z0-9+/_=-]{16,128}$");

    private final JdbcTemplate jdbc;
    private final boolean requireDeviceId;

    public SignupDevicePoints(JdbcTemplate jdbc,
                              @Value("${chengyin.signup-points.require-device-id:false}") boolean requireDeviceId) {
        this.jdbc = jdbc;
        this.requireDeviceId = requireDeviceId;
    }

    /** Outcome of a claim. {@code hash} is null when no device row was written. */
    public record Claim(String hash, boolean pointsAllowed) {
    }

    /** Null when the header is missing or not a device identifier the app sends. */
    static String normalize(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (UUID.matcher(value).matches()) return "ifv:" + value.toLowerCase(Locale.ROOT);
        if (KEY_ID.matcher(value).matches()) return "attest:" + value;
        return null;
    }

    static String hash(String normalized) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("chengyin-signup-device:" + normalized).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Call before inserting the new member; the result decides whether the welcome points are credited. */
    public Claim claim(String rawDeviceId, String channel) {
        String normalized = normalize(rawDeviceId);
        if (normalized == null) {
            return new Claim(null, !requireDeviceId);
        }
        String hash = hash(normalized);
        try {
            int rows = jdbc.update("INSERT IGNORE INTO cy_signup_device (device_hash, channel, create_time) VALUES (?, ?, NOW())",
                    hash, channel == null ? "" : channel);
            return rows == 1 ? new Claim(hash, true) : new Claim(null, false);
        } catch (RuntimeException e) {
            log.warn("signup device claim failed; sign-up points follow today's rule: errorType={}", e.getClass().getSimpleName());
            return new Claim(null, true);
        }
    }

    /** Records which member took the device's sign-up points. */
    public void attach(Claim claim, Long memberId) {
        if (claim == null || claim.hash() == null || memberId == null) return;
        try {
            jdbc.update("UPDATE cy_signup_device SET member_id = ? WHERE device_hash = ? AND member_id IS NULL", memberId, claim.hash());
        } catch (RuntimeException e) {
            log.warn("signup device attach failed: errorType={}", e.getClass().getSimpleName());
        }
    }

    /** The member insert failed: give the device its claim back so a retry still gets the points. */
    public void release(Claim claim) {
        if (claim == null || claim.hash() == null) return;
        try {
            jdbc.update("DELETE FROM cy_signup_device WHERE device_hash = ? AND member_id IS NULL", claim.hash());
        } catch (RuntimeException e) {
            log.warn("signup device release failed: errorType={}", e.getClass().getSimpleName());
        }
    }
}
