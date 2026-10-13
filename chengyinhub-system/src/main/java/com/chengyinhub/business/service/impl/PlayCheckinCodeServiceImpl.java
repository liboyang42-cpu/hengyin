package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.GameSessionRuntimeMapper;
import com.chengyinhub.business.service.IChapterMerchantNodeService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IPlayCheckinCodeService;
import com.chengyinhub.business.service.IQrcodeService;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** 游戏打卡一次性码：签名保真 + Redis 消费一次 + 短 TTL。 */
@Service
public class PlayCheckinCodeServiceImpl implements IPlayCheckinCodeService {

    private static final Logger logger = LoggerFactory.getLogger(PlayCheckinCodeServiceImpl.class);

    public static final String TYPE = "play_checkin";
    public static final long TTL_MS = 60_000L;
    private static final int TTL_SECONDS = 60;
    private static final String LIVE_KEY_PREFIX = "play:checkin:live:";

    private final IChapterMerchantNodeService chapterNodes;
    private final ICmsTopicNodeService nodes;
    private final GameSessionRuntimeMapper gameSessions;
    private final RedisCache redis;
    private final IQrcodeService qrcodeService;
    private final String dynSecret;

    public PlayCheckinCodeServiceImpl(IChapterMerchantNodeService chapterNodes,
                                      ICmsTopicNodeService nodes,
                                      GameSessionRuntimeMapper gameSessions,
                                      RedisCache redis,
                                      IQrcodeService qrcodeService,
                                      @Value("${verify.dyncode-secret:${token.secret}}") String dynSecret) {
        this.chapterNodes = chapterNodes;
        this.nodes = nodes;
        this.gameSessions = gameSessions;
        this.redis = redis;
        this.qrcodeService = qrcodeService;
        this.dynSecret = dynSecret;
    }

    @Override
    public Issued issue(Long merchantMemberId, Long nodeId) {
        CmsTopicNode node = resolveIssuableNode(merchantMemberId, nodeId);
        String code = VerifyDynCodeUtil.issue(node.getId(), TYPE, System.currentTimeMillis(),
                TTL_MS, ThreadLocalRandom.current().nextLong(), dynSecret);
        redis.setCacheObject(LIVE_KEY_PREFIX + node.getId(), code, TTL_SECONDS, TimeUnit.SECONDS);
        String qrcodeUrl = null;
        try {
            qrcodeUrl = qrcodeService.generateQrcode(code, 300, 300);
        } catch (Exception ignore) {
            logger.error("签发打卡码二维码生成失败", ignore);
        }
        return new Issued(node.getId(), node.getName(), TTL_MS, qrcodeUrl, code);
    }

    @Override
    public ConsumeResult inspect(String code) {
        VerifyDynCodeUtil.Result parsed = VerifyDynCodeUtil.verify(code, System.currentTimeMillis(), dynSecret);
        if (!parsed.valid) {
            if ("已过期".equals(parsed.reason)) {
                return ConsumeResult.expired();
            }
            return ConsumeResult.invalid();
        }
        if (!TYPE.equals(parsed.type) || parsed.regId == null) {
            return ConsumeResult.invalid();
        }
        if (!code.equals(redis.getCacheObject(LIVE_KEY_PREFIX + parsed.regId))) {
            return ConsumeResult.used();
        }
        return ConsumeResult.ok(parsed.regId);
    }

    @Override
    public ConsumeResult consume(String code) {
        ConsumeResult inspected = inspect(code);
        if (inspected.kind != ConsumeResult.Kind.OK) {
            return inspected;
        }
        String key = LIVE_KEY_PREFIX + inspected.nodeId;
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return redis.compareAndDelete(key, code) ? inspected : ConsumeResult.used();
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("打卡事务同步未开启");
        }
        // 占用到事务收尾；回滚仅恢复本次占用，不能覆盖商家刚刷出的新码。
        String reserved = code + ":consuming";
        int remainingMs = remainingTtlMs(code);
        if (remainingMs <= 0) return ConsumeResult.expired();
        if (!redis.compareAndSet(key, code, reserved, remainingMs, TimeUnit.MILLISECONDS)) {
            return ConsumeResult.used();
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                int ttl = remainingTtlMs(code);
                if (status == STATUS_ROLLED_BACK && ttl > 0) {
                    redis.compareAndSet(key, reserved, code, ttl, TimeUnit.MILLISECONDS);
                } else {
                    redis.compareAndDelete(key, reserved);
                }
            }
        });
        return inspected;
    }

    /** 仅用于已通过签名与格式校验的码，恢复时保持原始到期时刻。 */
    private int remainingTtlMs(String code) {
        long expiresAt = Long.parseLong(code.split("\\.")[3]);
        return (int) Math.max(0L, Math.min(TTL_MS, expiresAt - System.currentTimeMillis()));
    }

    private CmsTopicNode resolveIssuableNode(Long merchantMemberId, Long nodeId) {
        try {
            return chapterNodes.posterCodeNode(merchantMemberId, nodeId);
        } catch (RuntimeException posterDenied) {
            if (gameSessions.countMerchantOperableStation(merchantMemberId, nodeId) > 0) {
                CmsTopicNode node = nodes.selectCmsTopicNodeById(nodeId);
                if (node != null) {
                    return node;
                }
            }
            throw posterDenied;
        }
    }
}
