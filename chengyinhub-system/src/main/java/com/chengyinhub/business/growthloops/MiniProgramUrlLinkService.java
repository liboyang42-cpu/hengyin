package com.chengyinhub.business.growthloops;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.core.redis.RedisCache;

/**
 * GRO-05（App → 小程序）：服务端生成小程序 URL Link（微信 wxa/generate_urllink），给 App 还没有原生页面的小程序页面用。
 * App 拿到 https://wxaurl.cn/... 后交给系统打开，微信接管跳转。
 *
 * <p>只生成正式版（release）、30 天有效的链接；同一 (page, query) 在 Redis 缓存 7 天，避免每次点击都打微信接口。
 * 小程序 access_token 取自既有 {@link IWxContentSecurityService#getAccessToken()}（同一份 appid/secret 配置）；
 * 取不到时如实返回 available=false，不报 500。</p>
 */
@Service
public class MiniProgramUrlLinkService
{
    private static final Logger log = LoggerFactory.getLogger(MiniProgramUrlLinkService.class);
    static final String ENDPOINT = "https://api.weixin.qq.com/wxa/generate_urllink?access_token=";
    static final String CACHE_PREFIX = "growth:mp-urllink:v1:";
    static final Pattern PAGE = Pattern.compile("^(pages|subpackage[A-Za-z0-9]*)(/[A-Za-z0-9_-]+){1,5}$");
    static final Pattern QUERY = Pattern.compile("^[A-Za-z0-9_.~=&%-]{0,512}$");

    /** 发请求的那一下（测试替换）。返回微信的 JSON 文本。 */
    public interface Gateway { String post(String url, String json) throws Exception; }

    @Autowired(required = false) private IWxContentSecurityService tokens;
    @Autowired(required = false) private RedisCache redis;

    private final Gateway gateway;

    public MiniProgramUrlLinkService() { this(MiniProgramUrlLinkService::httpPost); }

    MiniProgramUrlLinkService(Gateway gateway) { this.gateway = gateway; }

    MiniProgramUrlLinkService(Gateway gateway, IWxContentSecurityService tokens, RedisCache redis)
    {
        this(gateway);
        this.tokens = tokens;
        this.redis = redis;
    }

    /** 校验页面路径（不带开头的 /）与查询串；不合法抛 IllegalArgumentException。 */
    public static String normalizePage(String page)
    {
        String p = page == null ? "" : page.trim();
        if (p.startsWith("/")) p = p.substring(1);
        if (p.length() > 128 || !PAGE.matcher(p).matches()) throw new IllegalArgumentException("page");
        return p;
    }

    public static String normalizeQuery(String query)
    {
        String q = query == null ? "" : query.trim();
        if (q.startsWith("?")) q = q.substring(1);
        if (!QUERY.matcher(q).matches()) throw new IllegalArgumentException("query");
        return q;
    }

    /** data: {available, url?, reason?} */
    public Map<String, Object> link(String page, String query)
    {
        String p = normalizePage(page);
        String q = normalizeQuery(query);
        Map<String, Object> out = new LinkedHashMap<>();
        String cacheKey = CACHE_PREFIX + p + "?" + q;
        String cached = cacheGet(cacheKey);
        if (cached != null) {
            out.put("available", true);
            out.put("url", cached);
            return out;
        }
        if (com.chengyinhub.common.config.RestoreDispatchGuard.isRestoreMode()) return unavailable(out, "RESTORE_MODE");
        String token = null;
        try { token = tokens == null ? null : tokens.getAccessToken(); }
        catch (RuntimeException failure) { log.warn("[urllink] access_token 不可用 reason={}", failure.getClass().getSimpleName()); }
        if (token == null || token.isBlank()) return unavailable(out, "WECHAT_NOT_CONFIGURED");
        JSONObject body = new JSONObject();
        body.put("path", p);
        body.put("query", q);
        body.put("env_version", "release");
        body.put("expire_type", 1);
        body.put("expire_interval", 30);
        try {
            JSONObject res = JSON.parseObject(gateway.post(ENDPOINT + token, body.toJSONString()));
            String url = res == null ? null : res.getString("url_link");
            if (res == null || res.getIntValue("errcode") != 0 || url == null || !url.startsWith("https://")) {
                log.warn("[urllink] 微信拒绝 page={} errcode={}", p, res == null ? null : res.get("errcode"));
                return unavailable(out, "WECHAT_REFUSED");
            }
            cachePut(cacheKey, url);
            out.put("available", true);
            out.put("url", url);
            return out;
        } catch (Exception failure) {
            log.warn("[urllink] 请求失败 page={} reason={}", p, failure.getClass().getSimpleName());
            return unavailable(out, "WECHAT_UNREACHABLE");
        }
    }

    private static Map<String, Object> unavailable(Map<String, Object> out, String reason)
    {
        out.put("available", false);
        out.put("reason", reason);
        return out;
    }

    private String cacheGet(String key)
    {
        try { return redis == null ? null : redis.getCacheObject(key); }
        catch (RuntimeException down) { return null; }
    }

    private void cachePut(String key, String url)
    {
        try { if (redis != null) redis.setCacheObject(key, url, 7, TimeUnit.DAYS); }
        catch (RuntimeException down) { /* 只是缓存 */ }
    }

    private static String httpPost(String url, String json) throws Exception
    {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
    }
}
