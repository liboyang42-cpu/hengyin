package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 偏好题组的配置校验与确定性求值；不依赖勋章 RuleEvaluator。 */
public class PreferenceResultEvaluator {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ALLOWED_TAG_CODES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "space_constraints", "social_mode", "arrival_window", "expression_mode")));

    public ObjectNode validate(String configJson) {
        ObjectNode config = parseObject(configJson);
        JsonNode dimensions = requiredArray(config, "dimensions", "偏好题缺少维度");
        Set<String> dimensionNames = new HashSet<String>();
        for (JsonNode dimension : dimensions) {
            String name = text(dimension);
            if (blank(name) || !dimensionNames.add(name)) throw new IllegalArgumentException("偏好题维度无效");
        }
        JsonNode steps = requiredArray(config, "steps", "偏好题至少需要一步");
        Set<String> stepKeys = new HashSet<String>();
        boolean hasNotApplicable = false;
        for (JsonNode step : steps) hasNotApplicable |= validateStep(step, dimensionNames, stepKeys);
        JsonNode tiebreak = config.get("tiebreak");
        if (tiebreak != null && !tiebreak.isNull()) {
            hasNotApplicable |= validateStep(tiebreak, dimensionNames, stepKeys);
        }
        if (dimensionNames.size() > 1 && (tiebreak == null || !tiebreak.isObject())) {
            throw new IllegalArgumentException("多维偏好题必须配置并列取舍题");
        }
        Set<String> reachable = reachableDimensions(steps, tiebreak, dimensionNames);
        for (String dimension : dimensionNames) {
            if (!reachable.contains(dimension)) throw new IllegalArgumentException("偏好题维度不可达:" + dimension);
        }

        JsonNode results = config.get("results");
        if (results == null || !results.isObject()) throw new IllegalArgumentException("偏好题缺少结果分支");
        Iterator<String> resultNames = results.fieldNames();
        while (resultNames.hasNext()) {
            String resultName = resultNames.next();
            if (!dimensionNames.contains(resultName)) {
                throw new IllegalArgumentException("偏好题存在未声明结果分支:" + resultName);
            }
        }
        Long commonCouponId = null;
        boolean allHaveCoupon = true;
        for (String dimension : dimensionNames) {
            JsonNode result = results.get(dimension);
            String resultBody = result == null ? null : text(result.get("body"));
            if (result == null || !result.isObject()
                    || blank(text(result.get("title"))) || blank(resultBody)
                    || blank(text(result.get("nextStep"))) || !validActionWindow(result.get("nextStepDays"))) {
                throw new IllegalArgumentException("偏好题结果文案不能为空:" + dimension);
            }
            if (!resultBody.contains("{{choices}}")) {
                throw new IllegalArgumentException("偏好题结果必须展示选择依据:" + dimension);
            }
            JsonNode coupon = result.get("couponId");
            if (coupon == null || coupon.isNull() || !coupon.canConvertToLong()) {
                allHaveCoupon = false;
            } else if (commonCouponId == null) {
                commonCouponId = coupon.longValue();
            } else if (!commonCouponId.equals(coupon.longValue())) {
                allHaveCoupon = false;
            }
        }
        if (dimensionNames.size() > 1 && allHaveCoupon && commonCouponId != null) {
            throw new IllegalArgumentException("偏好题不能让所有结果绑定同一优惠券");
        }
        if (hasNotApplicable) {
            JsonNode branch = config.get("notApplicableResult");
            if (branch == null || !branch.isObject()
                    || blank(text(branch.get("title"))) || blank(text(branch.get("body")))
                    || blank(text(branch.get("nextStep"))) || !validActionWindow(branch.get("nextStepDays"))) {
                throw new IllegalArgumentException("偏好题不适用结果文案不能为空");
            }
        }
        JsonNode tagOutput = config.get("tagOutput");
        if (tagOutput != null && !tagOutput.isNull()) {
            assertAllowedTag(text(tagOutput.get("code")));
            if (!tagOutput.path("fromDimension").asBoolean(false)) {
                throw new IllegalArgumentException("偏好标签必须由结果维度产出");
            }
            if (blank(text(tagOutput.get("purpose"))) || blank(text(tagOutput.get("recipientLabel")))
                    || !tagOutput.path("revocable").asBoolean(false)) {
                throw new IllegalArgumentException("偏好标签用途、接收方与可撤回说明不能为空");
            }
        }
        JsonNode consumes = config.get("tagConsumes");
        if (consumes != null && !consumes.isNull()) {
            if (!consumes.isArray()) throw new IllegalArgumentException("偏好标签消费声明必须是数组");
            for (JsonNode code : consumes) assertAllowedTag(text(code));
        }
        return config;
    }

    public Evaluation evaluate(String configJson, Map<String, String> selections) {
        ObjectNode config = validate(configJson);
        Map<String, String> safeSelections = selections == null
                ? Collections.<String, String>emptyMap() : selections;
        LinkedHashMap<String, Integer> scores = new LinkedHashMap<String, Integer>();
        for (JsonNode dimension : config.path("dimensions")) scores.put(dimension.asText(), 0);
        List<String> choiceTexts = new ArrayList<String>();
        for (JsonNode step : config.path("steps")) {
            JsonNode option = selectedOption(step, safeSelections.get(text(step.get("key"))));
            choiceTexts.add(text(option.get("text")));
            if (option.path("notApplicable").asBoolean(false)) {
                return notApplicable(config, choiceTexts);
            }
            addScores(scores, option.path("scores"));
        }

        List<String> winners = winners(scores);
        JsonNode tiebreak = config.get("tiebreak");
        if (winners.size() > 1 && tiebreak != null && tiebreak.isObject()) {
            String choice = safeSelections.get(text(tiebreak.get("key")));
            if (blank(choice)) return Evaluation.tie((ObjectNode) tiebreak.deepCopy(), choiceTexts);
            JsonNode option = selectedOption(tiebreak, choice);
            choiceTexts.add(text(option.get("text")));
            if (option.path("notApplicable").asBoolean(false)) return notApplicable(config, choiceTexts);
            addScores(scores, option.path("scores"));
            winners = winners(scores);
        }
        if (winners.size() != 1) {
            return Evaluation.tie(tiebreak != null && tiebreak.isObject() ? (ObjectNode) tiebreak.deepCopy() : null,
                    choiceTexts);
        }
        String resultCode = winners.get(0);
        JsonNode branch = config.path("results").path(resultCode);
        String evidence = join(choiceTexts);
        Evaluation out = Evaluation.branch(resultCode, text(branch.get("title")),
                render(text(branch.get("body")), evidence), text(branch.get("nextStep")),
                branch.path("nextStepDays").asInt(), choiceTexts);
        JsonNode tagOutput = config.get("tagOutput");
        if (tagOutput != null && tagOutput.path("fromDimension").asBoolean(false)) {
            out.tagCode = text(tagOutput.get("code"));
            out.tagValue = resultCode;
        }
        return out;
    }

    /** 下游节点沿用玩家已确认的上游档位；只允许消费配置显式声明的 code。 */
    public Evaluation evaluateInherited(String configJson, String tagCode, String tagValue) {
        ObjectNode config = validate(configJson);
        if (!consumedTagCodes(configJson).contains(tagCode)) {
            throw new IllegalArgumentException("当前节点未声明消费该标签");
        }
        if (blank(tagValue) || !config.path("results").has(tagValue)) {
            throw new IllegalArgumentException("已确认标签不能匹配本站结果");
        }
        JsonNode branch = config.path("results").path(tagValue);
        List<String> evidence = Collections.singletonList("上一站已确认的" + tagValue);
        return Evaluation.branch(tagValue, text(branch.get("title")),
                render(text(branch.get("body")), join(evidence)), text(branch.get("nextStep")),
                branch.path("nextStepDays").asInt(), evidence);
    }

    public Set<String> consumedTagCodes(String configJson) {
        ObjectNode config = validate(configJson);
        Set<String> codes = new HashSet<String>();
        for (JsonNode code : config.path("tagConsumes")) codes.add(code.asText());
        return codes;
    }

    public static boolean isAllowedTagCode(String code) {
        return ALLOWED_TAG_CODES.contains(code);
    }

    private boolean validateStep(JsonNode step, Set<String> dimensions, Set<String> stepKeys) {
        if (step == null || !step.isObject()) throw new IllegalArgumentException("偏好题步骤无效");
        String key = text(step.get("key"));
        if (blank(key) || !stepKeys.add(key)) throw new IllegalArgumentException("偏好题步骤 key 无效");
        String type = text(step.get("type"));
        if (!"single".equals(type) && !"discard".equals(type)) {
            throw new IllegalArgumentException("偏好题步骤类型无效:" + type);
        }
        if (blank(text(step.get("title")))) throw new IllegalArgumentException("偏好题步骤标题不能为空:" + key);
        JsonNode options = requiredArray(step, "options", "偏好题步骤至少需要两个选项:" + key);
        if (options.size() < 2) throw new IllegalArgumentException("偏好题步骤至少需要两个选项:" + key);
        Set<String> optionKeys = new HashSet<String>();
        boolean hasNotApplicable = false;
        for (JsonNode option : options) {
            String optionKey = text(option.get("key"));
            if (blank(optionKey) || !optionKeys.add(optionKey) || blank(text(option.get("text")))) {
                throw new IllegalArgumentException("偏好题选项无效:" + key);
            }
            JsonNode scores = option.get("scores");
            if (scores == null || !scores.isObject()) throw new IllegalArgumentException("偏好题选项缺少 scores:" + key);
            hasNotApplicable |= option.path("notApplicable").asBoolean(false);
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = scores.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!dimensions.contains(field.getKey()) || !field.getValue().isInt()
                        || field.getValue().asInt() < 0 || field.getValue().asInt() > 2) {
                    throw new IllegalArgumentException("偏好题分值无效:" + field.getKey());
                }
            }
        }
        return hasNotApplicable;
    }

    /** 穷举 MVP 小题组，证明每个结果真能唯一胜出，并证明每个并列选项都能收敛。 */
    private Set<String> reachableDimensions(JsonNode steps, JsonNode tiebreak, Set<String> dimensions) {
        List<Map<String, Integer>> states = new ArrayList<Map<String, Integer>>();
        Map<String, Integer> zero = new LinkedHashMap<String, Integer>();
        for (String dimension : dimensions) zero.put(dimension, 0);
        states.add(zero);
        for (JsonNode step : steps) {
            List<Map<String, Integer>> next = new ArrayList<Map<String, Integer>>();
            for (Map<String, Integer> state : states) {
                for (JsonNode option : step.path("options")) {
                    if (option.path("notApplicable").asBoolean(false)) continue;
                    Map<String, Integer> score = new LinkedHashMap<String, Integer>(state);
                    addScores(score, option.path("scores"));
                    next.add(score);
                    if (next.size() > 4096) throw new IllegalArgumentException("偏好题组合过多，请拆分题组");
                }
            }
            states = next;
        }
        Set<String> reachable = new HashSet<String>();
        for (Map<String, Integer> state : states) {
            List<String> baseWinners = winners(state);
            if (baseWinners.size() == 1) {
                reachable.add(baseWinners.get(0));
                continue;
            }
            if (tiebreak == null || !tiebreak.isObject()) continue;
            for (JsonNode option : tiebreak.path("options")) {
                if (option.path("notApplicable").asBoolean(false)) continue;
                Map<String, Integer> score = new LinkedHashMap<String, Integer>(state);
                addScores(score, option.path("scores"));
                List<String> resolved = winners(score);
                if (resolved.size() != 1) throw new IllegalArgumentException("并列取舍题仍可能打平");
                reachable.add(resolved.get(0));
            }
        }
        return reachable;
    }

    private Evaluation notApplicable(ObjectNode config, List<String> choices) {
        JsonNode branch = config.path("notApplicableResult");
        return Evaluation.branch("not_applicable", text(branch.get("title")), text(branch.get("body")),
                text(branch.get("nextStep")), branch.path("nextStepDays").asInt(), choices);
    }

    private JsonNode selectedOption(JsonNode step, String selectedKey) {
        if (blank(selectedKey)) throw new IllegalArgumentException("偏好题未完成:" + text(step.get("key")));
        for (JsonNode option : step.path("options")) {
            if (selectedKey.equals(text(option.get("key")))) return option;
        }
        throw new IllegalArgumentException("偏好题选项不存在:" + text(step.get("key")));
    }

    private void addScores(Map<String, Integer> scores, JsonNode additions) {
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = additions.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            scores.put(field.getKey(), scores.get(field.getKey()) + field.getValue().asInt());
        }
    }

    private List<String> winners(Map<String, Integer> scores) {
        int max = Integer.MIN_VALUE;
        List<String> winners = new ArrayList<String>();
        for (Map.Entry<String, Integer> score : scores.entrySet()) {
            if (score.getValue() > max) {
                max = score.getValue();
                winners.clear();
                winners.add(score.getKey());
            } else if (score.getValue() == max) {
                winners.add(score.getKey());
            }
        }
        return winners;
    }

    private ObjectNode parseObject(String value) {
        try {
            JsonNode parsed = JSON.readTree(value);
            if (parsed == null || !parsed.isObject()) throw new IllegalArgumentException("偏好题配置必须是 JSON 对象");
            return (ObjectNode) parsed;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("偏好题配置 JSON 无效");
        }
    }

    private JsonNode requiredArray(JsonNode node, String field, String message) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray() || value.size() == 0) throw new IllegalArgumentException(message);
        return value;
    }

    private void assertAllowedTag(String code) {
        if (!isAllowedTagCode(code)) throw new IllegalArgumentException("偏好标签不在白名单:" + code);
    }

    private static boolean validActionWindow(JsonNode node) {
        return node != null && node.isInt() && (node.asInt() == 7 || node.asInt() == 30);
    }

    private static String text(JsonNode node) { return node == null || node.isNull() ? null : node.asText(); }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private static String render(String template, String choices) {
        return template == null ? null : template.replace("{{choices}}", choices);
    }
    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append('、');
            out.append(value);
        }
        return out.toString();
    }

    public static final class Evaluation {
        private String resultCode;
        private String title;
        private String body;
        private String nextStep;
        private int nextStepDays;
        private List<String> choices;
        private boolean needsTiebreak;
        private ObjectNode tiebreak;
        private String tagCode;
        private String tagValue;

        private static Evaluation branch(String code, String title, String body, String nextStep, int nextStepDays,
                                         List<String> choices) {
            Evaluation out = new Evaluation();
            out.resultCode = code;
            out.title = title;
            out.body = body;
            out.nextStep = nextStep;
            out.nextStepDays = nextStepDays;
            out.choices = new ArrayList<String>(choices);
            return out;
        }

        private static Evaluation tie(ObjectNode tiebreak, List<String> choices) {
            Evaluation out = new Evaluation();
            out.needsTiebreak = true;
            out.tiebreak = tiebreak;
            out.choices = new ArrayList<String>(choices);
            return out;
        }

        public String getResultCode() { return resultCode; }
        public String getTitle() { return title; }
        public String getBody() { return body; }
        public String getNextStep() { return nextStep; }
        public int getNextStepDays() { return nextStepDays; }
        public List<String> getChoices() { return choices; }
        public boolean isNeedsTiebreak() { return needsTiebreak; }
        public ObjectNode getTiebreak() { return tiebreak; }
        public String getTagCode() { return tagCode; }
        public String getTagValue() { return tagValue; }
    }
}
