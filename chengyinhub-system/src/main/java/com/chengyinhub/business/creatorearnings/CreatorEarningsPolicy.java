package com.chengyinhub.business.creatorearnings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * R12 creator earnings rules, free of I/O so every number can be unit-tested.
 *
 * <p>Points are the only thing paid now. A price-based sale records the intended creator share and a money state
 * that is never "paid": {@code HELD_MONEY_OFF} while the money switch is off, {@code PENDING_PAYOUT} after it is on.
 * No payout path exists in this package.
 */
public final class CreatorEarningsPolicy {

    public static final String ADOPT = "ADOPT";
    public static final String INSTALL = "INSTALL";
    public static final String PLAY = "PLAY";
    public static final String SALE = "SALE";
    public static final String SALE_REVERSAL = "SALE_REVERSAL";

    public static final String MONEY_NONE = "NONE";
    public static final String MONEY_HELD = "HELD_MONEY_OFF";
    public static final String MONEY_PENDING_PAYOUT = "PENDING_PAYOUT";

    /** ums_member_points_detail.event_type for creator earnings (unused elsewhere). */
    public static final long POINTS_EVENT_TYPE = 96L;

    /**
     * @param adoptPoints        points to the template creator when another member adopts a library template
     * @param installPoints      points to the workshop seller when a buyer installs the purchased version
     * @param playPoints         points per verified play (another member's node check-in on the creator's item)
     * @param dailyPlayCap       rewarded plays per creator per day; plays beyond it are recorded as capped
     * @param dailyPlayPerPlayer rewarded plays per (creator, player, item) per day
     * @param saleShareBps       intended creator share of a sale, in basis points
     */
    public record Config(int adoptPoints, int installPoints, int playPoints, int dailyPlayCap,
                         int dailyPlayPerPlayer, int saleShareBps) {
        public Config {
            if (adoptPoints < 0 || installPoints < 0 || playPoints < 0 || dailyPlayCap < 0 || dailyPlayPerPlayer < 0)
                throw new IllegalArgumentException("creator earnings config must not be negative");
            if (saleShareBps < 0 || saleShareBps > 10000)
                throw new IllegalArgumentException("saleShareBps must be within 0..10000");
        }
    }

    private final Config config;

    public CreatorEarningsPolicy(Config config) {
        if (config == null) throw new IllegalArgumentException("config required");
        this.config = config;
    }

    public Config config() { return config; }

    /** Deterministic idempotency key: one ledger row per business event. */
    public static String eventKey(String eventType, String sourceRef) {
        if (eventType == null || sourceRef == null || sourceRef.isBlank())
            throw new IllegalArgumentException("event type and source are required");
        String key = eventType + ":" + sourceRef;
        if (key.length() > 128) throw new IllegalArgumentException("event key too long");
        return key;
    }

    /** The reward_business_key on the points row, so the points ledger itself also rejects a replay. */
    public static String pointsBusinessKey(String eventKey) {
        return "creator-earning:" + eventKey;
    }

    /** A creator never earns from their own use, play or purchase. */
    public static boolean countable(Long creator, Long actor) {
        return creator != null && creator > 0 && (actor == null || !creator.equals(actor));
    }

    public int pointsFor(String eventType) {
        return switch (eventType) {
            case ADOPT -> config.adoptPoints();
            case INSTALL -> config.installPoints();
            case PLAY -> config.playPoints();
            default -> 0;
        };
    }

    /**
     * Whether a verified play still earns points today.
     *
     * @param creatorRewardedToday plays of this creator already rewarded today
     * @param playerItemRewardedToday plays already rewarded today for the same player on the same item
     */
    public boolean playWithinCap(long creatorRewardedToday, long playerItemRewardedToday) {
        return creatorRewardedToday < config.dailyPlayCap() && playerItemRewardedToday < config.dailyPlayPerPlayer();
    }

    /** Intended creator share in minor units, rounded down. Never negative. */
    public long creatorShare(long grossMinor) {
        if (grossMinor <= 0) return 0L;
        return Math.multiplyExact(grossMinor, (long) config.saleShareBps()) / 10000L;
    }

    /** Money is never marked paid here; the switch only decides whether it waits for "money on" or for a payout. */
    public static String moneyState(boolean moneyOn) {
        return moneyOn ? MONEY_PENDING_PAYOUT : MONEY_HELD;
    }

    /** App income fact key: "MOD" + 29 lowercase hex, stable for a workshop order number. */
    public static String incomeOrderKey(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) throw new IllegalArgumentException("order number required");
        return "MOD" + sha256(orderNo).substring(0, 29);
    }

    public static boolean isIncomeOrderKey(String value) {
        return value != null && value.matches("MOD[0-9a-f]{29}");
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
