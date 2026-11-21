package com.chengyinhub.business.account.apple;

import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.system.service.ISysConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * FLOW-03: revokes a deleted account's Sign in with Apple tokens through Apple's REST endpoint
 * ({@code POST https://appleid.apple.com/auth/revoke}).
 *
 * <ol>
 *   <li>{@link #prepare}: when an Apple-confirmed deletion is accepted, the authorization code from the same
 *       re-authentication is exchanged for a refresh token ({@code /auth/token}). The code is valid for five minutes, so
 *       this must happen at apply time. The refresh token is stored encrypted ({@code chengyin.pii.key}); without that
 *       key it is not stored at all.</li>
 *   <li>{@link #revokeAfterDeletion}: when the cooling-off period ends and the account is erased, the stored token is
 *       revoked and then wiped.</li>
 *   <li>{@link #discard}: a cancelled deletion wipes the stored token.</li>
 * </ol>
 * The client secret is an ES256 JWT built from {@code APPLE_SIGNIN_KEY_ID}, {@code APPLE_SIGNIN_TEAM_ID} and
 * {@code APPLE_SIGNIN_PRIVATE_KEY} (PKCS#8 PEM). Without that configuration the row is recorded as pending and a warning
 * is logged. Nothing here ever throws into, or fails, the deletion.
 */
@Service
public class AppleTokenRevocationService {
    private static final Logger log = LoggerFactory.getLogger(AppleTokenRevocationService.class);
    static final String APPLE_AUDIENCE = "https://appleid.apple.com";
    static final String TOKEN_URL = "https://appleid.apple.com/auth/token";
    static final String REVOKE_URL = "https://appleid.apple.com/auth/revoke";

    /** Refresh token stored, revocation will run when deletion completes. */
    public static final String TOKEN_READY = "TOKEN_READY";
    /** Revocation keys were missing; an operator must finish revocation (see RESULT / ops notes). */
    public static final String PENDING_CONFIG = "PENDING_CONFIG";
    /** The authorization code could not be exchanged, or the token could not be stored safely. */
    public static final String PENDING_NO_TOKEN = "PENDING_NO_TOKEN";
    public static final String REVOKED = "REVOKED";
    public static final String REVOKE_FAILED = "REVOKE_FAILED";
    public static final String CANCELLED = "CANCELLED";

    private final JdbcTemplate jdbc;
    private final ISysConfigService config;
    private final PiiCryptoService crypto;
    private final String keyId;
    private final String teamId;
    private final String privateKeyPem;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    Clock clock = Clock.systemUTC();

    @Autowired
    public AppleTokenRevocationService(JdbcTemplate jdbc, ISysConfigService config,
                                       PiiCryptoService crypto,
                                       @Value("${APPLE_SIGNIN_KEY_ID:}") String keyId,
                                       @Value("${APPLE_SIGNIN_TEAM_ID:}") String teamId,
                                       @Value("${APPLE_SIGNIN_PRIVATE_KEY:}") String privateKeyPem) {
        this.jdbc = jdbc;
        this.config = config;
        this.crypto = crypto;
        this.keyId = keyId == null ? "" : keyId.trim();
        this.teamId = teamId == null ? "" : teamId.trim();
        this.privateKeyPem = privateKeyPem == null ? "" : privateKeyPem.trim();
    }

    /** Whether the revocation key and the app's client id are configured. */
    public boolean configured() {
        return !keyId.isEmpty() && !teamId.isEmpty() && !privateKeyPem.isEmpty() && clientId() != null;
    }

    /** Called after an Apple-confirmed deletion was accepted. Best effort; returns the recorded status. */
    public String prepare(Long memberId, String authorizationCode) {
        if (memberId == null) return null;
        try {
            if (!configured()) {
                log.warn("[Apple revocation] Sign in with Apple key is not configured; revocation for member {} is pending", memberId);
                return record(memberId, PENDING_CONFIG, null, "NOT_CONFIGURED");
            }
            if (crypto == null || !crypto.isEnabled()) {
                log.warn("[Apple revocation] chengyin.pii.key is not configured; the refresh token is not stored, revocation for member {} is pending", memberId);
                return record(memberId, PENDING_NO_TOKEN, null, "NO_ENCRYPTION_KEY");
            }
            if (authorizationCode == null || authorizationCode.isBlank() || authorizationCode.length() > 1024) {
                return record(memberId, PENDING_NO_TOKEN, null, "NO_AUTHORIZATION_CODE");
            }
            Map<String, String> form = new LinkedHashMap<>();
            form.put("client_id", clientId());
            form.put("client_secret", clientSecret());
            form.put("code", authorizationCode);
            form.put("grant_type", "authorization_code");
            Response response = post(TOKEN_URL, form);
            String refreshToken = null;
            if (response != null && response.status == 200 && response.body != null) {
                JsonNode json = new ObjectMapper().readTree(response.body);
                refreshToken = json.path("refresh_token").asText(null);
            }
            if (refreshToken == null || refreshToken.isBlank()) {
                log.warn("[Apple revocation] authorization code exchange failed for member {} (status {})",
                        memberId, response == null ? "none" : response.status);
                return record(memberId, PENDING_NO_TOKEN, null, "EXCHANGE_FAILED");
            }
            return record(memberId, TOKEN_READY, crypto.encrypt(refreshToken), null);
        } catch (Exception e) {
            log.warn("[Apple revocation] prepare failed for member {}: {}", memberId, e.getClass().getSimpleName());
            return safeRecord(memberId, PENDING_NO_TOKEN, "PREPARE_ERROR");
        }
    }

    /** Called once the account has been erased. Never throws. */
    public String revokeAfterDeletion(Long memberId) {
        if (memberId == null) return null;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT status, refresh_token_cipher FROM ums_apple_token_revocation WHERE member_id = ?", memberId);
            if (rows.isEmpty()) return null; // not an Apple-confirmed deletion
            String status = (String) rows.get(0).get("status");
            String cipher = (String) rows.get(0).get("refresh_token_cipher");
            if (REVOKED.equals(status)) return REVOKED;
            if (cipher == null || cipher.isEmpty()) {
                log.warn("[Apple revocation] member {} deleted; Apple token revocation is still pending ({})", memberId, status);
                return status;
            }
            if (!configured()) {
                log.warn("[Apple revocation] member {} deleted but the Sign in with Apple key is not configured; revocation is pending", memberId);
                update(memberId, PENDING_CONFIG, cipher, "NOT_CONFIGURED");
                return PENDING_CONFIG;
            }
            Map<String, String> form = new LinkedHashMap<>();
            form.put("client_id", clientId());
            form.put("client_secret", clientSecret());
            form.put("token", crypto.decrypt(cipher));
            form.put("token_type_hint", "refresh_token");
            Response response = post(REVOKE_URL, form);
            if (response != null && response.status == 200) {
                jdbc.update("UPDATE ums_apple_token_revocation SET status = ?, refresh_token_cipher = NULL, last_error = NULL,"
                        + " revoked_at = ?, update_time = ? WHERE member_id = ?", REVOKED, now(), now(), memberId);
                return REVOKED;
            }
            log.warn("[Apple revocation] Apple refused revocation for member {} (status {})", memberId,
                    response == null ? "none" : response.status);
            update(memberId, REVOKE_FAILED, cipher, "HTTP_" + (response == null ? "NONE" : response.status));
            return REVOKE_FAILED;
        } catch (Exception e) {
            log.warn("[Apple revocation] revocation failed for member {}: {}", memberId, e.getClass().getSimpleName());
            safeUpdateError(memberId, "REVOKE_ERROR");
            return REVOKE_FAILED;
        }
    }

    /** The owner cancelled the deletion: wipe the stored token. Never throws. */
    public void discard(Long memberId) {
        if (memberId == null) return;
        try {
            jdbc.update("UPDATE ums_apple_token_revocation SET status = ?, refresh_token_cipher = NULL, update_time = ?"
                    + " WHERE member_id = ? AND status <> ?", CANCELLED, now(), memberId, REVOKED);
        } catch (Exception e) {
            log.warn("[Apple revocation] discard failed for member {}: {}", memberId, e.getClass().getSimpleName());
        }
    }

    /** ES256 client secret for Apple's token endpoints, valid five minutes. */
    String clientSecret() throws Exception {
        long now = clock.millis();
        return Jwts.builder().header().keyId(keyId).and().issuer(teamId)
                .issuedAt(new Date(now)).expiration(new Date(now + 300_000L))
                .audience().single(APPLE_AUDIENCE).subject(clientId())
                .signWith(privateKey(), Jwts.SIG.ES256).compact();
    }

    PrivateKey privateKey() throws Exception {
        String pem = privateKeyPem.replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
    }

    String clientId() {
        String value = config.selectConfigByKey(AppleIdentityReconfirmationService.CLIENT_ID_CONFIG);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** One HTTPS form POST. Protected for tests. The body is never logged. */
    protected Response post(String url, Map<String, String> form) throws Exception {
        StringJoiner body = new StringJoiner("&");
        for (Map.Entry<String, String> e : form.entrySet()) {
            body.add(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        String text = response.body();
        return new Response(response.statusCode(), text != null && text.length() > 65536 ? null : text);
    }

    private String record(Long memberId, String status, String cipher, String error) {
        Timestamp now = now();
        int updated = jdbc.update("UPDATE ums_apple_token_revocation SET status = ?, refresh_token_cipher = ?, last_error = ?,"
                + " revoked_at = NULL, update_time = ? WHERE member_id = ?", status, cipher, error, now, memberId);
        if (updated == 0) {
            jdbc.update("INSERT INTO ums_apple_token_revocation (member_id, status, refresh_token_cipher, last_error,"
                    + " create_time, update_time) VALUES (?, ?, ?, ?, ?, ?)", memberId, status, cipher, error, now, now);
        }
        return status;
    }

    private String safeRecord(Long memberId, String status, String error) {
        try {
            return record(memberId, status, null, error);
        } catch (Exception e) {
            log.warn("[Apple revocation] could not record status for member {}: {}", memberId, e.getClass().getSimpleName());
            return status;
        }
    }

    private void update(Long memberId, String status, String cipher, String error) {
        jdbc.update("UPDATE ums_apple_token_revocation SET status = ?, refresh_token_cipher = ?, last_error = ?, update_time = ?"
                + " WHERE member_id = ?", status, cipher, error, now(), memberId);
    }

    private void safeUpdateError(Long memberId, String error) {
        try {
            jdbc.update("UPDATE ums_apple_token_revocation SET status = ?, last_error = ?, update_time = ? WHERE member_id = ?",
                    REVOKE_FAILED, error, now(), memberId);
        } catch (Exception ignored) {
            // logging only; deletion is already complete
        }
    }

    private Timestamp now() {
        return new Timestamp(clock.millis());
    }

    /** Minimal HTTP response. */
    public static final class Response {
        final int status;
        final String body;

        public Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
