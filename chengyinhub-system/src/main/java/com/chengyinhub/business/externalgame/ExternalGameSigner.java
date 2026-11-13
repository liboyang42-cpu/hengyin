package com.chengyinhub.business.externalgame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-issued package signatures. The signing key is a per-environment secret read from configuration
 * ({@code chengyin.external-game.signing-private-key}, or env {@code CHENGYIN_EXTERNAL_GAME_SIGNING_KEY}): base64 of a
 * 32-byte Ed25519 seed or of a PKCS#8 Ed25519 key. It is never shipped in the app; the app only needs the public key
 * ({@code GET /api/external-game/signing-key}) to verify. Missing or malformed key = not configured: submission and
 * download fail closed with 503, the rest of the backend starts normally.
 *
 * <p>Ed25519 replaces the app's HMAC with a hard-coded shared key: with a symmetric key anyone holding the app could mint
 * a "valid" signature. The same key also signs expiring download links.
 */
@Component
public class ExternalGameSigner {
    public static final String ALGORITHM = "Ed25519";
    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String keyId;

    @Autowired
    public ExternalGameSigner(@Value("${chengyin.external-game.signing-private-key:${CHENGYIN_EXTERNAL_GAME_SIGNING_KEY:}}") String encoded,
                              @Value("${chengyin.external-game.signing-key-id:ed25519-1}") String keyId) {
        KeyPair pair = load(encoded);
        this.privateKey = pair == null ? null : pair.getPrivate();
        this.publicKey = pair == null ? null : pair.getPublic();
        this.keyId = keyId == null || !keyId.matches("[A-Za-z0-9._-]{1,32}") ? "ed25519-1" : keyId;
    }

    public boolean configured() { return privateKey != null; }

    public String keyId() { return keyId; }

    /** Hex Ed25519 signature (128 chars) over the bytes. */
    public String signHex(byte[] payload) { return HexFormat.of().formatHex(sign(payload)); }

    public boolean verifyHex(byte[] payload, String hex) {
        if (hex == null || !hex.matches("[0-9a-f]{128}")) return false;
        return verify(payload, HexFormat.of().parseHex(hex));
    }

    /** URL-safe token for an expiring download link. */
    public String linkToken(String payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload.getBytes(StandardCharsets.UTF_8)));
    }

    public boolean verifyLinkToken(String payload, String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{86}")) return false;
        try {
            return verify(payload.getBytes(StandardCharsets.UTF_8), Base64.getUrlDecoder().decode(token));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public Map<String, Object> publicKeyView() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keyId", keyId);
        out.put("algorithm", ALGORITHM);
        out.put("configured", configured());
        if (publicKey != null) {
            byte[] spki = publicKey.getEncoded();
            out.put("publicKeySpki", Base64.getEncoder().encodeToString(spki));
            out.put("publicKeyRaw", Base64.getEncoder().encodeToString(Arrays.copyOfRange(spki, spki.length - 32, spki.length)));
        }
        out.put("signedPayload", "v1|id|name|version|entry|permissions(,)|contentRating|allowedDomains(lowercase,)|sha256");
        return out;
    }

    private byte[] sign(byte[] payload) {
        if (privateKey == null) throw new IllegalStateException("external game signing key not configured");
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initSign(privateKey);
            s.update(payload);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("external game signing failed", e);
        }
    }

    private boolean verify(byte[] payload, byte[] signature) {
        if (publicKey == null) return false;
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initVerify(publicKey);
            s.update(payload);
            return s.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /** Seed (32 bytes) or PKCS#8 → key pair; the public key is re-derived from the seed. Null when absent or invalid. */
    static KeyPair load(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            byte[] raw = Base64.getDecoder().decode(encoded.strip());
            byte[] seed;
            if (raw.length == 32) {
                seed = raw;
            } else {
                PrivateKey parsed = KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(raw));
                seed = ((EdECPrivateKey) parsed).getBytes().orElse(null);
                if (seed == null || seed.length != 32) return null;
            }
            if (allSame(seed)) return null;
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
            generator.initialize(NamedParameterSpec.ED25519, new FixedSeed(seed));
            KeyPair pair = generator.generateKeyPair();
            byte[] derived = ((EdECPrivateKey) pair.getPrivate()).getBytes().orElse(new byte[0]);
            return Arrays.equals(derived, seed) ? pair : null;
        } catch (IllegalArgumentException | GeneralSecurityException | ClassCastException e) {
            return null;
        }
    }

    private static boolean allSame(byte[] b) {
        for (byte x : b) if (x != b[0]) return false;
        return true;
    }

    /** Feeds the configured seed to the JDK generator, which derives the matching public key. */
    private static final class FixedSeed extends SecureRandom {
        private final byte[] seed;
        FixedSeed(byte[] seed) { this.seed = seed.clone(); }
        @Override public void nextBytes(byte[] bytes) { System.arraycopy(seed, 0, bytes, 0, Math.min(seed.length, bytes.length)); }
    }
}
