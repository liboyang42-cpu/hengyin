package com.chengyinhub.framework.web.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.system.service.ISysConfigService;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

/** A fresh WeChat code confirms the already authenticated owner; it never signs a session. */
@Service
public class WechatReconfirmationService {
    private final RedisCache redis;
    private final ISysConfigService config;
    private final Environment environment;

    public WechatReconfirmationService(RedisCache redis, ISysConfigService config, Environment environment) {
        this.redis = redis;
        this.config = config;
        this.environment = environment;
    }

    public boolean verify(Long memberId, String expectedOpenId, String freshCode, String ip) {
        if (memberId == null || memberId <= 0 || expectedOpenId == null || expectedOpenId.isBlank()
                || freshCode == null || freshCode.isBlank() || freshCode.length() > 512) return false;
        try {
            Long ipCount = redis.incrementWithinWindow("deregister_wx_ip_" + digest(ip == null ? "unknown" : ip), 1, TimeUnit.MINUTES);
            Long ownerCount = redis.incrementWithinWindow("deregister_wx_owner_" + memberId, 1, TimeUnit.MINUTES);
            if (ipCount == null || ipCount > 10 || ownerCount == null || ownerCount > 10) return false;
            // Reserve globally before exchanging. Failed exchanges also consume the code; retry needs a fresh wx.login.
            if (!Boolean.TRUE.equals(redis.setIfAbsent("deregister_wx_code_" + digest(freshCode),
                    memberId.toString(), 5, TimeUnit.MINUTES))) return false;
            String actual = exchangeOpenId(freshCode);
            return actual != null && MessageDigest.isEqual(expectedOpenId.getBytes(StandardCharsets.UTF_8),
                    actual.getBytes(StandardCharsets.UTF_8));
        } catch (Exception unavailable) {
            // Neither the secret-bearing request URL nor response/openid may enter logs.
            return false;
        }
    }

    String sessionBaseUrl() {
        if (environment.matchesProfiles("prod", "production")
                || !environment.matchesProfiles("test", "dev", "local", "preview")) return "https://api.weixin.qq.com";
        String base = environment.getProperty("chengyin.test.wechat-api-base-url", "https://api.weixin.qq.com").trim();
        URI uri = URI.create(base);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) throw new IllegalArgumentException("微信API基址配置不合法");
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    protected String exchangeOpenId(String code) throws Exception {
        String appid = config.selectConfigByKey("app.wechat.appid");
        String secret = config.selectConfigByKey("app.wechat.appsecret");
        if (appid == null || appid.isBlank() || secret == null || secret.isBlank()) return null;
        HttpURLConnection connection = (HttpURLConnection) URI.create(sessionBaseUrl() + "/sns/jscode2session?appid="
                + encode(appid) + "&secret=" + encode(secret) + "&grant_type=authorization_code&js_code=" + encode(code)).toURL().openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) return null;
            try (InputStream input = connection.getInputStream()) {
                byte[] bytes = input.readNBytes(16385);
                if (bytes.length > 16384) return null;
                JSONObject response = JSON.parseObject(new String(bytes, StandardCharsets.UTF_8));
                if (response == null || response.getIntValue("errcode") != 0) return null;
                String openid = response.getString("openid");
                return openid == null || openid.isBlank() ? null : openid;
            }
        } finally { connection.disconnect(); }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String digest(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
