package com.chengyinhub.web.service.verify;

import com.chengyinhub.common.core.redis.RedisCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 报名单动态入场码「新码作废旧码」(2026-10-29 走查 V-02:有新的二维码旧的就不能用了)。
 *
 * <p>码本身仍是无状态 HMAC({@code VerifyDynCodeUtil});这里只按 报名单+码类型 记下最近一次签发的 nonce,
 * 新用途核销时 nonce 不是最新的就拒。码类型含席位号,不同席位的码互不作废。
 * 记录与码同 TTL;没有记录(从未经本服务签发或已过期)按原逻辑放行,只有「确实签过更新的码」才拒旧码。
 * Redis 不可用时保持原无状态行为,不把出码/核销整条链路拖垮。只读回执路径不经过这里。
 */
@Component
public class RegistrationDynCodeLedger {
    private static final Logger logger = LoggerFactory.getLogger(RegistrationDynCodeLedger.class);
    static final String KEY_PREFIX = "verify:dyncode:latest:";
    public static final String SUPERSEDED = "入场码已更新，请出示最新入场码";

    private final RedisCache redisCache;

    public RegistrationDynCodeLedger(RedisCache redisCache) {
        this.redisCache = redisCache;
    }

    /** 签发后登记为该报名单该类型的最新码。 */
    public void recordIssued(String code, long ttlMs) {
        String[] p = parts(code);
        if (p == null) return;
        try {
            redisCache.setCacheObject(key(p), p[4], (int) Math.max(1L, ttlMs), TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            logger.warn("动态入场码最新记录写入失败，沿用无状态校验", e);
        }
    }

    /** 已验签的码是否被同报名单同类型的更新码取代。 */
    public boolean superseded(String code) {
        String[] p = parts(code);
        if (p == null) return false;
        try {
            Object latest = redisCache.getCacheObject(key(p));
            return latest != null && !String.valueOf(latest).equals(p[4]);
        } catch (RuntimeException e) {
            logger.warn("动态入场码最新记录读取失败，沿用无状态校验", e);
            return false;
        }
    }

    private static String key(String[] p) {
        return KEY_PREFIX + p[1] + ":" + p[2];
    }

    private static String[] parts(String code) {
        if (code == null || !code.startsWith("v1.")) return null;
        String[] p = code.split("\\.");
        return p.length == 6 ? p : null;
    }
}
