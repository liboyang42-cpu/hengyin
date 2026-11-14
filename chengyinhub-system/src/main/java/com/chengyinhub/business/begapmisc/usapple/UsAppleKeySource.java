package com.chengyinhub.business.begapmisc.usapple;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Apple's identity-token signing keys (JWKS, {@code chengyin.us.apple.jwks-url}, default Apple's). Keys are cached for an
 * hour; an unknown {@code kid} forces one refresh (Apple rotates keys). A JWKS that cannot be fetched is
 * {@link Unavailable} (503), never an identity failure.
 */
@Component
public class UsAppleKeySource {
    static final long CACHE_MS = 3_600_000L;
    private final String url;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private volatile Map<String, PublicKey> cached = Map.of();
    private volatile long fetchedAt;

    public UsAppleKeySource(@Value("${chengyin.us.apple.jwks-url:https://appleid.apple.com/auth/keys}") String url) {
        this.url = url;
    }

    /** The RSA key for {@code kid}, or null when Apple does not publish it. */
    public PublicKey key(String kid) {
        long now = System.currentTimeMillis();
        PublicKey key = now - fetchedAt < CACHE_MS ? cached.get(kid) : null;
        if (key != null) return key;
        refresh();
        return cached.get(kid);
    }

    protected String fetch() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || response.body().length() > 65536) throw new IllegalStateException("jwks");
        return response.body();
    }

    private synchronized void refresh() {
        try {
            Map<String, PublicKey> keys = parse(fetch());
            cached = keys;
            fetchedAt = System.currentTimeMillis();
        } catch (Exception failed) {
            throw new Unavailable();
        }
    }

    static Map<String, PublicKey> parse(String json) throws Exception {
        Map<String, PublicKey> keys = new ConcurrentHashMap<>();
        for (JsonNode jwk : new ObjectMapper().readTree(json).path("keys")) {
            if (!"RSA".equals(jwk.path("kty").asText()) || !jwk.hasNonNull("kid")) continue;
            BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("n").asText()));
            BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("e").asText()));
            keys.put(jwk.path("kid").asText(), KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e)));
        }
        return keys;
    }

    /** The JWKS could not be read; the exchange answers 503 US_APPLE_UNAVAILABLE. */
    public static final class Unavailable extends RuntimeException {
        public Unavailable() { super("US_APPLE_JWKS_UNAVAILABLE", null, false, false); }
    }
}
