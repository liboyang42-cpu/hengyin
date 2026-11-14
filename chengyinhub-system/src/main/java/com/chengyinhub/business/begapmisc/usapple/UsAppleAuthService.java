package com.chengyinhub.business.begapmisc.usapple;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.IUmsMemberService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * US market Sign in with Apple (app {@code Core/USAppleService}, {@code docs/us-apple-client.md}).
 *
 * <ul>
 *   <li>{@code challenge()}: three distinct 32-byte base64url values (challengeId, rawNonce, state), TTL 300 s. Only the
 *       SHA-256 hex of the raw nonce and the state are stored; the raw nonce is returned once and never kept.</li>
 *   <li>{@code exchange()}: the challenge is consumed first (single use, even when the identity then fails), then the Apple
 *       identity token is verified: RS256 against the Apple JWKS key named by {@code kid}, {@code iss}
 *       {@code https://appleid.apple.com}, {@code aud} = the configured US client id, not expired, and {@code nonce} = the
 *       stored digest. The {@code sub} is linked to one US account per realm ({@code us_apple_account}); a new sub gets a
 *       new player account. CN accounts are never matched (no phone, openid or {@code ums_member.apple_id} lookup).</li>
 *   <li>{@code session()}: the protected proof. Only an account linked in this realm answers; anything else is
 *       {@code US_SESSION_INVALID} (401).</li>
 * </ul>
 * Every outcome is an {@link Outcome} whose HTTP status equals its {@code code} (the app requires that). The feature is off
 * unless {@code chengyin.us.apple.enabled=true} with a realm and client id; off answers 503 {@code US_APPLE_UNAVAILABLE}.
 */
@Service
public class UsAppleAuthService {
    public static final String INVALID_REQUEST = "US_APPLE_INVALID_REQUEST";
    public static final String INVALID_CHALLENGE = "US_APPLE_INVALID_CHALLENGE";
    public static final String INVALID_IDENTITY = "US_APPLE_INVALID_IDENTITY";
    public static final String RATE_LIMITED = "US_APPLE_RATE_LIMITED";
    public static final String UNAVAILABLE = "US_APPLE_UNAVAILABLE";
    public static final String SESSION_INVALID = "US_SESSION_INVALID";
    static final String ISSUER = "https://appleid.apple.com";
    static final long TTL_SECONDS = 300;
    /** Open challenges per realm; beyond it new challenges are refused until older ones expire. */
    static final int MAX_OPEN_CHALLENGES = 10000;

    private final JdbcTemplate jdbc;
    private final IUmsMemberService members;
    private final UsAppleKeySource keys;
    private final boolean enabled;
    private final String realm;
    private final String clientId;
    private final SecureRandom random = new SecureRandom();
    Clock clock = Clock.systemUTC();

    @Autowired
    public UsAppleAuthService(JdbcTemplate jdbc, IUmsMemberService members, UsAppleKeySource keys,
                              @Value("${chengyin.us.apple.enabled:false}") boolean enabled,
                              @Value("${chengyin.us.apple.realm:}") String realm,
                              @Value("${chengyin.us.apple.client-id:}") String clientId) {
        this.jdbc = jdbc;
        this.members = members;
        this.keys = keys;
        this.enabled = enabled;
        this.realm = realm == null ? "" : realm.trim();
        this.clientId = clientId == null ? "" : clientId.trim();
    }

    public boolean ready() {
        return enabled && !realm.isEmpty() && realm.length() <= 128 && !clientId.isEmpty();
    }

    @Transactional(rollbackFor = Exception.class)
    public Outcome challenge() {
        if (!ready()) return Outcome.error(503, UNAVAILABLE);
        long now = clock.millis();
        jdbc.update("DELETE FROM us_apple_challenge WHERE realm = ? AND expires_at < ?", realm, now - 86_400_000L);
        Integer open = jdbc.queryForObject("SELECT COUNT(*) FROM us_apple_challenge WHERE realm = ? AND consumed_at IS NULL"
                + " AND expires_at > ?", Integer.class, realm, now);
        if (open != null && open >= MAX_OPEN_CHALLENGES) return Outcome.error(429, RATE_LIMITED);
        String challengeId = randomValue(), rawNonce = randomValue(), state = randomValue();
        while (challengeId.equals(rawNonce) || challengeId.equals(state) || rawNonce.equals(state)) {
            rawNonce = randomValue();
            state = randomValue();
        }
        jdbc.update("INSERT INTO us_apple_challenge (challenge_id, realm, nonce_digest, state, issued_at, expires_at)"
                + " VALUES (?, ?, ?, ?, ?, ?)", challengeId, realm, sha256Hex(rawNonce), state, now, now + TTL_SECONDS * 1000);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("challengeId", challengeId);
        data.put("rawNonce", rawNonce);
        data.put("state", state);
        data.put("expiresIn", TTL_SECONDS);
        data.put("market", "US");
        data.put("provider", "apple");
        return Outcome.ok(data);
    }

    /** Returns the linked member id in {@link Outcome#memberId}; the caller issues the session token. */
    @Transactional(rollbackFor = Exception.class)
    public Outcome exchange(Map<String, Object> body) {
        if (!ready()) return Outcome.error(503, UNAVAILABLE);
        if (body == null || body.size() != 3 || !(body.get("challengeId") instanceof String)
                || !(body.get("state") instanceof String) || !(body.get("identityToken") instanceof String)) {
            return Outcome.error(400, INVALID_REQUEST);
        }
        String challengeId = (String) body.get("challengeId"), state = (String) body.get("state");
        String token = (String) body.get("identityToken");
        if (!isRandomValue(challengeId) || !isRandomValue(state) || !isToken(token)) return Outcome.error(400, INVALID_REQUEST);
        long now = clock.millis();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT nonce_digest, state, expires_at, consumed_at"
                + " FROM us_apple_challenge WHERE challenge_id = ? AND realm = ? FOR UPDATE", challengeId, realm);
        if (rows.isEmpty()) return Outcome.error(401, INVALID_CHALLENGE);
        Map<String, Object> row = rows.get(0);
        if (row.get("consumed_at") != null || ((Number) row.get("expires_at")).longValue() <= now) {
            return Outcome.error(401, INVALID_CHALLENGE);
        }
        // Single use: the challenge is spent before the identity is looked at, so a failed token cannot be retried on it.
        jdbc.update("UPDATE us_apple_challenge SET consumed_at = ? WHERE challenge_id = ? AND consumed_at IS NULL", now, challengeId);
        if (!MessageDigest.isEqual(state.getBytes(StandardCharsets.US_ASCII),
                String.valueOf(row.get("state")).getBytes(StandardCharsets.US_ASCII))) {
            return Outcome.error(401, INVALID_CHALLENGE);
        }
        String subject;
        try {
            subject = verifiedSubject(token, String.valueOf(row.get("nonce_digest")));
        } catch (UsAppleKeySource.Unavailable unavailable) {
            return Outcome.error(503, UNAVAILABLE);
        }
        if (subject == null) return Outcome.error(401, INVALID_IDENTITY);
        Long memberId = linkedMember(subject, now);
        if (memberId == null) return Outcome.error(503, UNAVAILABLE);
        UmsMember member = members.selectUmsMemberById(memberId);
        if (member == null || Long.valueOf(2L).equals(member.getStatus()) || Integer.valueOf(2).equals(member.getDelFlag())) {
            return Outcome.error(401, INVALID_IDENTITY);
        }
        Outcome outcome = Outcome.ok(profile(member));
        outcome.memberId = memberId;
        return outcome;
    }

    public Outcome session(Long memberId) {
        if (!ready()) return Outcome.error(503, UNAVAILABLE);
        Integer linked = jdbc.queryForObject("SELECT COUNT(*) FROM us_apple_account WHERE member_id = ? AND realm = ?",
                Integer.class, memberId, realm);
        if (linked == null || linked == 0) return Outcome.error(401, SESSION_INVALID);
        UmsMember member = members.selectUmsMemberById(memberId);
        if (member == null) return Outcome.error(401, SESSION_INVALID);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("market", "US");
        data.put("realm", realm);
        data.put("account", profile(member));
        return Outcome.ok(data);
    }

    /** The verified Apple {@code sub}, or null when the token is not a valid identity for this challenge. */
    String verifiedSubject(String token, String nonceDigest) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) return null;
        String kid, alg;
        try {
            Map<?, ?> header = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(Base64.getUrlDecoder().decode(parts[0]), Map.class);
            kid = header.get("kid") instanceof String ? (String) header.get("kid") : null;
            alg = header.get("alg") instanceof String ? (String) header.get("alg") : null;
        } catch (Exception malformed) {
            return null;
        }
        if (kid == null || !"RS256".equals(alg)) return null;
        PublicKey key = keys.key(kid);
        if (key == null) return null;
        Claims claims;
        try {
            Jws<Claims> jws = Jwts.parser().setSigningKey(key).parseClaimsJws(token);
            claims = jws.getBody();
        } catch (Exception invalid) {
            return null;
        }
        if (!ISSUER.equals(claims.getIssuer()) || !clientId.equals(claims.getAudience())) return null;
        if (claims.getExpiration() == null) return null;
        Object nonce = claims.get("nonce");
        if (!(nonce instanceof String) || !MessageDigest.isEqual(((String) nonce).getBytes(StandardCharsets.US_ASCII),
                nonceDigest.getBytes(StandardCharsets.US_ASCII))) return null;
        String sub = claims.getSubject();
        return sub == null || sub.isEmpty() || sub.length() > 255 ? null : sub;
    }

    private Long linkedMember(String subject, long now) {
        List<Long> existing = jdbc.queryForList("SELECT member_id FROM us_apple_account WHERE realm = ? AND apple_sub = ?",
                Long.class, realm, subject);
        if (!existing.isEmpty()) return existing.get(0);
        UmsMember member = new UmsMember();
        member.setNickname("Questify Player");
        member.setName("Questify Player");
        member.setUserType(1L);
        member.setRole("player");
        member.setPhone("");
        member.setPassword("");
        member.setAvatar("");
        member.setWxOpenId("");
        member.setMark("US");
        member.setStatus(1L);
        member.setDelFlag(0);
        member.setPoint(0L);
        if (members.insertUmsMember(member) <= 0 || member.getId() == null) return null;
        try {
            jdbc.update("INSERT INTO us_apple_account (member_id, realm, apple_sub, created_at) VALUES (?, ?, ?, ?)",
                    member.getId(), realm, subject, now);
        } catch (DuplicateKeyException raced) {
            // A concurrent first sign-in won; this transaction rolls back its own member row with the exception.
            throw raced;
        }
        return member.getId();
    }

    private static Map<String, Object> profile(UmsMember member) {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", member.getId());
        account.put("userType", member.getUserType() == null ? 1L : member.getUserType());
        account.put("avatar", member.getAvatar() == null || member.getAvatar().isEmpty() ? null : member.getAvatar());
        account.put("nickname", member.getNickname() == null ? "" : member.getNickname());
        account.put("role", member.getRole() == null ? "player" : member.getRole());
        return account;
    }

    private String randomValue() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static boolean isRandomValue(String value) {
        if (value == null || value.length() != 43 || !value.matches("[A-Za-z0-9_-]{43}")) return false;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(value);
            return bytes.length == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    static boolean isToken(String value) {
        if (value == null || value.isEmpty() || value.length() > 16384) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x21 || c > 0x7e) return false;
        }
        return true;
    }

    static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** One wire outcome; {@code status} is both the HTTP status and the envelope {@code code}. */
    public static final class Outcome {
        public final int status;
        public final String errorCode;
        public final Map<String, Object> data;
        public Long memberId;

        private Outcome(int status, String errorCode, Map<String, Object> data) {
            this.status = status;
            this.errorCode = errorCode;
            this.data = data;
        }

        public static Outcome ok(Map<String, Object> data) { return new Outcome(200, null, data); }

        public static Outcome error(int status, String errorCode) { return new Outcome(status, errorCode, null); }
    }
}
