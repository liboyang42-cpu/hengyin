package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.GameRandomAudit;
import com.chengyinhub.business.mapper.GameRandomAuditMapper;
import com.chengyinhub.business.service.IGameRandomService;
import com.chengyinhub.business.service.support.AdvancedGameRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.ToIntFunction;

/**
 * 统一、可设种子、可审计随机核心（P3）。
 *
 * <p>默认使用 {@link SecureRandom}；测试可通过 {@link #GameRandom(long)} 或
 * {@link #GameRandom(long, GameRandomAuditMapper)} 注入固定种子，确保可重放与无偏采样。
 * 每次采样向 {@code game_random_audit} 写入 {domain, receiptId, seedDigest, ticket, total, outcomeJson}。
 */
@Service("gameRandom")
public class GameRandom implements IGameRandomService, AdvancedGameRandom {

    private final IntUnaryOperator nextIntFn;
    private final String seedDigest;

    @Autowired(required = false)
    private GameRandomAuditMapper auditMapper;

    public GameRandom() {
        SecureRandom sr = new SecureRandom();
        this.nextIntFn = sr::nextInt;
        this.seedDigest = "SECURE_RANDOM";
    }

    public GameRandom(long seed) {
        this(seed, null);
    }

    public GameRandom(long seed, GameRandomAuditMapper auditMapper) {
        Random seeded = new Random(seed);
        this.nextIntFn = seeded::nextInt;
        this.seedDigest = sha256Hex("seed:" + seed);
        this.auditMapper = auditMapper;
    }

    public GameRandom(IntUnaryOperator nextIntFn, String seedDigest, GameRandomAuditMapper auditMapper) {
        if (nextIntFn == null) {
            throw new IllegalArgumentException("nextIntFn must not be null");
        }
        this.nextIntFn = nextIntFn;
        this.seedDigest = (seedDigest == null || seedDigest.isBlank()) ? "CUSTOM" : seedDigest;
        this.auditMapper = auditMapper;
    }

    @Override
    public int nextInt(int bound) {
        return nextIntFn.applyAsInt(bound);
    }

    @Override
    public DrawResult draw(String domain, int total, String receiptId,
                           Long memberId, String refType, String refId,
                           IntFunction<String> outcomeFormatter) {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("domain must not be blank");
        }
        if (total <= 0) {
            throw new IllegalArgumentException("total must be > 0, got " + total);
        }
        String normalizedDomain = truncate(domain.trim(), 48);
        boolean callerProvidedReceipt = receiptId != null && !receiptId.isBlank();
        String effectiveReceiptId = callerProvidedReceipt
                ? truncate(receiptId.trim(), 64)
                : UUID.randomUUID().toString().replace("-", "");

        if (auditMapper != null && callerProvidedReceipt) {
            GameRandomAudit existing = auditMapper.selectByDomainAndReceipt(normalizedDomain, effectiveReceiptId);
            if (existing != null && existing.getTicket() != null) {
                int existingTotal = existing.getTotal() == null ? total : existing.getTotal();
                int boundedTicket = Math.floorMod(existing.getTicket(), total);
                return new DrawResult(boundedTicket, existingTotal, effectiveReceiptId,
                        existing.getSeedDigest(), true);
            }
        }

        int raw = nextIntFn.applyAsInt(total);
        int ticket = Math.floorMod(raw, total);
        String outcomeJson = outcomeFormatter == null
                ? JSON.toJSONString(Map.of("ticket", ticket, "total", total))
                : outcomeFormatter.apply(ticket);

        if (auditMapper != null) {
            GameRandomAudit audit = new GameRandomAudit();
            audit.setDomain(normalizedDomain);
            audit.setReceiptId(effectiveReceiptId);
            audit.setSeedDigest(truncate(seedDigest, 64));
            audit.setTicket(ticket);
            audit.setTotal(total);
            audit.setMemberId(memberId);
            audit.setRefType(truncate(refType, 48));
            audit.setRefId(truncate(refId, 64));
            audit.setOutcomeJson(truncate(outcomeJson, 500));
            try {
                auditMapper.insertAudit(audit);
            } catch (DuplicateKeyException dup) {
                GameRandomAudit existing = auditMapper.selectByDomainAndReceipt(normalizedDomain, effectiveReceiptId);
                if (existing != null && existing.getTicket() != null) {
                    int existingTotal = existing.getTotal() == null ? total : existing.getTotal();
                    return new DrawResult(Math.floorMod(existing.getTicket(), total), existingTotal,
                            effectiveReceiptId, existing.getSeedDigest(), true);
                }
            }
        }
        return new DrawResult(ticket, total, effectiveReceiptId, seedDigest, false);
    }

    @Override
    public boolean hitBp(int probBp, String receiptId, Long memberId, String refType, String refId) {
        if (probBp <= 0) {
            return false;
        }
        if (probBp >= 10000) {
            draw(DOMAIN_REWARD_DROP, 10000, receiptId, memberId, refType, refId,
                    t -> jsonOutcome("hit", true, "probBp", probBp, "ticket", t));
            return true;
        }
        DrawResult result = draw(DOMAIN_REWARD_DROP, 10000, receiptId, memberId, refType, refId,
                t -> jsonOutcome("hit", t < probBp, "probBp", probBp, "ticket", t));
        return result.ticket() < probBp;
    }

    @Override
    public <T> PickResult<T> weightedPick(String domain, List<T> candidates, ToIntFunction<T> weightFn,
                                          String receiptId, Long memberId, String refType, String refId,
                                          Function<T, String> labelFn) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        int totalWeight = 0;
        for (T candidate : candidates) {
            int w = weightFn == null ? 1 : Math.max(0, weightFn.applyAsInt(candidate));
            totalWeight += w;
        }
        if (totalWeight <= 0) {
            return null;
        }
        final int finalTotalWeight = totalWeight;
        DrawResult draw = draw(domain, totalWeight, receiptId, memberId, refType, refId, ticket -> {
            T picked = pickAt(candidates, weightFn, ticket);
            String label = picked == null || labelFn == null ? "" : labelFn.apply(picked);
            return jsonOutcome("ticket", ticket, "totalWeight", finalTotalWeight, "picked", label);
        });
        T chosen = pickAt(candidates, weightFn, draw.ticket());
        return new PickResult<>(chosen, draw);
    }

    @Override
    public int d20(String receiptId, Long memberId, String refType, String refId) {
        return d20(DOMAIN_JOURNEY_CHECK, receiptId, memberId, refType, refId);
    }

    public int d20(String domain, String receiptId, Long memberId, String refType, String refId) {
        DrawResult result = draw(domain, 20, receiptId, memberId, refType, refId,
                t -> jsonOutcome("die", 20, "roll", t + 1));
        return result.ticket() + 1;
    }

    @Override
    public int d6(String domain, String receiptId, Long memberId, String refType, String refId) {
        DrawResult result = draw(domain, 6, receiptId, memberId, refType, refId,
                t -> jsonOutcome("die", 6, "roll", t + 1));
        return result.ticket() + 1;
    }

    @Override
    public int flipCoin(String receiptId, Long memberId, String refType, String refId) {
        DrawResult result = draw(DOMAIN_PLAYKIT_COIN, 2, receiptId, memberId, refType, refId,
                t -> jsonOutcome("side", t == 0 ? "HEADS" : "TAILS", "ticket", t));
        return result.ticket();
    }

    /**
     * 为闭环子系统（如 {@link AdvancedActionJudgement}）提供带域与凭据前缀的受审计随机适配器，
     * 不破坏子系统对 Mapper 的隔离边界。
     */
    public AdvancedGameRandom scoped(String domain, String receiptPrefix,
                                     Long memberId, String refType, String refId) {
        AtomicInteger seq = new AtomicInteger(0);
        return bound -> {
            int idx = seq.getAndIncrement();
            String receipt = (receiptPrefix == null || receiptPrefix.isBlank())
                    ? null
                    : (idx == 0 ? receiptPrefix : receiptPrefix + ":" + idx);
            DrawResult res = draw(domain, bound, receipt, memberId, refType, refId,
                    t -> jsonOutcome("bound", bound, "value", t));
            return res.ticket();
        };
    }

    @Override
    public List<GameRandomAudit> listAudits(GameRandomAudit filter) {
        if (auditMapper == null) {
            return Collections.emptyList();
        }
        return auditMapper.selectAuditList(filter == null ? new GameRandomAudit() : filter);
    }

    private <T> T pickAt(List<T> candidates, ToIntFunction<T> weightFn, int ticket) {
        int acc = 0;
        T lastPositive = null;
        for (T candidate : candidates) {
            int w = weightFn == null ? 1 : Math.max(0, weightFn.applyAsInt(candidate));
            if (w <= 0) {
                continue;
            }
            lastPositive = candidate;
            acc += w;
            if (ticket < acc) {
                return candidate;
            }
        }
        return lastPositive != null ? lastPositive : candidates.get(candidates.size() - 1);
    }

    private static String jsonOutcome(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return JSON.toJSONString(m);
    }

    private static String truncate(String val, int maxLen) {
        if (val == null) {
            return null;
        }
        return val.length() <= maxLen ? val : val.substring(0, maxLen);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
