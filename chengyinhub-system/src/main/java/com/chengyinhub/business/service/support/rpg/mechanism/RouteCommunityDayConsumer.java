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
 * C03 路段 / 社区日:路段效果走现有 {@link TopicRouteEngine#applyEffects};
 * 社区日只认 {@link JourneyRpg} 已写入的 weekpart 快照(服务器时钟),不是客户端日历。
 */
public final class RouteCommunityDayConsumer {
    public static final String ACTION_TAKE_SEGMENT = "TAKE_SEGMENT";
    public static final String WEEKPART_WEEKEND = "weekend";

    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
    private static final int MAX_ROWS = 8;

    private final MechanismRequestIdBinder binder;

    public RouteCommunityDayConsumer(MechanismRequestIdBinder binder) {
        this.binder = binder == null ? new MechanismRequestIdBinder() : binder;
    }

    public ObjectNode select(JsonNode author) {
        ObjectNode out = MechanismRequestIdBinder.JSON.createObjectNode();
        if (author == null || author.isNull() || author.isMissingNode()) {
            out.put("enabled", false);
            return out;
        }
        if (!author.isObject()) throw new IllegalArgumentException("routeCommunityDay 必须是对象");
        author.fieldNames().forEachRemaining(name -> {
            if (!"segments".equals(name) && !"communityDay".equals(name) && !"enabled".equals(name)) {
                throw new IllegalArgumentException("routeCommunityDay 不支持的字段:" + name);
            }
        });
        ArrayNode segments = normalizeSegments(author.get("segments"));
        ObjectNode communityDay = normalizeCommunityDay(author.get("communityDay"));
        boolean on = author.path("enabled").asBoolean(segments.size() > 0);
        out.put("enabled", on && segments.size() > 0);
        if (segments.size() > 0) out.set("segments", segments);
        if (communityDay != null) out.set("communityDay", communityDay);
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
        MechanismRequestIdBinder.requireExplicitChoice(play, MechanismRequestIdBinder.MECH_ROUTE_COMMUNITY_DAY);
        MechanismRequestIdBinder.requireEnabled(play, MechanismRequestIdBinder.MECH_ROUTE_COMMUNITY_DAY);
        String action = MechanismRequestIdBinder.text(play.getPlayerChoice(), "action");
        return binder.oneShot(play, MechanismRequestIdBinder.MECH_ROUTE_COMMUNITY_DAY, action, () -> applyLive(play));
    }

    public Map<String, Object> recoverOriginal(MechanismRequestIdBinder.Play play) {
        return binder.recoverUnknown(play);
    }

    private Map<String, Object> applyLive(MechanismRequestIdBinder.Play play) {
        String action = MechanismRequestIdBinder.text(play.getPlayerChoice(), "action");
        if (!ACTION_TAKE_SEGMENT.equals(action)) {
            throw new IllegalArgumentException("路段必须显式 TAKE_SEGMENT");
        }
        JsonNode frozen = play.getFrozen().path(MechanismRequestIdBinder.MECH_ROUTE_COMMUNITY_DAY);
        String segmentId = MechanismRequestIdBinder.text(play.getPlayerChoice(), "segmentId");
        JsonNode segment = row(frozen.path("segments"), "id", segmentId);
        if (segment == null) throw new IllegalArgumentException("冻结快照里没有这段路:" + segmentId);
        if (segment.path("communityDay").asBoolean(false)) requireCommunityDay(play, frozen);
        play.engine().applyEffects(segment.path("effects"), play.getState());
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("action", ACTION_TAKE_SEGMENT);
        result.put("segmentId", segmentId);
        result.put("communityDay", Boolean.valueOf(segment.path("communityDay").asBoolean(false)));
        result.put("weekpart", play.getState().getEnvironment().get(JourneyRpg.ENV_WEEKPART));
        return result;
    }

    private void requireCommunityDay(MechanismRequestIdBinder.Play play, JsonNode frozen) {
        JsonNode community = frozen.path("communityDay");
        if (!community.path("enabled").asBoolean(false)) {
            throw new IllegalArgumentException("社区日路段需要作者启用 communityDay");
        }
        String expected = community.path("weekpart").asText(WEEKPART_WEEKEND);
        String actual = play.getState().getEnvironment().get(JourneyRpg.ENV_WEEKPART);
        if (actual == null || actual.isEmpty()) actual = JourneyRpg.ENV_UNKNOWN;
        ObjectNode condition = MechanismRequestIdBinder.JSON.createObjectNode();
        condition.put("op", StoryVocabulary.ENV_IN);
        condition.put("key", JourneyRpg.ENV_WEEKPART);
        condition.putArray("values").add(expected);
        boolean pass = play.engine().conditionsPass(MechanismRequestIdBinder.JSON.createArrayNode().add(condition),
                play.getState());
        if (!pass) {
            throw new IllegalStateException("社区日未到(服务器 weekpart=" + actual + ",需要 " + expected + ")");
        }
    }

    private static ArrayNode normalizeSegments(JsonNode source) {
        ArrayNode out = MechanismRequestIdBinder.JSON.createArrayNode();
        if (source == null || source.isNull()) return out;
        if (!source.isArray()) throw new IllegalArgumentException("routeCommunityDay.segments 必须是数组");
        if (source.size() > MAX_ROWS) throw new IllegalArgumentException("routeCommunityDay.segments 最多 " + MAX_ROWS + " 条");
        Set<String> seen = new LinkedHashSet<String>();
        for (JsonNode row : source) {
            if (!row.isObject()) throw new IllegalArgumentException("segment 条目必须是对象");
            row.fieldNames().forEachRemaining(name -> {
                if (!"id".equals(name) && !"label".equals(name) && !"effects".equals(name)
                        && !"communityDay".equals(name)) {
                    throw new IllegalArgumentException("segment 不支持的字段:" + name);
                }
            });
            String id = row.path("id").asText("");
            if (!ID.matcher(id).matches() || !seen.add(id)) {
                throw new IllegalArgumentException("segment.id 必须是不重复的小写代码");
            }
            JsonNode effects = row.get("effects");
            if (effects == null || !effects.isArray() || effects.isEmpty()) {
                throw new IllegalArgumentException("segment.effects 必须是非空效果数组");
            }
            for (JsonNode effect : effects) {
                String op = effect.path("op").asText("");
                if (StoryVocabulary.isEffect(op) || "SET".equals(op) || "INC".equals(op) || "ADD_TAG".equals(op)) {
                    if (StoryVocabulary.isEffect(op)) StoryVocabulary.validateEffect(effect, "segment.effects");
                    continue;
                }
                throw new IllegalArgumentException("segment.effects 不支持:" + op);
            }
            ObjectNode item = out.addObject();
            item.put("id", id);
            item.put("label", row.path("label").asText("").trim());
            item.put("communityDay", row.path("communityDay").asBoolean(false));
            item.set("effects", effects.deepCopy());
        }
        return out;
    }

    private static ObjectNode normalizeCommunityDay(JsonNode source) {
        if (source == null || source.isNull()) return null;
        if (!source.isObject()) throw new IllegalArgumentException("communityDay 必须是对象");
        source.fieldNames().forEachRemaining(name -> {
            if (!"enabled".equals(name) && !"weekpart".equals(name)) {
                throw new IllegalArgumentException("communityDay 不支持的字段:" + name);
            }
        });
        String weekpart = source.path("weekpart").asText(WEEKPART_WEEKEND);
        if (!WEEKPART_WEEKEND.equals(weekpart) && !"weekday".equals(weekpart)) {
            throw new IllegalArgumentException("communityDay.weekpart 只能是 weekend 或 weekday");
        }
        ObjectNode out = MechanismRequestIdBinder.JSON.createObjectNode();
        out.put("enabled", source.path("enabled").asBoolean(true));
        out.put("weekpart", weekpart);
        return out;
    }

    private static JsonNode row(JsonNode rows, String field, String id) {
        if (!rows.isArray() || id.isEmpty()) return null;
        for (JsonNode row : rows) if (id.equals(row.path(field).asText())) return row;
        return null;
    }
}
