package com.chengyinhub.business.citycoin;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.chengyinhub.business.mapper.CityCoinMapper;
import com.chengyinhub.system.service.ISysConfigService;

/**
 * R7 MKT-12 / MON-10: territory yield is paid in city coin, a separate currency that can never be cashed or
 * converted. This service only credits and reads. There is deliberately no spend, transfer, exchange or
 * withdraw operation; {@code CityCoinNoConversionGuardTest} fails the build if a route, job or admin action
 * starts converting city coin into points, cash, coupons or orders.
 *
 * <p>CITY wiring (owned by the coordinator): when a check-in at a held place settles yield for its holder, call
 * {@link #creditHolderAfterCommit(Long, String, String)} with the holder member, the place id and the check-in's
 * own unique reference. See {@code docs/roadmap/points-governance/RESULT.md}.
 */
@Service
public class CityCoinService {

    public static final String REASON_HOLDER_YIELD = "HOLDER_YIELD";
    public static final String CONFIG_HOLDER_YIELD = "city.coin.holder_yield";
    public static final String CONFIG_DAILY_CAP = "city.coin.daily_cap";
    static final int DEFAULT_HOLDER_YIELD = 10;
    static final int DEFAULT_DAILY_CAP = 100;
    static final int MAX_PLACE_ID = 64;
    static final int MAX_CHECK_IN_REF = 80;
    static final int HISTORY_LIMIT = 50;
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Logger log = LoggerFactory.getLogger(CityCoinService.class);

    /** CREDITED, DUPLICATE (this reference was already credited), CAPPED (daily cap reached), INVALID, UNAVAILABLE. */
    public enum Outcome { CREDITED, DUPLICATE, CAPPED, INVALID, UNAVAILABLE }

    public record CreditResult(Outcome outcome, int amount, Long balance) {
        static CreditResult of(Outcome outcome) { return new CreditResult(outcome, 0, null); }
    }

    @Autowired(required = false)
    private CityCoinMapper mapper;
    @Autowired(required = false)
    private ISysConfigService configService;
    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;
    private Clock clock = Clock.system(ZONE);

    public CityCoinService() {
    }

    /** For tests. A null transaction manager runs the credit without a transaction. */
    public CityCoinService(CityCoinMapper mapper, ISysConfigService configService, PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.configService = configService;
        this.transactionManager = transactionManager;
    }

    void setClock(Clock clock) { this.clock = clock; }

    /**
     * Credits the holder of {@code placeId} for one check-in. Idempotent on {@code (placeId, checkInRef)}: the
     * same reference never credits twice. Runs in its own transaction and never throws, so a city coin problem can
     * never undo or block the caller's game action; a failure returns {@link Outcome#UNAVAILABLE} and is logged.
     */
    public CreditResult creditHolder(Long memberId, String placeId, String checkInRef) {
        if (memberId == null || memberId <= 0 || blank(placeId) || blank(checkInRef)
                || placeId.trim().length() > MAX_PLACE_ID || checkInRef.trim().length() > MAX_CHECK_IN_REF) {
            return CreditResult.of(Outcome.INVALID);
        }
        if (mapper == null) return CreditResult.of(Outcome.UNAVAILABLE);
        String place = placeId.trim();
        String refKey = refKey(place, checkInRef.trim());
        try {
            if (transactionManager == null) return credit(memberId, place, refKey);
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return tx.execute(status -> credit(memberId, place, refKey));
        } catch (DuplicateKeyException race) {
            return CreditResult.of(Outcome.DUPLICATE);
        } catch (RuntimeException e) {
            log.error("city coin credit failed member={} place={} ref={}: {}", memberId, place, refKey, e.toString());
            return CreditResult.of(Outcome.UNAVAILABLE);
        }
    }

    /**
     * Same as {@link #creditHolder} but, when called inside a transaction, waits until that transaction commits,
     * so a rolled-back claim or check-in never pays yield. Outside a transaction it credits right away.
     */
    public void creditHolderAfterCommit(Long memberId, String placeId, String checkInRef) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    creditHolder(memberId, placeId, checkInRef);
                }
            });
            return;
        }
        creditHolder(memberId, placeId, checkInRef);
    }

    private CreditResult credit(Long memberId, String placeId, String refKey) {
        if (mapper.selectByRef(refKey) != null) return CreditResult.of(Outcome.DUPLICATE);
        Date now = Date.from(clock.instant());
        mapper.ensureAccount(memberId, now);
        Long before = mapper.selectBalanceForUpdate(memberId);
        if (before == null) throw new IllegalStateException("city coin account missing after ensure, member=" + memberId);
        // Re-check under the member lock: a concurrent credit of the same reference has committed by now.
        if (mapper.selectByRef(refKey) != null) return CreditResult.of(Outcome.DUPLICATE);
        int yield = config(CONFIG_HOLDER_YIELD, DEFAULT_HOLDER_YIELD, 0, 10_000);
        int cap = config(CONFIG_DAILY_CAP, DEFAULT_DAILY_CAP, 0, 1_000_000);
        long today = mapper.sumCreditedSince(memberId, REASON_HOLDER_YIELD, startOfToday());
        int amount = (int) Math.min(yield, Math.max(0L, cap - today));
        if (amount <= 0) return new CreditResult(Outcome.CAPPED, 0, before);
        long after = before + amount;
        mapper.insertCredit(memberId, amount, after, REASON_HOLDER_YIELD, refKey, placeId, now);
        if (mapper.creditBalance(memberId, amount, now) != 1) {
            throw new IllegalStateException("city coin balance update failed, member=" + memberId);
        }
        return new CreditResult(Outcome.CREDITED, amount, after);
    }

    /** The player's own balance and recent history (read route). */
    public Map<String, Object> summary(Long memberId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nonCashable", true);
        out.put("dailyCap", config(CONFIG_DAILY_CAP, DEFAULT_DAILY_CAP, 0, 1_000_000));
        Long balance = mapper == null ? null : mapper.selectBalance(memberId);
        out.put("balance", balance == null ? 0L : balance);
        out.put("todayEarned", mapper == null ? 0L : mapper.sumCreditedSince(memberId, REASON_HOLDER_YIELD, startOfToday()));
        List<Map<String, Object>> history = mapper == null ? List.of() : mapper.selectHistory(memberId, HISTORY_LIMIT);
        out.put("history", history);
        return out;
    }

    /** Admin dashboard figures. City coin carries no cash liability: it cannot be cashed or converted. */
    public Map<String, Object> dashboard(Date from, Date to) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            out.put("creditedInPeriod", mapper == null ? 0L : mapper.sumCreditedBetween(from, to));
            out.put("outstanding", mapper == null ? 0L : mapper.sumOutstanding());
            out.put("available", mapper != null);
        } catch (RuntimeException e) {
            log.warn("city coin dashboard unavailable: {}", e.toString());
            out.put("available", false);
        }
        out.put("liabilityYuan", 0);
        return out;
    }

    static String refKey(String placeId, String checkInRef) {
        return "holder:" + placeId + ":" + checkInRef;
    }

    private Date startOfToday() {
        return Date.from(LocalDate.now(clock).atStartOfDay(ZONE).toInstant());
    }

    private int config(String key, int fallback, int min, int max) {
        try {
            String raw = configService == null ? null : configService.selectConfigByKey(key);
            if (raw == null || raw.isBlank()) return fallback;
            int v = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, v));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
