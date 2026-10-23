package com.chengyinhub.business.service.support.rpg.mechanism;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.service.support.JourneyRpg;
import com.chengyinhub.business.service.support.StoryVocabulary;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * C03 进度钟 / 动量 / 侧面:复用 {@link JourneyRpg} 与 F5 {@code TICK_CLOCK} /
 * {@code ADD_CONDITION},不另造钟或钱包。
 *
 * <p>作者在 journeyRules.rpg 里选配 clocks / momentum / aspects → 校验(JourneyRpg.normalize)
 * → 发布冻结 → 玩家显式 TICK_CLOCK / SPEND_MOMENTUM / INVOKE_ASPECT → 服务端按原 requestId
 * 落一次效果。
 */
public final class ProgressClockConsumer {
    public static final String ACTION_TICK = "TICK_CLOCK";
    public static final String ACTION_SPEND = "SPEND_MOMENTUM";
    public static final String ACTION_ASPECT = "INVOKE_ASPECT";

    private final MechanismRequestIdBinder binder;

    public ProgressClockConsumer(MechanismRequestIdBinder binder) {
        this.binder = binder == null ? new MechanismRequestIdBinder() : binder;
    }

    /** 作者选配:走现有 JourneyRpg 规范化,缺省 / 全关返回 enabled=false。 */
    public ObjectNode select(String journeyRulesJson) {
        JourneyRules rules = JourneyRules.parse(journeyRulesJson);
        return freezeFrom(rules);
    }

    public ObjectNode freezeFrom(JourneyRules rules) {
        ObjectNode out = MechanismRequestIdBinder.JSON.createObjectNode();
        JsonNode rpg = rules == null ? null : rules.getRpg();
        boolean clocks = rpg != null && rpg.path("clocks").isArray() && rpg.path("clocks").size() > 0;
        boolean momentum = JourneyRpg.momentumEnabled(rules);
        boolean aspects = rpg != null && rpg.path("aspects").isArray() && rpg.path("aspects").size() > 0;
        out.put("enabled", clocks || momentum || aspects);
        if (rpg != null && rpg.has("clocks")) out.set("clocks", rpg.get("clocks").deepCopy());
        if (rpg != null && rpg.has("momentum")) out.set("momentum", rpg.get("momentum").deepCopy());
        if (rpg != null && rpg.has("aspects")) out.set("aspects", rpg.get("aspects").deepCopy());
        return out;
    }

    /** 模拟:在 state 副本上推进,不写原局。 */
    public Map<String, Object> simulate(MechanismRequestIdBinder.Play play) {
        MechanismRequestIdBinder.Play copy = copyPlay(play);
        Map<String, Object> result = applyLive(copy);
        result.put("simulated", Boolean.TRUE);
        return result;
    }

    public Map<String, Object> apply(MechanismRequestIdBinder.Play play) {
        MechanismRequestIdBinder.requireExplicitChoice(play, MechanismRequestIdBinder.MECH_PROGRESS_CLOCK);
        MechanismRequestIdBinder.requireEnabled(play, MechanismRequestIdBinder.MECH_PROGRESS_CLOCK);
        JsonNode choice = play.getPlayerChoice();
        String action = MechanismRequestIdBinder.text(choice, "action");
        return binder.oneShot(play, MechanismRequestIdBinder.MECH_PROGRESS_CLOCK, action, () -> applyLive(play));
    }

    public Map<String, Object> recoverOriginal(MechanismRequestIdBinder.Play play) {
        return binder.recoverUnknown(play);
    }

    private Map<String, Object> applyLive(MechanismRequestIdBinder.Play play) {
        JsonNode choice = play.getPlayerChoice();
        String action = MechanismRequestIdBinder.text(choice, "action");
        TopicRouteEngine.State state = play.getState();
        JourneyRules rules = play.getRules();
        if (state == null) throw new IllegalArgumentException("本局状态不能为空");
        if (ACTION_TICK.equals(action)) return tick(play, state);
        if (ACTION_SPEND.equals(action)) return spend(play, rules, state);
        if (ACTION_ASPECT.equals(action)) return invokeAspect(play, state);
        throw new IllegalArgumentException("不支持的进度钟动作:" + action);
    }

    private Map<String, Object> tick(MechanismRequestIdBinder.Play play, TopicRouteEngine.State state) {
        String var = MechanismRequestIdBinder.text(play.getPlayerChoice(), "var");
        JsonNode clock = clockOf(play.getFrozen().path(MechanismRequestIdBinder.MECH_PROGRESS_CLOCK), var);
        if (clock == null) throw new IllegalArgumentException("冻结快照里没有这口进度钟:" + var);
        int segments = clock.path("segments").asInt();
        ObjectNode effect = MechanismRequestIdBinder.JSON.createObjectNode();
        effect.put("op", StoryVocabulary.TICK_CLOCK);
        effect.put("var", var);
        effect.put("value", 1);
        effect.put("max", segments);
        play.engine().applyEffects(MechanismRequestIdBinder.JSON.createArrayNode().add(effect), state);
        Number value = state.getVariables().get(var);
        int filled = value == null ? 0 : value.intValue();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("action", ACTION_TICK);
        result.put("var", var);
        result.put("value", Integer.valueOf(filled));
        result.put("segments", Integer.valueOf(segments));
        result.put("filled", Boolean.valueOf(filled >= segments));
        result.put("view", JourneyRpg.view(play.getRules(), state));
        return result;
    }

    private Map<String, Object> spend(MechanismRequestIdBinder.Play play, JourneyRules rules,
                                      TopicRouteEngine.State state) {
        if (!JourneyRpg.momentumEnabled(rules)) throw new IllegalArgumentException("主题未启用动量");
        int spend = play.getPlayerChoice().path("spend").asInt(0);
        int maxSpend = JourneyRpg.momentum(rules).path("maxSpend").asInt();
        if (spend < 1 || spend > maxSpend) {
            throw new IllegalArgumentException("动量花费须为 1 至 " + maxSpend);
        }
        int before = JourneyRpg.momentumOf(state);
        if (before < spend) throw new IllegalStateException("动量不足");
        int after = JourneyRpg.setMomentum(rules, state, before - spend);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("action", ACTION_SPEND);
        result.put("spent", Integer.valueOf(spend));
        result.put("value", Integer.valueOf(after));
        result.put("max", Integer.valueOf(JourneyRpg.momentum(rules).path("max").asInt()));
        result.put("view", JourneyRpg.view(rules, state));
        return result;
    }

    private Map<String, Object> invokeAspect(MechanismRequestIdBinder.Play play, TopicRouteEngine.State state) {
        String key = MechanismRequestIdBinder.text(play.getPlayerChoice(), "key");
        JsonNode aspect = aspectOf(play.getFrozen().path(MechanismRequestIdBinder.MECH_PROGRESS_CLOCK), key);
        if (aspect == null) throw new IllegalArgumentException("冻结快照里没有这个特质:" + key);
        ObjectNode effect = MechanismRequestIdBinder.JSON.createObjectNode();
        effect.put("op", StoryVocabulary.ADD_CONDITION);
        effect.put("value", key);
        play.engine().applyEffects(MechanismRequestIdBinder.JSON.createArrayNode().add(effect), state);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("action", ACTION_ASPECT);
        result.put("key", key);
        result.put("active", Boolean.valueOf(state.getStatusConditions().contains(key)));
        result.put("view", JourneyRpg.view(play.getRules(), state));
        return result;
    }

    private static JsonNode clockOf(JsonNode block, String var) {
        JsonNode clocks = block.path("clocks");
        if (!clocks.isArray()) return null;
        for (JsonNode clock : clocks) if (var.equals(clock.path("var").asText())) return clock;
        return null;
    }

    private static JsonNode aspectOf(JsonNode block, String key) {
        JsonNode aspects = block.path("aspects");
        if (!aspects.isArray()) return null;
        for (JsonNode aspect : aspects) if (key.equals(aspect.path("key").asText())) return aspect;
        return null;
    }

    private static MechanismRequestIdBinder.Play copyPlay(MechanismRequestIdBinder.Play play) {
        MechanismRequestIdBinder.Play copy = new MechanismRequestIdBinder.Play();
        copy.setRules(play.getRules());
        copy.setState(play.getState() == null ? null : play.getState().copy());
        copy.setCheck(play.getCheck());
        copy.setFrozen(play.getFrozen());
        copy.setPlayerChoice(play.getPlayerChoice());
        copy.setOriginalRequestId(play.getOriginalRequestId());
        copy.setDice(play.getDice());
        return copy;
    }
}
