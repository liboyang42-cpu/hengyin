package com.chengyinhub.business.badge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 纯函数规则求值器:解析 unlock_rule_json + 阈值判定。无副作用,可单测。
 * ⚠️ P0 范围:引擎实际只判 threshold;def 里的 aggregate/aggregate_field/window(如 natural_day_streak)
 * 目前不参与判定——由调用方(onNodeDone/onTopicComplete)按业务预聚合出 observedValue 再传入。
 * 改 def 的 aggregate/window 不会改变行为;要调这些语义须改调用侧聚合逻辑。
 */
@Component
public class RuleEvaluator {

    private static final ObjectMapper OM = new ObjectMapper();

    /** 解析失败/空 → 安全默认(threshold=MAX,永不授予)。 */
    public BadgeRule parse(String json) {
        BadgeRule r = new BadgeRule();
        r.setThreshold(Long.MAX_VALUE);
        if (json == null || json.trim().isEmpty()) return r;
        try {
            JsonNode n = OM.readTree(json);
            if (n.hasNonNull("aggregate")) r.setAggregate(n.get("aggregate").asText());
            if (n.hasNonNull("aggregate_field")) r.setAggregateField(n.get("aggregate_field").asText());
            if (n.hasNonNull("threshold")) r.setThreshold(n.get("threshold").asLong(Long.MAX_VALUE));
        } catch (Exception ignore) {
            // 非法 json → 保持安全默认
        }
        return r;
    }

    /** observedValue 由调用方按 aggregate 预聚合后传入;此处只做阈值比较。 */
    public boolean isThresholdMet(BadgeRule rule, long observedValue) {
        if (rule == null) return false;
        return observedValue >= rule.getThreshold();
    }
}
