package com.chengyinhub.business.service.support.rpg.mechanism;

import com.chengyinhub.business.service.support.JourneyRpg;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * C03 线索 / 秘密目标:线索写入走现有 {@link TopicRouteEngine#applyEffects}
 * (含 clue.h_* 只增不减);秘密目标只判定作者 {@code when} 是否成立。
 *
 * <p>阵营 / 领地 / 秘密目标经济值未确认:拒绝金额字段,不编造奖励。
 */
public final class ClueSecretGoalConsumer {
    public static final String ACTION_GAIN_CLUE = "GAIN_CLUE";
    public static final String ACTION_CHECK_GOAL = "CHECK_GOAL";

    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
    private static final Pattern CLUE_VAR = Pattern.compile("^clue\\.[a-z][a-z0-9_]{0,47}$");
    private static final int MAX_ROWS = 8;
    private static final Set<String> FORBIDDEN_ECONOMY = set("reward", "amount", "xp", "points", "coin",
            "price", "factionReward", "territory", "economy");

    private final MechanismRequestIdBinder binder;

    public ClueSecretGoalConsumer(MechanismRequestIdBinder binder) {
        this.binder = binder == null ? new MechanismRequestIdBinder() : binder;
    }

    public ObjectNode select(JsonNode author) {
        ObjectNode out = MechanismRequestIdBinder.JSON.createObjectNode();
        if (author == null || author.isNull() || author.isMissingNode()) {
            out.put("enabled", false);
            return out;
        }
        if (!author.isObject()) throw new IllegalArgumentException("clueSecretGoal 必须是对象");
        rejectEconomy(author, "clueSecretGoal");
        author.fieldNames().forEachRemaining(name -> {
            if (!"clues".equals(name) && !"secretGoals".equals(name) && !"enabled".equals(name)) {
                if (FORBIDDEN_ECONOMY.contains(name)) {
                    throw new IllegalArgumentException("阵营/领地/秘密目标经济值未确认,不收字段:" + name);
                }
                throw new IllegalArgumentException("clueSecretGoal 不支持的字段:" + name);
            }
        });
        ArrayNode clues = normalizeClues(author.get("clues"));
        ArrayNode goals = normalizeGoals(author.get("secretGoals"));
        boolean on = author.path("enabled").asBoolean(clues.size() > 0 || goals.size() > 0);
        out.put("enabled", on && (clues.size() > 0 || goals.size() > 0));
        if (clues.size() > 0) out.set("clues", clues);
        if (goals.size() > 0) out.set("secretGoals", goals);
        return out;
    }

    public Map<String, Object> simulate(MechanismRequestIdBinder.Play play) {
        TopicRouteEngine.State snapshot = play.getState() == null ? null : play.getState().copy();
        MechanismRequestIdBinder.Play copy = new MechanismRequestIdBinder.Play();
        copy.setRules(play.getRules());
        copy.setState(snapshot);
        copy.setFrozen(play.getFrozen());
        copy.setPlayerChoice(play.getPlayerChoice());
        Map<String, Object> result = applyLive(copy);
        result.put("simulated", Boolean.TRUE);
        return result;
    }

    public Map<String, Object> apply(MechanismRequestIdBinder.Play play) {
        MechanismRequestIdBinder.requireExplicitChoice(play, MechanismRequestIdBinder.MECH_CLUE_SECRET_GOAL);
        MechanismRequestIdBinder.requireEnabled(play, MechanismRequestIdBinder.MECH_CLUE_SECRET_GOAL);
        String action = MechanismRequestIdBinder.text(play.getPlayerChoice(), "action");
        return binder.oneShot(play, MechanismRequestIdBinder.MECH_CLUE_SECRET_GOAL, action, () -> applyLive(play));
    }

    public Map<String, Object> recoverOriginal(MechanismRequestIdBinder.Play play) {
        return binder.recoverUnknown(play);
    }

    private Map<String, Object> applyLive(MechanismRequestIdBinder.Play play) {
        String action = MechanismRequestIdBinder.text(play.getPlayerChoice(), "action");
        JsonNode frozen = play.getFrozen().path(MechanismRequestIdBinder.MECH_CLUE_SECRET_GOAL);
        if (ACTION_GAIN_CLUE.equals(action)) return gainClue(play, frozen);
        if (ACTION_CHECK_GOAL.equals(action)) return checkGoal(play, frozen);
        throw new IllegalArgumentException("线索/秘密目标必须显式 GAIN_CLUE 或 CHECK_GOAL");
    }

    private Map<String, Object> gainClue(MechanismRequestIdBinder.Play play, JsonNode frozen) {
        String var = MechanismRequestIdBinder.text(play.getPlayerChoice(), "var");
        JsonNode clue = row(frozen.path("clues"), "var", var);
        if (clue == null) throw new IllegalArgumentException("冻结快照里没有这条线索:" + var);
        ObjectNode effect = MechanismRequestIdBinder.JSON.createObjectNode();
        effect.put("op", "SET");
        effect.put("var", var);
        effect.put("value", 1);
        play.engine().applyEffects(MechanismRequestIdBinder.JSON.createArrayNode().add(effect), play.getState());
        Number held = play.getState().getVariables().get(var);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("action", ACTION_GAIN_CLUE);
        result.put("var", var);
        result.put("value", Integer.valueOf(held == null ? 0 : held.intValue()));
        return result;
    }

    private Map<String, Object> checkGoal(MechanismRequestIdBinder.Play play, JsonNode frozen) {
        String key = MechanismRequestIdBinder.text(play.getPlayerChoice(), "goalKey");
        JsonNode goal = row(frozen.path("secretGoals"), "key", key);
        if (goal == null) throw new IllegalArgumentException("冻结快照里没有这个秘密目标:" + key);
        boolean met = play.engine().conditionsPass(wrapWhen(goal.get("when")), play.getState());
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("action", ACTION_CHECK_GOAL);
        result.put("goalKey", key);
        result.put("met", Boolean.valueOf(met));
        return result;
    }

    private static ArrayNode normalizeClues(JsonNode source) {
        ArrayNode out = MechanismRequestIdBinder.JSON.createArrayNode();
        if (source == null || source.isNull()) return out;
        if (!source.isArray()) throw new IllegalArgumentException("clueSecretGoal.clues 必须是数组");
        if (source.size() > MAX_ROWS) throw new IllegalArgumentException("clueSecretGoal.clues 最多 " + MAX_ROWS + " 条");
        Set<String> seen = new LinkedHashSet<String>();
        for (JsonNode row : source) {
            if (!row.isObject()) throw new IllegalArgumentException("clue 条目必须是对象");
            rejectEconomy(row, "clue");
            row.fieldNames().forEachRemaining(name -> {
                if (!"var".equals(name) && !"label".equals(name)) {
                    throw new IllegalArgumentException("clue 不支持的字段:" + name);
                }
            });
            String var = row.path("var").asText("");
            if (!CLUE_VAR.matcher(var).matches() || !seen.add(var)) {
                throw new IllegalArgumentException("clue.var 必须是不重复的 clue. 键");
            }
            ObjectNode item = out.addObject();
            item.put("var", var);
            item.put("label", requireLabel(row, "clue"));
        }
        return out;
    }

    private static ArrayNode normalizeGoals(JsonNode source) {
        ArrayNode out = MechanismRequestIdBinder.JSON.createArrayNode();
        if (source == null || source.isNull()) return out;
        if (!source.isArray()) throw new IllegalArgumentException("clueSecretGoal.secretGoals 必须是数组");
        if (source.size() > MAX_ROWS) throw new IllegalArgumentException("clueSecretGoal.secretGoals 最多 " + MAX_ROWS + " 条");
        Set<String> seen = new LinkedHashSet<String>();
        for (JsonNode row : source) {
            if (!row.isObject()) throw new IllegalArgumentException("secretGoal 条目必须是对象");
            rejectEconomy(row, "secretGoal");
            row.fieldNames().forEachRemaining(name -> {
                if (!"key".equals(name) && !"label".equals(name) && !"when".equals(name)) {
                    throw new IllegalArgumentException("secretGoal 不支持的字段:" + name);
                }
            });
            String key = row.path("key").asText("");
            if (!ID.matcher(key).matches() || !seen.add(key)) {
                throw new IllegalArgumentException("secretGoal.key 必须是不重复的小写代码");
            }
            JsonNode when = row.get("when");
            if (when == null || when.isNull()) throw new IllegalArgumentException("secretGoal.when 必填");
            ObjectNode item = out.addObject();
            item.put("key", key);
            item.put("label", requireLabel(row, "secretGoal"));
            item.set("when", when.deepCopy());
        }
        return out;
    }

    private static void rejectEconomy(JsonNode row, String path) {
        row.fieldNames().forEachRemaining(name -> {
            if (FORBIDDEN_ECONOMY.contains(name)) {
                throw new IllegalArgumentException("阵营/领地/秘密目标经济值未确认,不收字段:" + path + "." + name);
            }
        });
    }

    private static String requireLabel(JsonNode row, String path) {
        String label = row.path("label").asText("").trim();
        if (label.isEmpty() || label.length() > JourneyRpg.MAX_LABEL) {
            throw new IllegalArgumentException(path + ".label 必填且不超过 " + JourneyRpg.MAX_LABEL + " 字");
        }
        return label;
    }

    private static JsonNode wrapWhen(JsonNode when) {
        if (when != null && when.isArray()) return when;
        return MechanismRequestIdBinder.JSON.createArrayNode().add(when);
    }

    private static JsonNode row(JsonNode rows, String field, String id) {
        if (!rows.isArray() || id.isEmpty()) return null;
        for (JsonNode row : rows) if (id.equals(row.path(field).asText())) return row;
        return null;
    }

    private static Set<String> set(String... values) {
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<String>(java.util.Arrays.asList(values)));
    }
}
