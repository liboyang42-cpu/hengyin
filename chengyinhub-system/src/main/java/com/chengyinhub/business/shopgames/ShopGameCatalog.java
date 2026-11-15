package com.chengyinhub.business.shopgames;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.chengyinhub.business.service.support.GameplaySupportMatrix;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * S5 shop games: which games a shop may offer, by shop kind.
 *
 * <p>Every game here is an existing play-kit game type (a key of {@link GameplaySupportMatrix} that can prove
 * completion), so the existing play runtime plays it. Every shop kind has a pool of exactly {@link #POOL_SIZE}
 * games. The fairness rule (checked by {@link #fairnessProblems()} and the tests):
 * <ul>
 *   <li>every game runs at the one standard level: {@link #ROUNDS} rounds, win {@link #PASS_ROUNDS}, same reward;</li>
 *   <li>every game takes {@link #MIN_GAME_SECONDS}..{@link #MAX_GAME_SECONDS} seconds;</li>
 *   <li>every pool's average is {@link #MIN_POOL_SECONDS}..{@link #MAX_POOL_SECONDS} seconds;</li>
 *   <li>every pool has at least one game that needs neither movement nor the camera.</li>
 * </ul>
 * Nothing here reads plans, sponsorship or paid placement: a paid plan only lets a shop offer more of its pool.
 */
public final class ShopGameCatalog {

    public static final int ROUNDS = 3;
    public static final int PASS_ROUNDS = 2;
    public static final int POOL_SIZE = 3;
    public static final int MIN_GAME_SECONDS = 45;
    public static final int MAX_GAME_SECONDS = 90;
    public static final int MIN_POOL_SECONDS = 50;
    public static final int MAX_POOL_SECONDS = 75;
    /** Points source for a won shop game (ums_member_points_detail.event_type); capped by points governance. */
    public static final long POINTS_EVENT_TYPE = 31L;

    /** One game type with its standard length. {@code key} is a {@link GameplaySupportMatrix} type key. */
    public record Game(String key, String nameZh, String nameEn, int seconds, boolean needsMotion, boolean needsCamera) {
        public boolean accessible() { return !needsMotion && !needsCamera; }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("nameZh", nameZh);
            m.put("nameEn", nameEn);
            m.put("seconds", seconds);
            m.put("rounds", ROUNDS);
            m.put("passRounds", PASS_ROUNDS);
            m.put("needsMotion", needsMotion);
            m.put("needsCamera", needsCamera);
            return m;
        }
    }

    /** A shop kind: its pool (first = default) and the category-name words that select it. */
    public record Kind(String code, String nameZh, String nameEn, List<String> pool, List<String> words) {
        public String defaultGame() { return pool.get(0); }
    }

    private static final Map<String, Game> GAMES = new LinkedHashMap<>();
    private static final Map<String, Kind> KINDS = new LinkedHashMap<>();
    public static final String OTHER = "OTHER";

    static {
        game("qaPick", "快问快答", "Quick quiz", 60, false, false);
        game("trueFalse", "对还是错", "True or false", 45, false, false);
        game("imageGuess", "看图猜一猜", "Guess from the picture", 60, false, false);
        game("hangman", "猜字谜", "Word riddle", 75, false, false);
        game("qaText", "谜语作答", "Answer the riddle", 75, false, false);
        game("steps", "走几步", "Step challenge", 90, true, false);
        game("react", "变色就点", "Tap on the colour", 45, true, false);
        game("qaShot", "拍照挑战", "Photo challenge", 75, false, true);
        game("pricePair", "比一比价", "Which costs more", 60, false, false);
        game("hidden", "找东西", "Spot it", 60, false, false);
        game("jigsaw", "拼图", "Jigsaw", 90, false, false);
        game("sort", "排一排", "Put in order", 60, false, false);
        game("estimate", "猜数字", "Guess the number", 45, false, false);

        // Order matters: the first kind whose word appears in a category name wins.
        kind("BAR", "酒馆", "Bar", List.of("trueFalse", "imageGuess", "estimate"), List.of("酒", "bar", "pub"));
        kind("CAFE", "咖啡茶饮", "Café", List.of("qaPick", "imageGuess", "trueFalse"),
                List.of("咖啡", "茶", "饮品", "cafe", "café", "coffee", "tea"));
        kind("BAKERY", "烘焙甜品", "Bakery", List.of("imageGuess", "pricePair", "estimate"),
                List.of("烘焙", "甜品", "蛋糕", "面包", "bakery", "dessert"));
        kind("BOOKSHOP", "书店", "Bookshop", List.of("hangman", "qaText", "trueFalse"),
                List.of("书", "阅读", "book"));
        kind("GYM", "运动健身", "Gym", List.of("steps", "react", "trueFalse"),
                List.of("健身", "运动", "瑜伽", "攀岩", "球", "gym", "fitness", "sport", "yoga"));
        kind("RESTAURANT", "餐厅", "Restaurant", List.of("qaShot", "pricePair", "qaPick"),
                List.of("餐", "饭", "面", "火锅", "小吃", "美食", "烧烤", "料理", "restaurant", "food"));
        kind("GALLERY", "展馆画廊", "Gallery", List.of("hidden", "jigsaw", "imageGuess"),
                List.of("美术", "画廊", "展", "博物", "艺术", "gallery", "museum", "art"));
        kind("CRAFT", "手作工坊", "Craft workshop", List.of("sort", "jigsaw", "qaShot"),
                List.of("手作", "工坊", "陶", "craft", "workshop"));
        kind("RETAIL", "古着文创", "Vintage & gifts", List.of("estimate", "pricePair", "hidden"),
                List.of("古着", "中古", "文创", "杂货", "买手", "vintage", "gift"));
        kind("FLORIST", "花店", "Florist", List.of("imageGuess", "hidden", "qaPick"), List.of("花", "flower", "florist"));
        kind("PET", "宠物友好", "Pet friendly", List.of("qaShot", "imageGuess", "trueFalse"), List.of("宠物", "pet"));
        kind(OTHER, "其他店铺", "Other shop", List.of("qaPick", "trueFalse", "hidden"), List.of());
    }

    private ShopGameCatalog() { }

    private static void game(String key, String zh, String en, int seconds, boolean motion, boolean camera) {
        GAMES.put(key, new Game(key, zh, en, seconds, motion, camera));
    }

    private static void kind(String code, String zh, String en, List<String> pool, List<String> words) {
        KINDS.put(code, new Kind(code, zh, en, List.copyOf(pool), List.copyOf(words)));
    }

    public static Map<String, Game> games() { return Collections.unmodifiableMap(GAMES); }

    public static Map<String, Kind> kinds() { return Collections.unmodifiableMap(KINDS); }

    public static Game game(String key) { return key == null ? null : GAMES.get(key); }

    public static Kind kind(String code) {
        Kind k = code == null ? null : KINDS.get(code.trim().toUpperCase(Locale.ROOT));
        return k != null ? k : KINDS.get(OTHER);
    }

    public static boolean knownKind(String code) {
        return code != null && KINDS.containsKey(code.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * The shop kind for a merchant's category names, in the merchant's own order (primary category first).
     * Unknown or empty names give {@link #OTHER}, so every merchant has a default.
     */
    public static Kind kindForCategories(List<String> categoryNames) {
        if (categoryNames != null) {
            for (String raw : categoryNames) {
                if (raw == null || raw.isBlank()) continue;
                String name = raw.trim().toLowerCase(Locale.ROOT);
                for (Kind k : KINDS.values()) {
                    for (String w : k.words()) {
                        if (name.contains(w.toLowerCase(Locale.ROOT))) return k;
                    }
                }
            }
        }
        return KINDS.get(OTHER);
    }

    /** How many games a shop may offer: one, plus its plan's extra node games, never more than its pool. */
    public static int allowance(int extraNodeGames) {
        return Math.max(1, Math.min(POOL_SIZE, 1 + Math.max(0, extraNodeGames)));
    }

    /**
     * The catalog game a node template plays, or null. Reads the template's advanced play-kit sections through the
     * shared {@link GameplaySupportMatrix} (section + mode), then its legacy validation method.
     */
    public static String detectGame(Integer validationMethod, JsonNode advanced) {
        if (advanced != null && advanced.isObject()) {
            JsonNode types = GameplaySupportMatrix.view().path("types");
            for (JsonNode row : types) {
                String key = row.path("key").asText();
                if (!GAMES.containsKey(key)) continue;
                JsonNode section = advanced.path(row.path("section").asText());
                if (!section.path("enabled").asBoolean(false)) continue;
                if (row.has("mode") && !row.path("mode").asText().equals(section.path("mode").asText())) continue;
                return key;
            }
        }
        if (validationMethod == null) return null;
        switch (validationMethod) {
            case 1: return "qaText";
            case 2: return "qaShot";
            case 3: return "qaPick";
            case 8: return "estimate";
            case 9: return "pricePair";
            case 10: return "hidden";
            default: return null;
        }
    }

    /** Fairness problems in the catalog; empty when every rule in the class comment holds. */
    public static List<String> fairnessProblems() {
        List<String> problems = new ArrayList<>();
        for (Game g : GAMES.values()) {
            if (g.seconds() < MIN_GAME_SECONDS || g.seconds() > MAX_GAME_SECONDS) problems.add(g.key() + " length");
        }
        for (Kind k : KINDS.values()) {
            if (k.pool().size() != POOL_SIZE) problems.add(k.code() + " pool size");
            if (k.pool().stream().distinct().count() != k.pool().size()) problems.add(k.code() + " repeats a game");
            int total = 0;
            boolean accessible = false;
            for (String key : k.pool()) {
                Game g = GAMES.get(key);
                if (g == null) { problems.add(k.code() + " unknown game " + key); continue; }
                total += g.seconds();
                accessible |= g.accessible();
            }
            int mean = k.pool().isEmpty() ? 0 : total / k.pool().size();
            if (mean < MIN_POOL_SECONDS || mean > MAX_POOL_SECONDS) problems.add(k.code() + " pool length " + mean);
            if (!accessible) problems.add(k.code() + " has no game without movement or camera");
        }
        return problems;
    }

    /** The standard rules every shop game uses; the same for every shop, plan and sponsor. */
    public static Map<String, Object> standardRules() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rounds", ROUNDS);
        m.put("passRounds", PASS_ROUNDS);
        m.put("pointsEventType", POINTS_EVENT_TYPE);
        return m;
    }

    public static Map<String, Object> kindMap(Kind k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", k.code());
        m.put("nameZh", k.nameZh());
        m.put("nameEn", k.nameEn());
        List<Map<String, Object>> pool = new ArrayList<>();
        for (String key : k.pool()) pool.add(GAMES.get(key).toMap());
        m.put("pool", pool);
        m.put("defaultGame", k.defaultGame());
        return m;
    }
}
