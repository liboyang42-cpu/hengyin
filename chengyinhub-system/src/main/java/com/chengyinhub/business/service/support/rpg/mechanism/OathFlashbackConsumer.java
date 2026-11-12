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
 * C03 誓言 / 闪回:誓言走现有 ADD_CONDITION;闪回只恢复作者点名的本局键,
 * 不是钱包回滚。密码必须来自作者冻结配置,保存/发布/判定同一份,不造全局口令。
 *
 * <p>11/14/32 当前补丁 WAITING_SOURCE(unpushed e16c2c5 / 5b92889 / 94a5d4a / a829a09),
 * 本消费者不改 RouteEffects / Runtime。
 */
public final class OathFlashbackConsumer {
    public static final String ACTION_SWEAR = "SWEAR";
    public static final String ACTION_FLASHBACK = "FLASHBACK";

    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
    private static final Pattern STATE_KEY = Pattern.compile("^(counter|clue|relation)\\.[a-z][a-z0-9_]{0,47}$");
    private static final int MAX_ROWS = 8;
    private static final int MAX_PASSWORD = 64;

    private final MechanismRequestIdBinder binder;

    public OathFlashbackConsumer(MechanismRequestIdBinder binder) {
        this.binder = binder == null ? new MechanismRequestIdBinder() : binder;
    }

    public ObjectNode select(JsonNode author) {
        ObjectNode out = MechanismRequestIdBinder.JSON.createObjectNode();
        if (author == null || author.isNull() || author.isMissingNode()) {
            out.put("enabled", false);
            return out;
        }
        if (!author.isObject()) throw new IllegalArgumentException("oathFlashback 必须是对象");
        author.fieldNames().forEachRemaining(name -> {
            if (!"oaths".equals(name) && !"flashbacks".equals(name) && !"enabled".equals(name)) {
                throw new IllegalArgumentException("oathFlashback 不支持的字段:" + name);
            }
        });
        ArrayNode oaths = normalizeOaths(author.get("oaths"));
        ArrayNode flashbacks = normalizeFlashbacks(author.get("flashbacks"));
        boolean on = author.path("enabled").asBoolean(oaths.size() > 0 || flashbacks.size() > 0);
        out.put("enabled", on && (oaths.size() > 0 || flashbacks.size() > 0));
        if (oaths.size() > 0) out.set("oaths", oaths);
        if (flashbacks.size() > 0) out.set("flashbacks", flashbacks);
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
        MechanismRequestIdBinder.requireExplicitChoice(play, MechanismRequestIdBinder.MECH_OATH_FLASHBACK);
        MechanismRequestIdBinder.requireEnabled(play, MechanismRequestIdBinder.MECH_OATH_FLASHBACK);
        String action = MechanismRequestIdBinder.text(play.getPlayerChoice(), "action");
        return binder.oneShot(play, MechanismRequestIdBinder.MECH_OATH_FLASHBACK, action, () -> applyLive(play));
    }

    public Map<String, Object> recoverOriginal(MechanismRequestIdBinder.Play play) {
        return binder.recoverUnknown(play);
    }

    private Map<String, Object> applyLive(MechanismRequestIdBinder.Play play) {
        String action = MechanismRequestIdBinder.text(play.getPlayerChoice(), "action");
        JsonNode frozen = play.getFrozen().path(MechanismRequestIdBinder.MECH_OATH_FLASHBACK);
        if (ACTION_SWEAR.equals(action)) return swear(play, frozen);
        if (ACTION_FLASHBACK.equals(action)) return flashback(play, frozen);
        throw new IllegalArgumentException("誓言/闪回必须显式 SWEAR 或 FLASHBACK");
    }

    private Map<String, Object> swear(MechanismRequestIdBinder.Play play, JsonNode frozen) {
        String key = MechanismRequestIdBinder.text(play.getPlayerChoice(), "oathKey");
        JsonNode oath = row(frozen.path("oaths"), "key", key);
        if (oath == null) throw new IllegalArgumentException("冻结快照里没有这条誓言:" + key);
        play.engine().applyEffects(oath.path("effects"), play.getState());
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("action", ACTION_SWEAR);
        result.put("oathKey", key);
        result.put("active", Boolean.valueOf(play.getState().getStatusConditions().contains(key)));
        return result;
    }

    private Map<String, Object> flashback(MechanismRequestIdBinder.Play play, JsonNode frozen) {
        String id = MechanismRequestIdBinder.text(play.getPlayerChoice(), "flashbackId");
        JsonNode flashback = row(frozen.path("flashbacks"), "id", id);
        if (flashback == null) throw new IllegalArgumentException("冻结快照里没有这条闪回:" + id);
        String required = flashback.path("password").asText("");
        if (!required.isEmpty()) {
            String supplied = MechanismRequestIdBinder.text(play.getPlayerChoice(), "password");
            if (!required.equals(supplied)) throw new IllegalArgumentException("闪回密码与作者配置不一致");
        }
        JsonNode snapshot = flashback.path("snapshot");
        Map<String, Number> restored = new LinkedHashMap<String, Number>();
        for (JsonNode keyNode : flashback.path("restoreKeys")) {
            String key = keyNode.asText();
            JsonNode value = snapshot.path(key);
            if (value.isNumber()) {
                play.getState().getVariables().put(key, value.numberValue());
                restored.put(key, value.numberValue());
            }
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("action", ACTION_FLASHBACK);
        result.put("flashbackId", id);
        result.put("restored", restored);
        result.put("passwordChecked", Boolean.valueOf(!required.isEmpty()));
        return result;
    }

    /**
     * 发布冻结时把当前局点名键拍进 snapshot,之后玩家 FLASHBACK 只恢复这份冻结值。
     */
    public ObjectNode freezeWithSnapshot(ObjectNode selected, TopicRouteEngine.State state) {
        ObjectNode frozen = selected == null ? MechanismRequestIdBinder.disabled() : selected.deepCopy();
        JsonNode flashbacks = frozen.path("flashbacks");
        if (!flashbacks.isArray() || state == null) return frozen;
        ArrayNode next = MechanismRequestIdBinder.JSON.createArrayNode();
        for (JsonNode row : flashbacks) {
            ObjectNode copy = row.deepCopy();
            ObjectNode snapshot = copy.putObject("snapshot");
            for (JsonNode keyNode : copy.path("restoreKeys")) {
                String key = keyNode.asText();
                Number value = state.getVariables().get(key);
                if (value != null) snapshot.put(key, value.doubleValue());
            }
            next.add(copy);
        }
        frozen.set("flashbacks", next);
        return frozen;
    }

    private static ArrayNode normalizeOaths(JsonNode source) {
        ArrayNode out = MechanismRequestIdBinder.JSON.createArrayNode();
        if (source == null || source.isNull()) return out;
        if (!source.isArray()) throw new IllegalArgumentException("oathFlashback.oaths 必须是数组");
        if (source.size() > MAX_ROWS) throw new IllegalArgumentException("oathFlashback.oaths 最多 " + MAX_ROWS + " 条");
        Set<String> seen = new LinkedHashSet<String>();
        for (JsonNode row : source) {
            if (!row.isObject()) throw new IllegalArgumentException("oath 条目必须是对象");
            row.fieldNames().forEachRemaining(name -> {
                if (!"key".equals(name) && !"label".equals(name) && !"effects".equals(name)) {
                    throw new IllegalArgumentException("oath 不支持的字段:" + name);
                }
            });
            String key = row.path("key").asText("");
            if (!ID.matcher(key).matches() || !seen.add(key)) {
                throw new IllegalArgumentException("oath.key 必须是不重复的小写代码");
            }
            JsonNode effects = row.get("effects");
            if (effects == null || !effects.isArray() || effects.isEmpty()) {
                effects = MechanismRequestIdBinder.JSON.createArrayNode()
                        .add(MechanismRequestIdBinder.JSON.createObjectNode()
                                .put("op", StoryVocabulary.ADD_CONDITION).put("value", key));
            }
            for (JsonNode effect : effects) StoryVocabulary.validateEffect(effect, "oath.effects");
            ObjectNode item = out.addObject();
            item.put("key", key);
            item.put("label", requireLabel(row, "oath"));
            item.set("effects", effects.deepCopy());
        }
        return out;
    }

    private static ArrayNode normalizeFlashbacks(JsonNode source) {
        ArrayNode out = MechanismRequestIdBinder.JSON.createArrayNode();
        if (source == null || source.isNull()) return out;
        if (!source.isArray()) throw new IllegalArgumentException("oathFlashback.flashbacks 必须是数组");
        if (source.size() > MAX_ROWS) throw new IllegalArgumentException("oathFlashback.flashbacks 最多 " + MAX_ROWS + " 条");
        Set<String> seen = new LinkedHashSet<String>();
        for (JsonNode row : source) {
            if (!row.isObject()) throw new IllegalArgumentException("flashback 条目必须是对象");
            row.fieldNames().forEachRemaining(name -> {
                if (!"id".equals(name) && !"label".equals(name) && !"restoreKeys".equals(name)
                        && !"password".equals(name) && !"snapshot".equals(name)) {
                    throw new IllegalArgumentException("flashback 不支持的字段:" + name);
                }
            });
            String id = row.path("id").asText("");
            if (!ID.matcher(id).matches() || !seen.add(id)) {
                throw new IllegalArgumentException("flashback.id 必须是不重复的小写代码");
            }
            JsonNode keys = row.get("restoreKeys");
            if (keys == null || !keys.isArray() || keys.isEmpty() || keys.size() > MAX_ROWS) {
                throw new IllegalArgumentException("flashback.restoreKeys 必须是 1 至 " + MAX_ROWS + " 个本局键");
            }
            ArrayNode restore = MechanismRequestIdBinder.JSON.createArrayNode();
            Set<String> keySeen = new LinkedHashSet<String>();
            for (JsonNode key : keys) {
                if (!key.isTextual() || !STATE_KEY.matcher(key.asText()).matches() || !keySeen.add(key.asText())) {
                    throw new IllegalArgumentException("flashback.restoreKeys 只能是不重复的 counter/clue/relation 键");
                }
                restore.add(key.asText());
            }
            String password = row.path("password").asText("").trim();
            if (password.length() > MAX_PASSWORD) {
                throw new IllegalArgumentException("flashback.password 不能超过 " + MAX_PASSWORD + " 字");
            }
            ObjectNode item = out.addObject();
            item.put("id", id);
            item.put("label", requireLabel(row, "flashback"));
            item.set("restoreKeys", restore);
            if (!password.isEmpty()) item.put("password", password);
            if (row.has("snapshot") && row.get("snapshot").isObject()) {
                item.set("snapshot", row.get("snapshot").deepCopy());
            }
        }
        return out;
    }

    private static String requireLabel(JsonNode row, String path) {
        String label = row.path("label").asText("").trim();
        if (label.isEmpty() || label.length() > JourneyRpg.MAX_LABEL) {
            throw new IllegalArgumentException(path + ".label 必填且不超过 " + JourneyRpg.MAX_LABEL + " 字");
        }
        return label;
    }

    private static JsonNode row(JsonNode rows, String field, String id) {
        if (!rows.isArray() || id.isEmpty()) return null;
        for (JsonNode row : rows) if (id.equals(row.path(field).asText())) return row;
        return null;
    }
}
