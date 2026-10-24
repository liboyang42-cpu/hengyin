package com.chengyinhub.business.service.support.rpg.adapter;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.chengyinhub.business.service.support.JourneyRpg;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.chengyinhub.business.service.support.rpg.TopicEnvironmentService;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 {@link TopicEnvironmentService} 的服务器环境快照喂给现有 ENV_IN / mods 消费者,
 * 并按作者配置的截止时刻失效。失效后检定不得因假天气或客户端时钟成功。
 *
 * <p>哨兵 {@link #SENTINEL_EXPIRED} 不是 unknown:unknown 是天气源 fail-open,
 * 作者可以显式写进 ENV_IN values;expired 不在取值表里,ENV_IN 对 rain/unknown 都不成立。
 */
public class EnvironmentExpiryAdapter {
    /** 作者配置的环境窗口已过:ENV_IN 不得再按原取值或 unknown 放行。 */
    public static final String SENTINEL_EXPIRED = "expired";

    private final TopicEnvironmentService environmentService;
    private final Clock clock;

    public EnvironmentExpiryAdapter(TopicEnvironmentService environmentService, Clock clock) {
        this.environmentService = environmentService;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public Map<String, Object> apply(RpgAdapterContext context) {
        Map<String, Object> fact = new LinkedHashMap<String, Object>();
        fact.put("ignoredClientWeather", context == null ? null : context.getClientWeather());
        fact.put("ignoredClientNowMillis", context == null ? null : context.getClientNowMillis());
        fact.put("clockMillis", Long.valueOf(RpgAdapterSupport.serverNow(clock)));
        if (RpgAdapterSupport.inactive(context) || context.getSpec().getEnvironmentExpiry() == null) {
            fact.put("applied", Boolean.FALSE);
            return fact;
        }
        JourneyRules rules = context.getRules();
        TopicRouteEngine.State state = context.getState();
        CmsTopic topic = context.getTopic();
        if (!JourneyRpg.environmentEnabled(rules)) {
            fact.put("applied", Boolean.FALSE);
            fact.put("reason", "environment-disabled");
            return fact;
        }
        if (environmentService != null) {
            environmentService.refresh(topic, rules, state);
        }
        Map<String, String> snapshot = new LinkedHashMap<String, String>(state.getEnvironment());
        fact.put("serverSnapshot", snapshot);
        RpgAdapterSpec.EnvironmentExpiry expiry = context.getSpec().getEnvironmentExpiry();
        long now = RpgAdapterSupport.serverNow(clock);
        boolean expired = expiry.hasDeadline() && now >= expiry.getExpiresAtEpochMillis();
        fact.put("expired", Boolean.valueOf(expired));
        fact.put("expiresAtEpochMillis", Long.valueOf(expiry.getExpiresAtEpochMillis()));
        List<String> expiredKeys = new ArrayList<String>();
        if (expired) {
            List<String> keys = expiry.getKeys().isEmpty() ? JourneyRpg.environmentKeys(rules) : expiry.getKeys();
            for (String key : keys) {
                if (key == null || key.isEmpty()) continue;
                state.getEnvironment().put(key, SENTINEL_EXPIRED);
                expiredKeys.add(key);
            }
        }
        fact.put("expiredKeys", expiredKeys);
        fact.put("applied", Boolean.TRUE);
        context.getFacts().put("environment", fact);
        return fact;
    }

    public void contribute(RpgAdapterContext context, PlayCheckReceipt receipt) {
        if (RpgAdapterSupport.inactive(context)) return;
        Object fact = context.getFacts().get("environment");
        if (fact != null) RpgAdapterSupport.putExtra(receipt, "environment", fact);
    }
}
