package com.chengyinhub.business.service.support.rpg.adapter;

import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 原资源写入:只走 {@link TopicRouteEngine#applyEffects} 已有 ADD_ITEM / GRANT_XP / GRANT_BADGE /
 * SET / INC,幂等键仍是 themeRuntime.sources 与 storyGrants。不另造钱包或请求账。
 *
 * <p>同一 originalRequestId 再入只是再次 apply;引擎侧 source 已存在则跳过,不会二发。
 */
public class ResourceSourceAdapter {
    private final TopicRouteEngine engine = new TopicRouteEngine();

    public Map<String, Object> writeOriginal(RpgAdapterContext context) {
        Map<String, Object> fact = new LinkedHashMap<String, Object>();
        if (RpgAdapterSupport.inactive(context) || context.getSpec().getResources() == null
                || context.getReceipt() == null || !context.isWriteResources()) {
            fact.put("applied", Boolean.FALSE);
            return fact;
        }
        fact.put("requestId", context.getOriginalRequestId());
        JsonNode effects = Boolean.TRUE.equals(context.getReceipt().getSuccess())
                ? context.getSpec().getResources().getSuccessEffects()
                : context.getSpec().getResources().getFailEffects();
        if (effects == null || !effects.isArray() || effects.isEmpty()) {
            fact.put("applied", Boolean.FALSE);
            fact.put("emptyEffects", Boolean.TRUE);
            context.getFacts().put("resources", fact);
            return fact;
        }
        int sourcesBefore = context.getState().getThemeRuntime().path("sources").size();
        int grantsBefore = context.getState().getStoryGrants().size();
        engine.applyEffects(effects, context.getState());
        fact.put("sourcesDelta", Integer.valueOf(context.getState().getThemeRuntime().path("sources").size() - sourcesBefore));
        fact.put("grantsDelta", Integer.valueOf(context.getState().getStoryGrants().size() - grantsBefore));
        fact.put("applied", Boolean.TRUE);
        context.getFacts().put("resources", fact);
        return fact;
    }

    public void contribute(RpgAdapterContext context, PlayCheckReceipt receipt) {
        if (RpgAdapterSupport.inactive(context)) return;
        Object fact = context.getFacts().get("resources");
        if (fact != null) RpgAdapterSupport.putExtra(receipt, "resources", fact);
        if (context.getOriginalRequestId() != null) {
            RpgAdapterSupport.putExtra(receipt, "requestId", context.getOriginalRequestId());
        }
    }
}
