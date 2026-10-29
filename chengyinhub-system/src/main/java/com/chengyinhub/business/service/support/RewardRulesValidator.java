package com.chengyinhub.business.service.support;

import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 条件奖励规则的校验与规范化。
 *
 * <p>商家配的形状是「达到某个结果,发这份奖」:
 * <pre>[{"outcomeCode":"ESTIMATE_HIT","couponId":123,"xp":50}]</pre>
 *
 * <p>模板上原有的平铺奖励列(coupon_id / feedback_text / medal_*)语义不变,
 * 仍表示「完成即发」;本列是叠加在它之上的条件奖励。
 *
 * <p>⚠️ 这里只校验形状。「这张券是不是商家自己的」由
 * {@code ICouponRewardAuthorizationService} 在写入口把关 —— 那是资损闸,
 * 不能挪到这个纯函数里,否则校验器一被复用就绕过去了。
 */
public class RewardRulesValidator {

    /** 与前端结果合同 node-outcome-contract.js 的 CODE_PATTERN 同一套 */
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final int MAX_RULES = 8;
    private static final int MAX_XP = 1000;
    private static final int MAX_JSON_LENGTH = 4000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Only collect validated resource references; ownership remains in the existing write-owner service. */
    public Set<Long> couponIds(String rawJson) {
        String normalized = validateAndNormalize(rawJson);
        if (normalized == null) return Collections.emptySet();
        Set<Long> ids = new LinkedHashSet<>();
        try {
            for (JsonNode rule : objectMapper.readTree(normalized)) {
                long id = rule.path("couponId").asLong(0L);
                if (id > 0) ids.add(id);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            throw new IllegalStateException("已校验奖励规则无法读取", impossible);
        }
        return Collections.unmodifiableSet(ids);
    }

    /**
     * @return 规范化后的紧凑 JSON;空配置返回 null(表示"没有条件奖励")
     * @throws IllegalArgumentException 形状不合法,消息直接给商家看
     */
    public String validateAndNormalize(String rawJson) {
        if (StringUtils.isBlank(rawJson)) return null;
        if (rawJson.length() > MAX_JSON_LENGTH) {
            throw new IllegalArgumentException("奖励规则配置过大");
        }
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(rawJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("奖励规则不是合法 JSON");
        }
        if (parsed == null || parsed.isNull()) return null;
        if (!parsed.isArray()) {
            throw new IllegalArgumentException("奖励规则必须是一个数组");
        }
        if (parsed.size() == 0) return null;
        if (parsed.size() > MAX_RULES) {
            throw new IllegalArgumentException("条件奖励最多 " + MAX_RULES + " 条");
        }

        Set<String> codes = new HashSet<>();
        for (JsonNode rule : parsed) {
            if (rule == null || !rule.isObject()) {
                throw new IllegalArgumentException("奖励规则格式不正确");
            }
            String code = rule.path("outcomeCode").asText("").trim();
            if (!CODE.matcher(code).matches()) {
                throw new IllegalArgumentException("结果码只能用1至64位字母、数字、下划线和短横线");
            }
            // 同一个结果码配两份奖 = 发一份还是两份,谁也说不清
            if (!codes.add(code)) {
                throw new IllegalArgumentException("同一个结果码只能配一份奖励:" + code);
            }

            long couponId = rule.path("couponId").asLong(0L);
            if (couponId < 0) {
                throw new IllegalArgumentException("奖励券 id 不合法");
            }
            int xp = rule.path("xp").asInt(0);
            if (xp < 0 || xp > MAX_XP) {
                throw new IllegalArgumentException("奖励探索值须为 0 至 " + MAX_XP);
            }
            String feedbackText = rule.path("feedbackText").asText("").trim();
            if (feedbackText.length() > 200) {
                throw new IllegalArgumentException("奖励文案不能超过 200 字");
            }
            // ⚠️ 条件奖励不收勋章。勋章在现有链路里不是"发放"动作 —— 它随节点数据
            // 提前下发给前端播颁发动画,而节点列表是玩家开玩前就取的,那时还没有结果码。
            // 收了字段却发不出去就是假保证,不如明说不支持。
            if (rule.has("medalName") || rule.has("medalImg") || rule.has("medalStyle")) {
                throw new IllegalArgumentException("条件奖励暂不支持勋章,勋章请配在「完成奖励」里");
            }
            // 一条什么都不发的规则是配置事故:商家以为配了奖,玩家什么也拿不到
            if (couponId <= 0 && xp <= 0 && feedbackText.isEmpty()) {
                throw new IllegalArgumentException("每条奖励规则至少要发一样东西:" + code);
            }
        }
        try {
            return objectMapper.writeValueAsString(parsed);
        } catch (Exception e) {
            throw new IllegalArgumentException("奖励规则无法序列化");
        }
    }

    /**
     * 挑出该给这个结果码发的那条规则。
     *
     * @return 命中的规则;没有条件奖励或没匹配上返回 null(此时只发无条件奖励)
     */
    public JsonNode ruleFor(String rulesJson, String outcomeCode) {
        if (StringUtils.isBlank(rulesJson) || StringUtils.isBlank(outcomeCode)) return null;
        try {
            JsonNode rules = objectMapper.readTree(rulesJson);
            if (rules == null || !rules.isArray()) return null;
            for (JsonNode rule : rules) {
                if (outcomeCode.equals(rule.path("outcomeCode").asText(""))) return rule;
            }
        } catch (Exception ignored) {
            // 存量里若有脏 JSON,当作没有条件奖励处理:少发一份奖是可修的,
            // 抛异常会让整个节点完成回滚,玩家卡在这一站。
        }
        return null;
    }
}
