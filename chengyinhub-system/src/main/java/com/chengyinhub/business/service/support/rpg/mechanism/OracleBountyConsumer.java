package com.chengyinhub.business.service.support.rpg.mechanism;

import com.chengyinhub.business.service.support.JourneyRpg;
import com.chengyinhub.business.service.support.StoryVocabulary;
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
 * C03 神谕 / 赏金:作者表驱动,玩家显式 CONSULT / CLAIM。
 * 神谕不是第二套骰子(查表 + 现有效果);赏金不是商家拓展金
 * ({@code MerchantReferralBounty}),只推进作者配的 counter.* 钟。
 */
public final class OracleBountyConsumer {
    public static final String ACTION_CONSULT = "CONSULT";
    public static final String ACTION_CLAIM = "CLAIM";

    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
    private static final Pattern CLOCK_VAR = Pattern.compile("^counter\\.[a-z][a-z0-9_]{0,47}$");
    private static final int MAX_ROWS = 8;

    private final MechanismRequestIdBinder binder;

    public OracleBountyConsumer(MechanismRequestIdBinder binder) {
        this.binder = binder == null ? new MechanismRequestIdBinder() : binder;
    }

    public ObjectNode select(JsonNode author) {
        ObjectNode out = MechanismRequestIdBinder.JSON.createObjectNode();
        if (author == null || author.isNull() || author.isMissingNode()) {
            out.put("enabled", false);
            return out;
        }
        if (!author.isObject()) throw new IllegalArgumentException("oracleBounty 必须是对象");
        author.fieldNames().forEachRemaining(name -> {
            if (!"oracles".equals(name) && !"bounties".equals(name) && !"enabled".equals(name)) {
                throw new IllegalArgumentException("oracleBounty 不支持的字段:" + name);
            }
        });
        ArrayNode oracles = normalizeOracles(author.get("oracles"));
        ArrayNode bounties = normalizeBounties(author.get("bounties"));
        boolean on = author.path("enabled").asBoolean(oracles.size() > 0 || bounties.size() > 0);
        out.put("enabled", on && (oracles.size() > 0 || bounties.size() > 0));
        if (oracles.size() > 0) out.set("oracles", oracles);
        if (bounties.size() > 0) out.set("bounties", bounties);
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
        MechanismRequestIdBinder.requireExplicitChoice(play, MechanismRequestIdBinder.MECH_ORACLE_BOUNTY);
        MechanismRequestIdBinder.requireEnabled(play, MechanismRequestIdBinder.MECH_ORACLE_BOUNTY);
        String action = MechanismRequestIdBinder.text(play.getPlayerChoice(), "action");
        return binder.oneShot(play, MechanismRequestIdBinder.MECH_ORACLE_BOUNTY, action, () -> applyLive(play));
    }

    public Map<String, Object> recoverOriginal(MechanismRequestIdBinder.Play play) {
        return binder.recoverUnknown(play);
    }

    private Map<String, Object> applyLive(MechanismRequestIdBinder.Play play) {
        String action = MechanismRequestIdBinder.text(play.getPlayerChoice(), "action");
        JsonNode frozen = play.getFrozen().path(MechanismRequestIdBinder.MECH_ORACLE_BOUNTY);
        if (ACTION_CONSULT.equals(action)) return consult(play, frozen);
        if (ACTION_CLAIM.equals(action)) return claim(play, frozen);
        throw new IllegalArgumentException("神谕/赏金必须显式 CONSULT 或 CLAIM");
    }

    private Map<String, Object> consult(MechanismRequestIdBinder.Play play, JsonNode frozen) {
        String oracleId = MechanismRequestIdBinder.text(play.getPlayerChoice(), "oracleId");
        JsonNode oracle = row(frozen.path("oracles"), "id", oracleId);
        if (oracle == null) throw new IllegalArgumentException("冻结快照里没有这条神谕:" + oracleId);
        play.engine().applyEffects(oracle.path("effects"), play.getState());
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("action", ACTION_CONSULT);
        result.put("oracleId", oracleId);
        result.put("label", oracle.path("label").asText(""));
        result.put("revelation", oracle.path("revelation").asText(""));
        return result;
    }

    private Map<String, Object> claim(MechanismRequestIdBinder.Play play, JsonNode frozen) {
        String bountyId = MechanismRequestIdBinder.text(play.getPlayerChoice(), "bountyId");
        JsonNode bounty = row(frozen.path("bounties"), "id", bountyId);
        if (bounty == null) throw new IllegalArgumentException("冻结快照里没有这笔赏金:" + bountyId);
        String var = bounty.path("var").asText();
        int segments = bounty.path("segments").asInt();
        ObjectNode effect = MechanismRequestIdBinder.JSON.createObjectNode();
        effect.put("op", StoryVocabulary.TICK_CLOCK);
        effect.put("var", var);
        effect.put("value", bounty.path("ticks").asInt(1));
        effect.put("max", segments);
        play.engine().applyEffects(MechanismRequestIdBinder.JSON.createArrayNode().add(effect), play.getState());
        Number value = play.getState().getVariables().get(var);
        int filled = value == null ? 0 : value.intValue();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("action", ACTION_CLAIM);
        result.put("bountyId", bountyId);
        result.put("var", var);
        result.put("value", Integer.valueOf(filled));
        result.put("segments", Integer.valueOf(segments));
        result.put("claimed", Boolean.valueOf(filled >= segments));
        return result;
    }

    private static ArrayNode normalizeOracles(JsonNode source) {
        ArrayNode out = MechanismRequestIdBinder.JSON.createArrayNode();
        if (source == null || source.isNull()) return out;
        if (!source.isArray()) throw new IllegalArgumentException("oracleBounty.oracles 必须是数组");
        if (source.size() > MAX_ROWS) throw new IllegalArgumentException("oracleBounty.oracles 最多 " + MAX_ROWS + " 条");
        Set<String> seen = new LinkedHashSet<String>();
        for (JsonNode row : source) {
            if (!row.isObject()) throw new IllegalArgumentException("oracle 条目必须是对象");
            row.fieldNames().forEachRemaining(name -> {
                if (!"id".equals(name) && !"label".equals(name) && !"revelation".equals(name) && !"effects".equals(name)) {
                    throw new IllegalArgumentException("oracle 不支持的字段:" + name);
                }
            });
            String id = row.path("id").asText("");
            if (!ID.matcher(id).matches() || !seen.add(id)) {
                throw new IllegalArgumentException("oracle.id 必须是不重复的小写代码");
            }
            String label = row.path("label").asText("").trim();
            if (label.isEmpty() || label.length() > JourneyRpg.MAX_LABEL) {
                throw new IllegalArgumentException("oracle.label 必填且不超过 " + JourneyRpg.MAX_LABEL + " 字");
            }
            JsonNode effects = row.get("effects");
            if (effects == null || !effects.isArray() || effects.isEmpty()) {
                throw new IllegalArgumentException("oracle.effects 必须是非空效果数组");
            }
            for (JsonNode effect : effects) validatePlayEffect(effect, "oracle.effects");
            ObjectNode item = out.addObject();
            item.put("id", id);
            item.put("label", label);
            if (row.hasNonNull("revelation")) item.put("revelation", row.path("revelation").asText());
            item.set("effects", effects.deepCopy());
        }
        return out;
    }

    private static ArrayNode normalizeBounties(JsonNode source) {
        ArrayNode out = MechanismRequestIdBinder.JSON.createArrayNode();
        if (source == null || source.isNull()) return out;
        if (!source.isArray()) throw new IllegalArgumentException("oracleBounty.bounties 必须是数组");
        if (source.size() > MAX_ROWS) throw new IllegalArgumentException("oracleBounty.bounties 最多 " + MAX_ROWS + " 条");
        Set<String> seen = new LinkedHashSet<String>();
        for (JsonNode row : source) {
            if (!row.isObject()) throw new IllegalArgumentException("bounty 条目必须是对象");
            row.fieldNames().forEachRemaining(name -> {
                if (!"id".equals(name) && !"label".equals(name) && !"var".equals(name)
                        && !"ticks".equals(name) && !"segments".equals(name)) {
                    throw new IllegalArgumentException("bounty 不支持的字段:" + name + "(赏金经济值未确认,不收金额)");
                }
            });
            String id = row.path("id").asText("");
            if (!ID.matcher(id).matches() || !seen.add(id)) {
                throw new IllegalArgumentException("bounty.id 必须是不重复的小写代码");
            }
            String var = row.path("var").asText("");
            if (!CLOCK_VAR.matcher(var).matches()) {
                throw new IllegalArgumentException("bounty.var 必须是 counter. 进度钟");
            }
            int segments = intIn(row, "segments", StoryVocabulary.MIN_CLOCK_SEGMENTS, StoryVocabulary.MAX_CLOCK_SEGMENTS);
            int ticks = intIn(row, "ticks", 1, segments);
            ObjectNode item = out.addObject();
            item.put("id", id);
            item.put("label", row.path("label").asText("").trim());
            item.put("var", var);
            item.put("ticks", ticks);
            item.put("segments", segments);
        }
        return out;
    }

    private static JsonNode row(JsonNode rows, String field, String id) {
        if (!rows.isArray() || id.isEmpty()) return null;
        for (JsonNode row : rows) if (id.equals(row.path(field).asText())) return row;
        return null;
    }

    private static void validatePlayEffect(JsonNode effect, String path) {
        String op = effect.path("op").asText("");
        if (StoryVocabulary.isEffect(op)) {
            StoryVocabulary.validateEffect(effect, path);
            return;
        }
        if ("SET".equals(op) || "INC".equals(op) || "ADD_TAG".equals(op)) return;
        throw new IllegalArgumentException(path + " 不支持:" + op);
    }

    private static int intIn(JsonNode row, String field, int min, int max) {
        JsonNode value = row.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.asInt() < min || value.asInt() > max) {
            throw new IllegalArgumentException("bounty." + field + " 须为 " + min + " 至 " + max + " 的整数");
        }
        return value.asInt();
    }
}
