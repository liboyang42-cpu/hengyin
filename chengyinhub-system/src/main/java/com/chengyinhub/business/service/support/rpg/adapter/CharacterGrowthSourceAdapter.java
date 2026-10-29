package com.chengyinhub.business.service.support.rpg.adapter;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.chengyinhub.business.service.support.JourneyRpg;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色成长投影与角色卡按原 requestId 回读。属性真源仍是局状态 / {@code ThemeCharacterService.card};
 * 技能点 / 专长只读已有 counter.* 与 statusConditions。归零恢复只套 {@link JourneyRules.Recovery}
 * 已发布的地板值,不发明经济数字。
 */
public class CharacterGrowthSourceAdapter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final TopicRouteEngine engine = new TopicRouteEngine();

    public Map<String, Object> prepare(RpgAdapterContext context) {
        Map<String, Object> fact = new LinkedHashMap<String, Object>();
        if (RpgAdapterSupport.inactive(context) || context.getSpec().getGrowth() == null) {
            fact.put("applied", Boolean.FALSE);
            return fact;
        }
        RpgAdapterSpec.Growth growth = context.getSpec().getGrowth();
        TopicRouteEngine.State state = context.getState();
        String skillKey = growth.getSkillPointKey();
        if (skillKey != null && !skillKey.isEmpty()) {
            Number value = state.getVariables().get(skillKey);
            fact.put("skillPoints", Integer.valueOf(value == null ? 0 : value.intValue()));
            fact.put("skillPointKey", skillKey);
        }
        List<String> feats = new ArrayList<String>();
        for (String feat : growth.getFeatConditions()) {
            if (feat != null && state.getStatusConditions().contains(feat)) feats.add(feat);
        }
        fact.put("feats", feats);
        fact.put("applied", Boolean.TRUE);
        context.getFacts().put("growth", fact);
        return fact;
    }

    /**
     * 休息类动作才把作者点名的临时键归零,并套用已发布的 hp/luck 地板。
     * 源键沿用 themeRuntime.sources,同一 originalRequestId 不二写。
     */
    public Map<String, Object> resetIfRecovery(RpgAdapterContext context) {
        Map<String, Object> fact = new LinkedHashMap<String, Object>();
        if (RpgAdapterSupport.inactive(context) || context.getSpec().getGrowth() == null
                || !context.getSpec().getGrowth().isResetOnRecovery()
                || context.getRecoveryKind() == null || context.getRecoveryKind().isEmpty()) {
            fact.put("applied", Boolean.FALSE);
            return fact;
        }
        String requestId = RpgAdapterSupport.blankToNull(context.getOriginalRequestId());
        if (requestId == null) {
            fact.put("applied", Boolean.FALSE);
            fact.put("requestId", null);
            fact.put("status", "WAITING_SOURCE");
            return fact;
        }
        String source = "growth-reset:" + requestId;
        if (context.getState().getThemeRuntime().path("sources").path(source).asBoolean(false)) {
            fact.put("applied", Boolean.FALSE);
            fact.put("replay", Boolean.TRUE);
            fact.put("requestId", requestId);
            return fact;
        }
        JourneyRules.Recovery recovery = context.getRules() == null ? null : context.getRules().getRecovery();
        if (recovery != null) {
            floor(context.getState(), "sys.hp", recovery.getStageRestHpFloor());
            floor(context.getState(), "sys.luck", recovery.getStageRestLuckFloor());
        }
        for (String key : context.getSpec().getGrowth().getResetKeys()) {
            if (key == null || key.isEmpty()) continue;
            context.getState().getVariables().put(key, Integer.valueOf(0));
        }
        context.getState().getThemeRuntime().with("sources").put(source, true);
        fact.put("applied", Boolean.TRUE);
        fact.put("requestId", requestId);
        context.getFacts().put("growthReset", fact);
        return fact;
    }

    /**
     * 角色卡回读:有原动作就从原回执取,requestId 保持原值;没有才用当前卡并写进本次 breakdown。
     * 调用方传入新的 retry id 也被丢掉,只认 {@link RpgAdapterContext#getOriginalRequestId()}。
     */
    public Map<String, Object> readCard(RpgAdapterContext context) {
        Map<String, Object> card;
        String requestId = context == null ? null : context.getOriginalRequestId();
        TopicRouteAction original = context == null ? null : context.getOriginalAction();
        if (original != null && original.getResponseJson() != null) {
            PlayCheckReceipt stored = readReceipt(original);
            if (stored != null && stored.getBreakdown() != null && stored.getBreakdown().get("characterCard") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> storedCard = new LinkedHashMap<String, Object>(
                        (Map<String, Object>) stored.getBreakdown().get("characterCard"));
                Object storedRequest = stored.getBreakdown().get("requestId");
                storedCard.put("requestId", storedRequest == null ? requestId : storedRequest);
                storedCard.put("replay", Boolean.TRUE);
                return storedCard;
            }
        }
        if (context != null && context.getLiveCharacterCard() != null) {
            card = new LinkedHashMap<String, Object>(context.getLiveCharacterCard());
        } else {
            card = project(context);
        }
        card.put("requestId", requestId);
        card.put("replay", Boolean.FALSE);
        if (context != null && context.getReceipt() != null && context.getSpec().active()) {
            RpgAdapterSupport.putExtra(context.getReceipt(), "characterCard", card);
            RpgAdapterSupport.putExtra(context.getReceipt(), "requestId", requestId);
        }
        return card;
    }

    public void contribute(RpgAdapterContext context, PlayCheckReceipt receipt) {
        if (RpgAdapterSupport.inactive(context)) return;
        Object fact = context.getFacts().get("growth");
        if (fact != null) RpgAdapterSupport.putExtra(receipt, "growth", fact);
        context.setReceipt(receipt);
        readCard(context);
    }

    private static void floor(TopicRouteEngine.State state, String key, int floor) {
        Number held = state.getVariables().get(key);
        int current = held == null ? 0 : held.intValue();
        state.getVariables().put(key, Integer.valueOf(Math.max(current, floor)));
    }

    private static Map<String, Object> project(RpgAdapterContext context) {
        Map<String, Object> card = new LinkedHashMap<String, Object>();
        if (context == null || context.getRules() == null) {
            card.put("enabled", Boolean.FALSE);
            return card;
        }
        card.put("rpg", JourneyRpg.view(context.getRules(), context.getState()));
        List<Map<String, Object>> attributes = new ArrayList<Map<String, Object>>();
        if (context.getState() != null) {
            for (JsonNode row : context.getRules().getAttributes()) {
                String key = row.path("key").asText("");
                if (key.isEmpty() || !row.path("visible").asBoolean(true)) continue;
                Number value = context.getState().getVariables().get(key);
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("stateKey", key);
                item.put("label", row.path("label").asText(key));
                item.put("value", Integer.valueOf(value == null ? row.path("initial").asInt(0) : value.intValue()));
                attributes.add(item);
            }
        }
        card.put("attributes", attributes);
        return card;
    }

    private static PlayCheckReceipt readReceipt(TopicRouteAction action) {
        try {
            return JSON.readValue(action.getResponseJson(), PlayCheckReceipt.class);
        } catch (Exception unusable) {
            return null;
        }
    }
}
