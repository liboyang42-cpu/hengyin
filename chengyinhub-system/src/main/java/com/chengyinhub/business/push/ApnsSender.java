package com.chengyinhub.business.push;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.chengyinhub.business.mapper.push.PushMapper;

/**
 * APNs 发送端（token 鉴权 + HTTP/2）。配置全部来自环境变量（经 Spring 属性）：
 * APNS_KEY_ID、APNS_TEAM_ID、APNS_PRIVATE_KEY（.p8 PEM 文本）、APNS_TOPIC（bundle id）、APNS_ENV（sandbox|production）。
 * <ul>
 * <li>未配置：不发、只 WARN 一次，{@link #configured()} = false。</li>
 * <li>只发给 environment 与 APNS_ENV 一致的设备。</li>
 * <li>410，或 400 且 reason 为 BadDeviceToken / DeviceTokenNotForTopic / Unregistered：停用该 token。</li>
 * <li>JWT 缓存 50 分钟后重签（Apple 要求 20–60 分钟之间刷新）；403 ExpiredProviderToken/InvalidProviderToken 时作废缓存。</li>
 * <li>日志不写完整 token、不写密钥。</li>
 * </ul>
 * 本类同步发送；异步由 {@link PushFanoutService} 放到线程池里。
 */
@Component
public class ApnsSender
{
    private static final Logger log = LoggerFactory.getLogger(ApnsSender.class);
    static final long JWT_REFRESH_SECONDS = 50L * 60L;
    static final String SANDBOX_HOST = "api.sandbox.push.apple.com";
    static final String PRODUCTION_HOST = "api.push.apple.com";
    private static final Set<String> DEAD_TOKEN_REASONS = Set.of("BadDeviceToken", "DeviceTokenNotForTopic", "Unregistered");

    private final String keyId;
    private final String teamId;
    private final String topic;
    private final String environment;
    private final PrivateKey privateKey;
    private final ApnsTransport transport;
    private final PushMapper mapper;
    private final LongSupplier clockSeconds;
    private final AtomicBoolean warned = new AtomicBoolean(false);

    private String cachedJwt;
    private long cachedJwtIssuedAt;

    @Autowired
    public ApnsSender(@Value("${apns.key-id:${APNS_KEY_ID:}}") String keyId,
                      @Value("${apns.team-id:${APNS_TEAM_ID:}}") String teamId,
                      @Value("${apns.private-key:${APNS_PRIVATE_KEY:}}") String privateKeyPem,
                      @Value("${apns.topic:${APNS_TOPIC:}}") String topic,
                      @Value("${apns.env:${APNS_ENV:}}") String env,
                      PushMapper mapper)
    {
        this(keyId, teamId, privateKeyPem, topic, env, mapper, new JdkApnsTransport(), () -> System.currentTimeMillis() / 1000L);
    }

    public ApnsSender(String keyId, String teamId, String privateKeyPem, String topic, String env, PushMapper mapper,
                      ApnsTransport transport, LongSupplier clockSeconds)
    {
        this.keyId = trim(keyId);
        this.teamId = trim(teamId);
        this.topic = trim(topic);
        this.environment = environmentOf(env);
        this.mapper = mapper;
        this.transport = transport;
        this.clockSeconds = clockSeconds;
        PrivateKey parsed = null;
        if (this.keyId != null && this.teamId != null && this.topic != null && this.environment != null && privateKeyPem != null && !privateKeyPem.isBlank())
        {
            try { parsed = ApnsJwt.parsePrivateKey(privateKeyPem); }
            catch (IllegalArgumentException invalid) { log.warn("[APNs] APNS_PRIVATE_KEY 无法解析为 PKCS#8 EC 私钥，推送保持关闭"); }
        }
        this.privateKey = parsed;
    }

    /** 五项配置齐全且私钥可解析。 */
    public boolean configured()
    {
        boolean ok = privateKey != null;
        if (!ok && warned.compareAndSet(false, true))
            log.warn("[APNs] 未配置（APNS_KEY_ID / APNS_TEAM_ID / APNS_PRIVATE_KEY / APNS_TOPIC / APNS_ENV），远程推送不发送");
        return ok;
    }

    /** SANDBOX / PRODUCTION；未配置时为 null。 */
    public String environment()
    {
        return environment;
    }

    String host()
    {
        return "PRODUCTION".equals(environment) ? PRODUCTION_HOST : SANDBOX_HOST;
    }

    /**
     * 同步发送到这些设备（调用方已在异步线程里）。
     *
     * @return 被 APNs 接受（200）的设备数
     */
    public int send(List<PushDevice> devices, String payloadJson, String collapseId, long expirationEpochSeconds)
    {
        if (!configured() || devices == null || devices.isEmpty()) return 0;
        byte[] body = payloadJson.getBytes(StandardCharsets.UTF_8);
        int accepted = 0;
        for (PushDevice device : devices)
        {
            if (device == null || device.getToken() == null || !environment.equals(device.getEnvironment())) continue;
            try
            {
                ApnsTransport.ApnsResponse response = transport.post(host(), "/3/device/" + device.getToken(),
                        headers(collapseId, expirationEpochSeconds), body);
                if (response.status() == 200) { accepted++; continue; }
                handleFailure(device, response);
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
                return accepted;
            }
            catch (Exception failure)
            {
                log.warn("[APNs] 发送异常 token={} reason={}", mask(device.getToken()), failure.getClass().getSimpleName());
            }
        }
        return accepted;
    }

    private void handleFailure(PushDevice device, ApnsTransport.ApnsResponse response)
    {
        String reason = response.reason();
        if (response.status() == 410 || (response.status() == 400 && reason != null && DEAD_TOKEN_REASONS.contains(reason)))
        {
            mapper.disableToken(device.getToken(), device.getEnvironment());
            log.info("[APNs] token 已失效并停用 token={} status={} reason={}", mask(device.getToken()), response.status(), reason);
            return;
        }
        if (response.status() == 403 && ("ExpiredProviderToken".equals(reason) || "InvalidProviderToken".equals(reason)))
        {
            synchronized (this) { cachedJwt = null; }
        }
        log.warn("[APNs] 推送被拒 token={} status={} reason={}", mask(device.getToken()), response.status(), reason);
    }

    Map<String, String> headers(String collapseId, long expirationEpochSeconds)
    {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("authorization", "bearer " + jwt());
        headers.put("apns-topic", topic);
        headers.put("apns-push-type", "alert");
        headers.put("apns-priority", "10");
        headers.put("apns-expiration", String.valueOf(expirationEpochSeconds));
        if (collapseId != null && !collapseId.isEmpty() && collapseId.getBytes(StandardCharsets.UTF_8).length <= 64)
            headers.put("apns-collapse-id", collapseId);
        return headers;
    }

    synchronized String jwt()
    {
        long now = clockSeconds.getAsLong();
        if (cachedJwt == null || now - cachedJwtIssuedAt >= JWT_REFRESH_SECONDS)
        {
            cachedJwt = ApnsJwt.sign(keyId, teamId, now, privateKey);
            cachedJwtIssuedAt = now;
        }
        return cachedJwt;
    }

    public static String mask(String token)
    {
        if (token == null) return "null";
        return token.length() <= 8 ? "****" : token.substring(0, 6) + "…" + token.length();
    }

    static String environmentOf(String env)
    {
        String v = trim(env);
        if (v == null) return null;
        v = v.toLowerCase(Locale.ROOT);
        if ("sandbox".equals(v) || "development".equals(v)) return "SANDBOX";
        if ("production".equals(v)) return "PRODUCTION";
        return null;
    }

    private static String trim(String value)
    {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
