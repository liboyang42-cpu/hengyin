package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.marketing.MarketingRevenueRule;
import com.chengyinhub.business.exception.MarketingRevenueException;

/**
 * 规则键编码：{@code topic:<id>:s<scenario>} / {@code activity:<id>:s<scenario>} / {@code global:s<scenario>}。
 *
 * <p>适用范围编进键里，避免再造一张 scope 表，也让解析变成确定性回退链：
 * 具体目标优先，其次全局默认；命中第一个即返回，不存在"两个具体范围同时命中"的歧义。
 */
public final class RevenueRuleKeys {

    private RevenueRuleKeys() {
    }

    public static String of(Integer targetType, Long targetId, Integer scenario) {
        int s = requireScenario(scenario);
        if (targetType == null || targetType == 0) {
            return "global:s" + s;
        }
        if (targetId == null || targetId <= 0) {
            throw MarketingRevenueException.badRequest("TARGET_ID_REQUIRED", "指定适用范围时必须给出目标 id");
        }
        if (targetType == 1) {
            return "topic:" + targetId + ":s" + s;
        }
        if (targetType == 2) {
            return "activity:" + targetId + ":s" + s;
        }
        throw MarketingRevenueException.badRequest("TARGET_TYPE_INVALID", "适用范围只能是 0/1/2");
    }

    /**
     * 解析候选键，按优先级从具体到全局。第一个存在且 ACTIVE 的规则即生效。
     */
    public static java.util.List<String> resolutionChain(Integer ownerType, Long ownerId, Integer scenario) {
        int s = requireScenario(scenario);
        java.util.List<String> chain = new java.util.ArrayList<>(3);
        if (ownerType != null && ownerId != null && ownerId > 0) {
            if (ownerType == 1) {
                chain.add("topic:" + ownerId + ":s" + s);
            } else if (ownerType == 2) {
                chain.add("activity:" + ownerId + ":s" + s);
            }
        }
        chain.add("global:s" + s);
        return chain;
    }

    /** 从 ruleKey 反解范围，供后台展示；格式不合法返回 null 而不抛，避免影响列表读取。 */
    public static String[] parseScope(String ruleKey) {
        if (ruleKey == null) {
            return null;
        }
        String[] parts = ruleKey.split(":");
        if (parts.length == 2 && "global".equals(parts[0])) {
            return new String[]{"0", "0"};
        }
        if (parts.length == 3 && ("topic".equals(parts[0]) || "activity".equals(parts[0]))) {
            return new String[]{"topic".equals(parts[0]) ? "1" : "2", parts[1]};
        }
        return null;
    }

    private static int requireScenario(Integer scenario) {
        if (scenario == null
                || (scenario != MarketingRevenueRule.SCENARIO_PLATFORM_SELLS_AUTHOR_CONTENT
                && scenario != MarketingRevenueRule.SCENARIO_AUTHOR_OWN_CODE
                && scenario != MarketingRevenueRule.SCENARIO_REFERRER_RECOMMENDS)) {
            throw MarketingRevenueException.badRequest("SCENARIO_INVALID", "场景必须是 1/2/3");
        }
        return scenario;
    }
}
