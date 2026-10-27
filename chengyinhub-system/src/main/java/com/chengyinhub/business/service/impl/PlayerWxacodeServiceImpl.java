package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.service.IPlayerWxacodeService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 微信 getwxacodeunlimit 玩家个人码：固定落地页、签名 scene、Redis 单飞与永久复用。
 * E-4:缓存里存 OSS 对象 key,每次读取现签地址;永久缓存一条预签名 URL 会在 AccessKey 轮换或桶迁移后变成空白码图。
 */
@Service
public class PlayerWxacodeServiceImpl implements IPlayerWxacodeService {
    private static final Logger log = LoggerFactory.getLogger(PlayerWxacodeServiceImpl.class);
    private static final String ENDPOINT = "https://api.weixin.qq.com/wxa/getwxacodeunlimit";
    private static final String PAGE = "pages/index/index";
    private static final String CACHE_PREFIX = "wx:player-code:v2:";
    private static final String DOOR_CACHE_PREFIX = "wx:door-code:v2:";
    private static final String LOCK_SUFFIX = ":lock";
    private static final String TOKEN_REFRESH_LOCK = "wx:mini:access-token:refresh-lock";
    private static final int LOCK_SECONDS = 300;
    private static final String RATE_KEY = "wx:player-code:rate";
    private static final long RATE_LIMIT_PER_MINUTE = 4800L;
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>(
            "local now = tonumber(ARGV[1]); "
                    + "local cutoff = now - 60000; "
                    + "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', cutoff); "
                    + "local count = redis.call('ZCARD', KEYS[1]); "
                    + "if count >= tonumber(ARGV[3]) then "
                    + "redis.call('PEXPIRE', KEYS[1], 60000); return 0; end; "
                    + "redis.call('ZADD', KEYS[1], now, ARGV[2]); "
                    + "redis.call('PEXPIRE', KEYS[1], 60000); return 1;",
            Long.class);
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    @Autowired
    private IWxContentSecurityService tokenService;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private StringRedisTemplate rateRedis;

    @Value("${wx.mini.player-code-secret:${verify.dyncode-secret:${token.secret}}}")
    private String signingSecret;

    private final WxCodeGateway gateway;
    private final WxCodeStorage storage;

    public PlayerWxacodeServiceImpl() {
        this(new HttpWxCodeGateway(), new OssWxCodeStorage());
    }

    PlayerWxacodeServiceImpl(WxCodeGateway gateway, WxCodeStorage storage) {
        this.gateway = gateway;
        this.storage = storage;
    }

    @Override
    public String getOrCreate(Long memberId) {
        if (memberId == null || memberId <= 0) {
            throw new IllegalStateException("请先登录");
        }
        return getOrCreateCached(CACHE_PREFIX + memberId, sceneFor(memberId), "玩家个人码生成锁");
    }

    @Override
    public String getOrCreateScene(String scene) {
        if (StringUtils.isEmpty(scene) || scene.length() > 32
                || !scene.matches("[0-9A-Za-z!#$&'()*+,/:;=?@._~-]+")) {
            throw new IllegalStateException("门口码 scene 不合法");
        }
        return getOrCreateCached(DOOR_CACHE_PREFIX + scene, scene, "门口游戏码生成锁");
    }

    private String getOrCreateCached(String cacheKey, String scene, String lockLabel) {
        String cached = redisCache.getCacheObject(cacheKey);
        if (StringUtils.isNotEmpty(cached)) {
            return storage.url(cached);
        }

        String lockKey = cacheKey + LOCK_SUFFIX;
        String lockOwner = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redisCache.setIfAbsent(
                lockKey, lockOwner, LOCK_SECONDS, TimeUnit.SECONDS))) {
            String observed = redisCache.getCacheObject(cacheKey);
            if (StringUtils.isNotEmpty(observed)) {
                return storage.url(observed);
            }
            throw new IllegalStateException("个人码正在生成，请稍后重试");
        }

        try {
            String requestJson = requestJson(scene);
            String accessToken = tokenService.getAccessToken();
            if (StringUtils.isEmpty(accessToken)) {
                throw new IllegalStateException("个人码生成服务繁忙，请稍后重试");
            }

            WxCodeResponse response = generateWithinRateLimit(accessToken, requestJson);
            ImagePayload image;
            try {
                image = requireImage(response);
            } catch (WxCodeApiException e) {
                if (!isTokenError(e.errcode)) {
                    throw e;
                }
                String refreshed = refreshTokenSingleFlight();
                if (StringUtils.isEmpty(refreshed)) {
                    throw new IllegalStateException("个人码生成服务繁忙，请稍后重试");
                }
                image = requireImage(generateWithinRateLimit(refreshed, requestJson));
            }

            String objectKey = storage.store(image.bytes, image.extension);
            if (StringUtils.isEmpty(objectKey)) {
                throw new IllegalStateException("个人码存储失败，请稍后重试");
            }
            redisCache.setCacheObject(cacheKey, objectKey);
            return storage.url(objectKey);
        } finally {
            releaseOwnedLock(lockKey, lockOwner, lockLabel);
        }
    }

    private WxCodeResponse generateWithinRateLimit(String accessToken, String requestJson) {
        Long allowed;
        try {
            allowed = rateRedis.execute(
                    RATE_LIMIT_SCRIPT,
                    Collections.singletonList(RATE_KEY),
                    Long.toString(System.currentTimeMillis()),
                    UUID.randomUUID().toString(),
                    Long.toString(RATE_LIMIT_PER_MINUTE));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("玩家个人码频率控制异常，exceptionType={}", e.getClass().getSimpleName());
            throw new IllegalStateException("个人码生成频率控制不可用，请稍后重试");
        }
        if (!Long.valueOf(1L).equals(allowed)) {
            throw new IllegalStateException("个人码生成请求过于频繁，请稍后重试");
        }
        return gateway.generate(accessToken, requestJson);
    }

    private String refreshTokenSingleFlight() {
        String owner = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redisCache.setIfAbsent(
                TOKEN_REFRESH_LOCK, owner, LOCK_SECONDS, TimeUnit.SECONDS))) {
            throw new IllegalStateException("个人码生成服务繁忙，请稍后重试");
        }
        try {
            return tokenService.refreshAccessToken();
        } finally {
            releaseOwnedLock(TOKEN_REFRESH_LOCK, owner, "微信 access_token 刷新锁");
        }
    }

    private void releaseOwnedLock(String key, String owner, String label) {
        try {
            redisCache.compareAndDelete(key, owner);
        } catch (Exception e) {
            log.warn("{}释放失败，等待租约自动过期", label);
        }
    }

    String sceneFor(Long memberId) {
        if (StringUtils.isEmpty(signingSecret)) {
            throw new IllegalStateException("个人码签名配置缺失");
        }
        String unsigned = "p1." + Long.toString(memberId, 36);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 12);
            String scene = unsigned + "." + signature;
            if (scene.length() > 32 || !scene.matches("[0-9A-Za-z!#$&'()*+,/:;=?@._~-]+")) {
                throw new IllegalStateException("个人码 scene 超出微信约束");
            }
            return scene;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("个人码签名失败");
        }
    }

    private String requestJson(String scene) {
        JSONObject body = new JSONObject();
        body.put("scene", scene);
        body.put("page", PAGE);
        body.put("check_path", true);
        body.put("env_version", "release");
        body.put("width", 430);
        return body.toJSONString();
    }

    private ImagePayload requireImage(WxCodeResponse response) {
        if (response == null || response.body == null || response.body.length == 0) {
            throw new IllegalStateException("微信未返回个人码");
        }
        if (looksJson(response)) {
            Map<String, Object> error;
            try {
                error = JSON.parseObject(new String(response.body, StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new IllegalStateException("微信个人码响应无法识别");
            }
            int errcode = error == null || error.get("errcode") == null
                    ? -1 : Integer.parseInt(String.valueOf(error.get("errcode")));
            throw new WxCodeApiException(errcode);
        }
        if (isPng(response.body)) {
            return new ImagePayload(response.body, "png");
        }
        if (isJpeg(response.body)) {
            return new ImagePayload(response.body, "jpg");
        }
        throw new IllegalStateException("微信个人码响应不是受支持的图片");
    }

    private boolean looksJson(WxCodeResponse response) {
        if (response.contentType != null && response.contentType.toLowerCase().contains("json")) {
            return true;
        }
        for (byte b : response.body) {
            if (!Character.isWhitespace((char) (b & 0xff))) {
                return b == '{';
            }
        }
        return false;
    }

    private boolean isPng(byte[] bytes) {
        return bytes.length >= 8
                && (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47
                && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a;
    }

    private boolean isJpeg(byte[] bytes) {
        return bytes.length >= 3
                && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
    }

    private boolean isTokenError(int errcode) {
        return errcode == 40001 || errcode == 40014 || errcode == 42001;
    }

    interface WxCodeGateway {
        WxCodeResponse generate(String accessToken, String requestJson);
    }

    interface WxCodeStorage {
        /** 写入码图,返回对象 key(可长期缓存的身份,不是 bearer URL)。 */
        String store(byte[] bytes, String extension);

        /** 按对象 key 现签可展示地址。 */
        String url(String objectKey);
    }

    static final class WxCodeResponse {
        private final String contentType;
        private final byte[] body;

        WxCodeResponse(String contentType, byte[] body) {
            this.contentType = contentType;
            this.body = body;
        }
    }

    private static final class ImagePayload {
        private final byte[] bytes;
        private final String extension;

        private ImagePayload(byte[] bytes, String extension) {
            this.bytes = bytes;
            this.extension = extension;
        }
    }

    private static final class WxCodeApiException extends IllegalStateException {
        private final int errcode;

        private WxCodeApiException(int errcode) {
            super("微信小程序码签发失败(errcode=" + errcode + ")");
            this.errcode = errcode;
        }
    }

    private static final class HttpWxCodeGateway implements WxCodeGateway {
        @Override
        public WxCodeResponse generate(String accessToken, String requestJson) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(ENDPOINT + "?access_token=" + accessToken).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(10000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                byte[] requestBytes = requestJson.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(requestBytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(requestBytes);
                }
                int status = connection.getResponseCode();
                InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                return new WxCodeResponse(connection.getContentType(), readBounded(input));
            } catch (Exception e) {
                log.error("微信个人码请求失败，exceptionType={}", e.getClass().getSimpleName());
                throw new IllegalStateException("微信个人码请求失败", e);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        private byte[] readBounded(InputStream input) throws IOException {
            if (input == null) {
                return new byte[0];
            }
            try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_RESPONSE_BYTES) {
                        throw new IOException("微信个人码响应过大");
                    }
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            }
        }
    }

    private static final class OssWxCodeStorage implements WxCodeStorage {
        @Override
        public String store(byte[] bytes, String extension) {
            try {
                JSONObject uploaded = FileOssUploadUtils.upload(new ByteArrayMultipartFile(bytes, extension));
                return uploaded == null ? null : uploaded.getString("fileName");
            } catch (Exception e) {
                log.error("玩家个人码存储失败，exceptionType={}", e.getClass().getSimpleName());
                throw new IllegalStateException("个人码存储失败，请稍后重试", e);
            }
        }

        @Override
        public String url(String objectKey) {
            return FileOssUploadUtils.generatePresignedUrl(objectKey);
        }
    }

    private static final class ByteArrayMultipartFile implements MultipartFile {
        private final byte[] bytes;
        private final String extension;

        private ByteArrayMultipartFile(byte[] bytes, String extension) {
            this.bytes = bytes;
            this.extension = extension;
        }

        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return "player-code." + extension; }
        @Override public String getContentType() { return "png".equals(extension) ? "image/png" : "image/jpeg"; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File dest) throws IOException { Files.write(dest.toPath(), bytes); }
    }
}
