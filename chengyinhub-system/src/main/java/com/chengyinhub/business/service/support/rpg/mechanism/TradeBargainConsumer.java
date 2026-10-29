package com.chengyinhub.business.service.support.rpg.mechanism;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.chengyinhub.business.service.support.AdvancedGameConfigValidator;
import com.chengyinhub.business.service.support.GameplayCheckEngine;
import com.chengyinhub.business.service.support.JourneyRpg;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * C03 魔鬼交易:复用 {@link JourneyRpg#bargainEnabled}、{@link AdvancedGameConfigValidator}
 * 的 check.bargain 形状、以及 {@link GameplayCheckEngine#roll} 的 bargainBonus。
 * 不另造骰子;代价只走现有 SET/INC/ADD_TAG/ADD_CONDITION/TICK_CLOCK。
 *
 * <p>玩家必须显式 ACCEPT;主题没开 rpg.bargain 或节点没配 check.bargain 则不能成交。
 */
public final class TradeBargainConsumer {
    public static final String ACTION_ACCEPT = "ACCEPT";
    public static final String ACTION_DECLINE = "DECLINE";

    private final MechanismRequestIdBinder binder;

    public TradeBargainConsumer(MechanismRequestIdBinder binder) {
        this.binder = binder == null ? new MechanismRequestIdBinder() : binder;
    }

    /** 作者选配:主题 rpg.bargain + 节点 check.bargain,形状走现有校验器。 */
    public ObjectNode select(JourneyRules rules, JsonNode check) {
        ObjectNode out = MechanismRequestIdBinder.JSON.createObjectNode();
        boolean theme = JourneyRpg.bargainEnabled(rules);
        JsonNode bargain = check == null ? null : check.path("bargain");
        boolean node = bargain != null && bargain.isObject() && !bargain.isEmpty();
        if (node) {
            String wrapped = "{\"schemaVersion\":1,\"check\":{\"enabled\":true,\"checkId\":\"c1\","
                    + "\"tier\":\"medium\",\"bargain\":" + bargain.toString() + "}}";
            new AdvancedGameConfigValidator().validateAndNormalize(wrapped);
        }
        out.put("enabled", theme && node);
        if (node) out.set("bargain", bargain.deepCopy());
        if (check != null) out.put("checkId", check.path("checkId").asText(""));
        return out;
    }

    public Map<String, Object> simulate(MechanismRequestIdBinder.Play play) {
        TopicRouteEngine.State snapshot = play.getState() == null ? null : play.getState().copy();
        MechanismRequestIdBinder.Play copy = new MechanismRequestIdBinder.Play();
        copy.setRules(play.getRules());
        copy.setState(snapshot);
        copy.setCheck(play.getCheck());
        copy.setFrozen(play.getFrozen());
        copy.setPlayerChoice(play.getPlayerChoice());
        copy.setDice(play.getDice());
        Map<String, Object> result = applyLive(copy);
        result.put("simulated", Boolean.TRUE);
        return result;
    }

    public Map<String, Object> apply(MechanismRequestIdBinder.Play play) {
        MechanismRequestIdBinder.requireExplicitChoice(play, MechanismRequestIdBinder.MECH_TRADE_BARGAIN);
        MechanismRequestIdBinder.requireEnabled(play, MechanismRequestIdBinder.MECH_TRADE_BARGAIN);
        String action = MechanismRequestIdBinder.text(play.getPlayerChoice(), "action");
        return binder.oneShot(play, MechanismRequestIdBinder.MECH_TRADE_BARGAIN, action, () -> applyLive(play));
    }

    public Map<String, Object> recoverOriginal(MechanismRequestIdBinder.Play play) {
        return binder.recoverUnknown(play);
    }

    private Map<String, Object> applyLive(MechanismRequestIdBinder.Play play) {
        String action = MechanismRequestIdBinder.text(play.getPlayerChoice(), "action");
        JsonNode bargain = play.getFrozen().path(MechanismRequestIdBinder.MECH_TRADE_BARGAIN).path("bargain");
        if (ACTION_DECLINE.equals(action)) {
            Map<String, Object> declined = new LinkedHashMap<String, Object>();
            declined.put("action", ACTION_DECLINE);
            declined.put("accepted", Boolean.FALSE);
            declined.put("bonus", Integer.valueOf(0));
            return declined;
        }
        if (!ACTION_ACCEPT.equals(action)) throw new IllegalArgumentException("交易必须显式 ACCEPT 或 DECLINE");
        if (!JourneyRpg.bargainEnabled(play.getRules())) {
            throw new IllegalArgumentException("主题未启用魔鬼交易");
        }
        int bonus = bargain.path("bonus").asInt(0);
        play.engine().applyEffects(bargain.path("effects"), play.getState());
        IntSupplier dice = play.getDice();
        if (dice == null) throw new IllegalStateException("检定必须使用服务端骰点,不得另造骰子");
        PlayCheckReceipt receipt = GameplayCheckEngine.roll(play.getCheck(), play.getRules(), play.getState(),
                dice, false, bonus);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("action", ACTION_ACCEPT);
        result.put("accepted", Boolean.TRUE);
        result.put("bonus", Integer.valueOf(bonus));
        result.put("label", bargain.path("label").asText(""));
        result.put("total", receipt.getTotal());
        result.put("success", receipt.getSuccess());
        result.put("breakdown", receipt.getBreakdown());
        Map<String, Object> bargainView = new LinkedHashMap<String, Object>();
        bargainView.put("label", bargain.path("label").asText(""));
        bargainView.put("bonus", Integer.valueOf(bonus));
        bargainView.put("accepted", Boolean.TRUE);
        result.put("bargain", bargainView);
        return result;
    }
}
