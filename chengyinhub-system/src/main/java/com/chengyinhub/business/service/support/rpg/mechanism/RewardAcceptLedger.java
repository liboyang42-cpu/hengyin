package com.chengyinhub.business.service.support.rpg.mechanism;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * C03 exclusive original reward-accept ledger. Same {@code originalRequestId}
 * never grants a second accept. UNKNOWN occupies the id and must not mint a
 * retry. This is not a second wallet, dice, or Runtime writer.
 *
 * <p>Faction / territory / secret-goal economy values are not invented here.
 * Missing trigger actor or missing originalRequestId is UNKNOWN / null, not
 * an empty string.
 */
public final class RewardAcceptLedger {

    public static final String APPLIED = "APPLIED";
    public static final String REPLAY = "REPLAY";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String REJECTED = "REJECTED";
    public static final String WAITING_SOURCE = "WAITING_SOURCE";

    public enum Outcome {
        ACCEPTED,
        REPLAY,
        REJECTED,
        UNKNOWN,
        WAITING_SOURCE
    }

    /**
     * Author-frozen grant facts already resolved by the caller. Amounts must
     * come from the frozen publish; this ledger does not invent them.
     */
    public record AcceptFact(
            String originalRequestId,
            String triggerActorId,
            String rewardKind,
            String frozenGrantHash,
            Long frozenAmount,
            boolean explicitPlayerAccept) {

        public AcceptFact {
            rewardKind = rewardKind == null ? null : rewardKind;
        }
    }

    public record Decision(
            Outcome outcome,
            String originalRequestId,
            boolean secondEffect,
            boolean wroteWallet,
            boolean mintedNewRequestId,
            boolean projectionTakenAsSuccess,
            String reason) {

        public Decision {
            outcome = outcome == null ? Outcome.UNKNOWN : outcome;
            reason = reason == null ? "" : reason;
        }

        public boolean grantsAccept() {
            return outcome == Outcome.ACCEPTED || outcome == Outcome.REPLAY;
        }
    }

    public static final class Entry {
        private final String requestId;
        private String status = UNKNOWN;
        private Map<String, Object> result;

        Entry(String requestId) {
            this.requestId = requestId;
        }

        public String requestId() { return requestId; }
        public String status() { return status; }
        public Map<String, Object> result() {
            return result == null ? null : Collections.unmodifiableMap(result);
        }
    }

    private final Map<String, Entry> byRequestId = new LinkedHashMap<>();

    public Entry existing(String requestId) {
        return requestId == null ? null : byRequestId.get(requestId);
    }

    public Decision accept(AcceptFact fact) {
        if (fact == null) {
            return toDecision(unknown(null, "accept fact missing"));
        }
        String requestId = fact.originalRequestId();
        if (requestId == null || requestId.isBlank()) {
            return toDecision(unknown(null, "originalRequestId missing"));
        }
        Entry existing = byRequestId.get(requestId);
        if (existing != null) {
            Map<String, Object> replay = copy(existing.result);
            replay.put("originalRequestId", existing.requestId);
            String replayStatus = APPLIED.equals(existing.status) || REPLAY.equals(existing.status)
                    ? REPLAY : existing.status;
            replay.put("status", replayStatus);
            if (REPLAY.equals(replayStatus)) {
                replay.put("outcome", Outcome.REPLAY.name());
            }
            replay.put("replay", Boolean.TRUE);
            replay.put("secondEffect", Boolean.FALSE);
            replay.put("wroteWallet", Boolean.FALSE);
            replay.put("mintedNewRequestId", Boolean.FALSE);
            replay.put("projectionTakenAsSuccess", Boolean.FALSE);
            return toDecision(replay);
        }
        Map<String, Object> live = liveAccept(fact);
        Entry entry = new Entry(requestId);
        entry.status = String.valueOf(live.get("status"));
        entry.result = copy(live);
        byRequestId.put(requestId, entry);
        return toDecision(copy(live));
    }

    public Decision recover(String originalRequestId) {
        if (originalRequestId == null || originalRequestId.isBlank()) {
            return toDecision(unknown(null, "originalRequestId missing"));
        }
        Entry entry = existing(originalRequestId);
        if (entry == null) {
            return toDecision(unknown(originalRequestId, "no original accept receipt"));
        }
        Map<String, Object> out = copy(entry.result);
        out.put("originalRequestId", entry.requestId);
        out.put("status", entry.status);
        out.put("clearedOriginalIntent", Boolean.FALSE);
        out.put("mintedNewRequestId", Boolean.FALSE);
        out.put("secondEffect", Boolean.FALSE);
        if (UNKNOWN.equals(entry.status)) {
            out.put("projectionTakenAsSuccess", Boolean.FALSE);
        }
        return toDecision(out);
    }

    /**
     * 对齐 iOS {@code recoverOriginal}:只读原 requestId。未见过的 id 不占账。
     * 缺 requestId 保持 Java null + UNKNOWN，不抛。
     */
    public Decision recoverOriginal(String originalRequestId) {
        return recover(originalRequestId);
    }

    /**
     * Record UNKNOWN for an already-bound id. A never-seen id must not occupy
     * the ledger, or a later unique accept becomes a replay of minted UNKNOWN.
     * APPLIED / REPLAY / WAITING_SOURCE / REJECTED original outcomes are not clobbered.
     */
    public Decision markUnknown(String originalRequestId, String reason) {
        if (originalRequestId == null || originalRequestId.isBlank()) {
            return toDecision(unknown(null, "originalRequestId missing"));
        }
        Entry entry = byRequestId.get(originalRequestId);
        if (entry == null) {
            return toDecision(unknown(originalRequestId, reason == null ? UNKNOWN : reason));
        }
        if (APPLIED.equals(entry.status) || REPLAY.equals(entry.status)
                || WAITING_SOURCE.equals(entry.status) || REJECTED.equals(entry.status)) {
            Map<String, Object> kept = copy(entry.result);
            kept.put("clearedOriginalIntent", Boolean.FALSE);
            kept.put("mintedNewRequestId", Boolean.FALSE);
            kept.put("projectionTakenAsSuccess", Boolean.FALSE);
            kept.put("reason", "UNKNOWN must not clear original accept");
            return toDecision(kept);
        }
        Map<String, Object> stored = unknown(originalRequestId, reason == null ? UNKNOWN : reason);
        entry.status = UNKNOWN;
        entry.result = stored;
        return toDecision(copy(stored));
    }

    private Map<String, Object> liveAccept(AcceptFact fact) {
        Objects.requireNonNull(fact, "fact");
        if (!fact.explicitPlayerAccept()) {
            return rejected(fact.originalRequestId(), "player must explicitly accept");
        }
        if (fact.triggerActorId() == null || fact.triggerActorId().isBlank()) {
            return unknown(fact.originalRequestId(), "trigger actor missing");
        }
        if (fact.frozenGrantHash() == null || fact.frozenGrantHash().isBlank()
                || fact.frozenAmount() == null) {
            Map<String, Object> waiting = unknown(fact.originalRequestId(),
                    WAITING_SOURCE + ":frozen grant original");
            waiting.put("status", WAITING_SOURCE);
            waiting.put("outcome", Outcome.WAITING_SOURCE.name());
            return waiting;
        }
        if (fact.frozenAmount() < 0L) {
            return rejected(fact.originalRequestId(), "frozen amount cannot be negative");
        }
        if (fact.rewardKind() == null || fact.rewardKind().isBlank()) {
            return rejected(fact.originalRequestId(), "reward kind missing");
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("originalRequestId", fact.originalRequestId());
        ok.put("status", APPLIED);
        ok.put("outcome", Outcome.ACCEPTED.name());
        ok.put("triggerActorId", fact.triggerActorId());
        ok.put("rewardKind", fact.rewardKind());
        ok.put("frozenGrantHash", fact.frozenGrantHash());
        ok.put("frozenAmount", fact.frozenAmount());
        ok.put("wroteWallet", Boolean.FALSE);
        ok.put("secondEffect", Boolean.FALSE);
        ok.put("mintedNewRequestId", Boolean.FALSE);
        ok.put("projectionTakenAsSuccess", Boolean.FALSE);
        ok.put("reason", "original accept bound to frozen grant");
        return ok;
    }

    static Map<String, Object> unknown(String requestId, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("originalRequestId", requestId);
        out.put("status", UNKNOWN);
        out.put("outcome", Outcome.UNKNOWN.name());
        out.put("reason", reason);
        out.put("wroteWallet", Boolean.FALSE);
        out.put("secondEffect", Boolean.FALSE);
        out.put("clearedOriginalIntent", Boolean.FALSE);
        out.put("mintedNewRequestId", Boolean.FALSE);
        out.put("projectionTakenAsSuccess", Boolean.FALSE);
        return out;
    }

    private static Map<String, Object> rejected(String requestId, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("originalRequestId", requestId);
        out.put("status", REJECTED);
        out.put("outcome", Outcome.REJECTED.name());
        out.put("reason", reason);
        out.put("wroteWallet", Boolean.FALSE);
        out.put("secondEffect", Boolean.FALSE);
        out.put("mintedNewRequestId", Boolean.FALSE);
        out.put("projectionTakenAsSuccess", Boolean.FALSE);
        return out;
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }

    private static Decision toDecision(Map<String, Object> result) {
        Outcome outcome = Outcome.UNKNOWN;
        Object named = result.get("outcome");
        if (named instanceof String) {
            try {
                outcome = Outcome.valueOf((String) named);
            } catch (IllegalArgumentException ignored) {
                outcome = Outcome.UNKNOWN;
            }
        } else if (REPLAY.equals(String.valueOf(result.get("status")))) {
            outcome = Outcome.REPLAY;
        } else if (WAITING_SOURCE.equals(String.valueOf(result.get("status")))) {
            outcome = Outcome.WAITING_SOURCE;
        } else if (REJECTED.equals(String.valueOf(result.get("status")))) {
            outcome = Outcome.REJECTED;
        } else if (UNKNOWN.equals(String.valueOf(result.get("status")))) {
            outcome = Outcome.UNKNOWN;
        } else if (APPLIED.equals(String.valueOf(result.get("status")))) {
            outcome = Outcome.ACCEPTED;
        }
        Object requestId = result.get("originalRequestId");
        return new Decision(
                outcome,
                requestId instanceof String ? (String) requestId : null,
                Boolean.TRUE.equals(result.get("secondEffect")),
                Boolean.TRUE.equals(result.get("wroteWallet")),
                Boolean.TRUE.equals(result.get("mintedNewRequestId")),
                Boolean.TRUE.equals(result.get("projectionTakenAsSuccess")),
                result.get("reason") == null ? String.valueOf(result.get("status"))
                        : String.valueOf(result.get("reason")));
    }
}
