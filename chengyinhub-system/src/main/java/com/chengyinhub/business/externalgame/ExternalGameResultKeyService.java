package com.chengyinhub.business.externalgame;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.appgaps.AppGapException;

/**
 * W7 GAME-9: per-game result keys that a creator provisions for their own game server.
 *
 * <ul>
 *   <li>{@link #issue}: the game's creator, approved games only. Returns a fresh random 32-byte key (hex) <b>once</b>;
 *       issuing again rotates it. Only an AES-GCM ciphertext under {@code CHENGYIN_EXTERNAL_GAME_KEY_WRAP} (environment)
 *       and an 8-character hint are stored. Without the wrap secret nothing is issued (409, honest state).</li>
 *   <li>{@link #activeKey}: the plain key for the verifier, or null when none, revoked or not decryptable.</li>
 *   <li>{@link #revoke}: operators (console permission {@code business:externalgame:resultkey}).</li>
 * </ul>
 * The table holds no personal data: the owner is read from {@code external_game}.
 */
@Service
public class ExternalGameResultKeyService {

    static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final JdbcTemplate jdbc;
    private final byte[] wrapKey;
    private final SecureRandom random = new SecureRandom();
    private LongSupplier clock = System::currentTimeMillis;

    @Autowired
    public ExternalGameResultKeyService(JdbcTemplate jdbc,
            @Value("${chengyin.external-game.key-wrap:${CHENGYIN_EXTERNAL_GAME_KEY_WRAP:}}") String wrapSecret) {
        this.jdbc = jdbc;
        this.wrapKey = wrapSecret == null || wrapSecret.isBlank() ? null : sha256(wrapSecret.trim());
    }

    /** For tests. */
    ExternalGameResultKeyService withClock(LongSupplier clock) {
        this.clock = clock;
        return this;
    }

    public boolean configured() {
        return wrapKey != null;
    }

    /** Creator self-serve: a fresh key, shown once. Issuing again replaces (rotates) the stored key. */
    public Map<String, Object> issue(long creatorId, String gameId) {
        if (creatorId <= 0) throw AppGapException.unauthorized();
        String gId = gameId == null ? "" : gameId.trim();
        if (gId.isEmpty() || gId.length() > 64) throw AppGapException.badRequest("gameId");
        List<Map<String, Object>> games = jdbc.queryForList(
                "SELECT owner_member_id, approved_submission_id FROM external_game WHERE game_id=?", gId);
        if (games.isEmpty()) throw AppGapException.notFound("GAME_NOT_FOUND");
        Object owner = games.get(0).get("owner_member_id");
        if (!(owner instanceof Number n) || n.longValue() != creatorId) {
            throw new AppGapException(403, "NOT_GAME_OWNER", "只有游戏的创作者可以领取成绩密钥");
        }
        if (games.get(0).get("approved_submission_id") == null) {
            throw AppGapException.conflict("GAME_NOT_APPROVED", "游戏通过审核后才能领取成绩密钥");
        }
        if (wrapKey == null) {
            throw AppGapException.conflict("RESULT_KEY_NOT_CONFIGURED", "平台还没有开通成绩密钥，请稍后再试");
        }
        byte[] raw = new byte[KEY_BYTES];
        random.nextBytes(raw);
        String key = HexFormat.of().formatHex(raw);
        byte[] cipher = encrypt(key.getBytes(StandardCharsets.UTF_8));
        String hint = hint(key);
        Timestamp now = new Timestamp(clock.getAsLong());
        int n2 = jdbc.update("UPDATE external_game_result_key SET key_cipher=?, key_hint=?, created_at=?, revoked_at=NULL,"
                + " revoked_by=NULL WHERE game_id=?", cipher, hint, now, gId);
        if (n2 == 0) {
            jdbc.update("INSERT INTO external_game_result_key (game_id, key_cipher, key_hint, created_at) VALUES (?, ?, ?, ?)",
                    gId, cipher, hint, now);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gameId", gId);
        out.put("resultKey", key);
        out.put("keyHint", hint);
        out.put("algorithm", "HMAC-SHA256");
        out.put("shownOnce", true);
        return out;
    }

    /** Status without the key: whether one is active, its hint and when it was made. */
    public Map<String, Object> status(String gameId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gameId", gameId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT key_hint, created_at, revoked_at FROM external_game_result_key WHERE game_id=?", gameId);
        boolean active = !rows.isEmpty() && rows.get(0).get("revoked_at") == null;
        out.put("active", active);
        out.put("keyHint", rows.isEmpty() ? null : rows.get(0).get("key_hint"));
        out.put("createdAt", rows.isEmpty() ? null : rows.get(0).get("created_at"));
        out.put("revokedAt", rows.isEmpty() ? null : rows.get(0).get("revoked_at"));
        out.put("configured", configured());
        return out;
    }

    /** Operators: the stored key stops verifying at once. The creator can issue a new one. */
    public boolean revoke(String gameId, String operator) {
        if (operator == null || operator.isBlank()) throw new IllegalArgumentException("operator is required");
        return jdbc.update("UPDATE external_game_result_key SET revoked_at=?, revoked_by=? WHERE game_id=? AND revoked_at IS NULL",
                new Timestamp(clock.getAsLong()), operator, gameId) == 1;
    }

    /** The active plain key for verification, or null. Never thrown: a problem means "no stored key". */
    public String activeKey(String gameId) {
        if (wrapKey == null || gameId == null) return null;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT key_cipher FROM external_game_result_key WHERE game_id=? AND revoked_at IS NULL", gameId);
            if (rows.isEmpty() || !(rows.get(0).get("key_cipher") instanceof byte[] cipher)) return null;
            return new String(decrypt(cipher), StandardCharsets.UTF_8);
        } catch (RuntimeException | GeneralSecurityException e) {
            return null;
        }
    }

    private byte[] encrypt(byte[] plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(wrapKey, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] body = c.doFinal(plain);
            byte[] out = new byte[IV_BYTES + body.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(body, 0, out, IV_BYTES, body.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("result key encryption failed", e);
        }
    }

    private byte[] decrypt(byte[] stored) throws GeneralSecurityException {
        if (stored.length <= IV_BYTES) throw new GeneralSecurityException("short");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(wrapKey, "AES"), new GCMParameterSpec(TAG_BITS, stored, 0, IV_BYTES));
        return c.doFinal(stored, IV_BYTES, stored.length - IV_BYTES);
    }

    static String hint(String key) {
        return HexFormat.of().formatHex(sha256(key)).substring(0, 8);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
