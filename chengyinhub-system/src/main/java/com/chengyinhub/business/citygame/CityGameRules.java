package com.chengyinhub.business.citygame;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntUnaryOperator;
import java.util.regex.Pattern;

/**
 * CITY game rules that do not touch storage. The server is the only party that rolls the die,
 * prices an action or decides an outcome; the client sends ids, versions and consent only.
 */
public final class CityGameRules {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,95}");
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");
    public static final long MAX_SAFE = 9_007_199_254_740_991L;

    /** Wire action names shared with the app's CityActionKind. */
    public enum Action {
        ROLL_ROUND, ACCEPT_TASK, SUBMIT_EVIDENCE, ACQUIRE_PROPERTY, ACCEPT_SERVICE_VISIT, COMPLETE_SERVICE_VISIT, START_CHALLENGE;

        public boolean requiresQuote() {
            return this == ACQUIRE_PROPERTY || this == ACCEPT_SERVICE_VISIT || this == START_CHALLENGE;
        }

        public static Action parse(String value) {
            if (value == null) return null;
            for (Action action : values()) if (action.name().equals(value)) return action;
            return null;
        }
    }

    private CityGameRules() {}

    public static boolean id(String value) { return value != null && ID.matcher(value).matches(); }
    public static boolean uuid(String value) { return value != null && UUID.matcher(value).matches(); }
    public static boolean hash(String value) { return value != null && HASH.matcher(value).matches(); }
    public static boolean version(Long value) { return value != null && value >= 0 && value <= MAX_SAFE; }

    /** Faces and weights as published in city_game_board; invalid tables fail closed. */
    public static Dice dice(String facesJson, String weightsJson) {
        JSONArray faces = JSON.parseArray(facesJson);
        JSONArray weights = JSON.parseArray(weightsJson);
        if (faces == null || weights == null || faces.isEmpty() || faces.size() > 200 || faces.size() != weights.size()) {
            throw new IllegalStateException("dice table invalid");
        }
        int[] f = new int[faces.size()];
        long[] w = new long[weights.size()];
        long total = 0;
        for (int i = 0; i < f.length; i++) {
            f[i] = faces.getIntValue(i);
            w[i] = weights.getLongValue(i);
            if (f[i] < 0 || w[i] < 1) throw new IllegalStateException("dice table invalid");
            total += w[i];
        }
        if (total > Integer.MAX_VALUE) throw new IllegalStateException("dice table invalid");
        return new Dice(f, w, (int) total);
    }

    public static final class Dice {
        private final int[] faces;
        private final long[] weights;
        private final int total;

        Dice(int[] faces, long[] weights, int total) { this.faces = faces; this.weights = weights; this.total = total; }

        public int maxFace() {
            int max = 0;
            for (int face : faces) max = Math.max(max, face);
            return max;
        }

        /**
         * Weighted roll. {@code bound} returns a uniform value in [0, n); production passes
         * {@code SecureRandom::nextInt}, tests pass a fixed sequence.
         */
        public int roll(IntUnaryOperator bound) { return faces[rollIndex(bound)]; }

        /** Weighted roll returning the index into the published faces (faces may repeat). */
        public int rollIndex(IntUnaryOperator bound) {
            int ticket = bound.applyAsInt(total);
            if (ticket < 0 || ticket >= total) throw new IllegalStateException("random out of range");
            long cursor = 0;
            for (int i = 0; i < faces.length; i++) {
                cursor += weights[i];
                if (ticket < cursor) return i;
            }
            throw new IllegalStateException("unreachable");
        }

        public int face(int index) { return faces[index]; }
    }

    /** Next track position after a move; the track is the board's properties ordered by track_index. */
    public static int advance(int position, int face, int trackSize) {
        if (trackSize <= 0) throw new IllegalStateException("empty track");
        return Math.floorMod(position + face, trackSize);
    }

    /** Challenger wins only on a strictly higher server roll; ties keep the defender. */
    public static boolean challengerWins(int challengerFace, int defenderFace) { return challengerFace > defenderFace; }

    /** Score a team banks when its round is settled. */
    public static long settlementScore(long heldProperties, int holdingYield) {
        return Math.max(0, heldProperties) * Math.max(0, holdingYield);
    }

    /** Action units charged for an action under the published board rules. */
    public static int actionCost(Action action, Map<String, Object> board) {
        switch (action) {
            case ROLL_ROUND: return integer(board.get("rollCost"));
            case ACQUIRE_PROPERTY: return integer(board.get("acquisitionCost"));
            case ACCEPT_SERVICE_VISIT: return integer(board.get("serviceCost"));
            case START_CHALLENGE: return integer(board.get("challengeCost"));
            default: return integer(board.get("taskCost"));
        }
    }

    /** Canonical request digest; a requestId reused with another body is rejected. */
    public static String requestHash(Map<String, ?> body) {
        return sha256(JSON.toJSONString(new TreeMap<>(body)));
    }

    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static int integer(Object value) {
        if (value == null) throw new IllegalStateException("missing rule value");
        return ((Number) value).intValue();
    }

    public static long number(Object value) {
        if (value == null) throw new IllegalStateException("missing rule value");
        return ((Number) value).longValue();
    }

    public static List<Integer> ints(String json) { return JSON.parseArray(json, Integer.class); }
}
