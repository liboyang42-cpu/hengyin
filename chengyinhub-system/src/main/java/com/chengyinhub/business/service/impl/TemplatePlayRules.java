package com.chengyinhub.business.service.impl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.chengyinhub.business.service.support.HangmanGameplay;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static com.chengyinhub.business.service.impl.AdvancedActionJudgement.enabled;
import static com.chengyinhub.business.service.impl.AdvancedActionJudgement.mechanicsReady;

/**
 * W7 x-shop-game-runner: which advanced-runtime sections a template-only session (a shop game played from the
 * place page or a City Monopoly landing) may use, and when such a session is over and won.
 *
 * <p>A template-only session has no activity, topic or node. So only sections that the section engine
 * ({@link AdvancedActionJudgement}) judges on their own are allowed: no branch, team, outcome rules, journey facts,
 * predictions or bingo. Pure functions; no I/O.
 */
public final class TemplatePlayRules {

    /** Sections a shop game template may enable (the shop-game catalog's games plus a timer). */
    public static final Set<String> SECTIONS = Set.of("qa", "trueFalse", "imageGuess", "hangman", "reaction",
            "pricePair", "hiddenObject", "jigsaw", "sort", "estimate", "timer");

    /** Actions a template-only session accepts. Everything else is refused before any state is read. */
    public static final Set<String> ACTIONS = Set.of("SUBMIT_QA", "SUBMIT_TRUE_FALSE", "SUBMIT_IMAGE_GUESS",
            "SUBMIT_HANGMAN", "START_CHALLENGE", "SUBMIT_REACTION", "SUBMIT_PRICE_PAIR", "SUBMIT_HIDDEN_OBJECT",
            "SUBMIT_JIGSAW", "SUBMIT_SORT", "SUBMIT_ESTIMATE", TemplatePlayRules.GIVE_UP);

    /** Ends a running template session as not won (the player stops playing). */
    public static final String GIVE_UP = "GIVE_UP";

    /**
     * A template-only session never offers unlimited tries: a section whose template allows unlimited tries (0) gets
     * this many, so a shop-game win cannot be reached by guessing until right. Matches the shop-game standard of
     * three rounds ({@code ShopGameCatalog.ROUNDS}).
     */
    public static final int TRIES_CAP = 3;

    private TemplatePlayRules() { }

    /** Caps unlimited tries (see {@link #TRIES_CAP}) in place and returns the same config. */
    public static ObjectNode capTries(ObjectNode config) {
        for (String section : List.of("qa", "trueFalse", "imageGuess", "jigsaw", "pricePair", "hiddenObject")) {
            capField(config, section, "maxTries");
        }
        capField(config, "sort", "maxAttempts");
        return config;
    }

    private static void capField(ObjectNode config, String section, String field) {
        JsonNode node = config.get(section);
        if (node instanceof ObjectNode o && enabled(o) && o.path(field).asInt(0) <= 0) o.put(field, TRIES_CAP);
    }

    /** Enabled sections outside {@link #SECTIONS}, plus {@code outcomeRules} when present. Empty = playable. */
    public static List<String> unsupported(JsonNode config) {
        List<String> out = new ArrayList<>();
        if (config == null || !config.isObject()) {
            out.add("config");
            return out;
        }
        if (config.has("outcomeRules")) out.add("outcomeRules");
        Iterator<Map.Entry<String, JsonNode>> fields = config.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> f = fields.next();
            if (f.getValue() != null && f.getValue().isObject() && enabled(f.getValue()) && !SECTIONS.contains(f.getKey())) {
                out.add(f.getKey());
            }
        }
        boolean anyGame = false;
        for (String s : SECTIONS) if (!"timer".equals(s) && enabled(config.get(s))) anyGame = true;
        if (!anyGame) out.add("noGame");
        return out;
    }

    /** The session is over: every enabled section is done, or a section can no longer be won. */
    public static boolean over(JsonNode config, ObjectNode state) {
        if (mechanicsReady(config, state)) return true;
        return enabled(config.get("hiddenObject")) && state.path("hiddenObject").path("exhausted").asBoolean(false);
    }

    /** Every enabled section was won. Only meaningful once {@link #over} is true. */
    public static boolean won(JsonNode config, ObjectNode state) {
        for (String s : SECTIONS) {
            if ("timer".equals(s) || !enabled(config.get(s))) continue;
            if (!sectionWon(s, config, state)) return false;
        }
        return true;
    }

    static boolean sectionWon(String section, JsonNode config, ObjectNode state) {
        JsonNode node = state.path(section);
        switch (section) {
            case "hiddenObject":
                return node.path("foundIds").size() >= config.path("hiddenObject").path("hotspots").size();
            case "hangman":
                return state.path(HangmanGameplay.STATE_KEY).path("solved").asBoolean(false);
            case "estimate":
                return "HIT".equals(node.path("tier").asText(""));
            case "reaction":
                return node.path("passed").asBoolean(false);
            default:
                return node.path("passed").asBoolean(false) || node.path("lastCorrect").asBoolean(false);
        }
    }
}
