package com.chengyinhub.business.service;

import com.chengyinhub.business.service.support.PreferenceResultEvaluator;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreferenceResultEvaluatorTest {

    private final PreferenceResultEvaluator evaluator = new PreferenceResultEvaluator();

    @Test
    void scoresDeterministicallyAndRendersChoiceEvidence() {
        Map<String, String> choices = new LinkedHashMap<String, String>();
        choices.put("room", "A");
        choices.put("light", "B");

        PreferenceResultEvaluator.Evaluation result = evaluator.evaluate(validConfig(), choices);

        assertFalse(result.isNeedsTiebreak());
        assertEquals("compact", result.getResultCode());
        assertEquals("小空间先减负", result.getTitle());
        assertTrue(result.getBody().contains("小户型、少采光"));
        assertEquals("space_constraints", result.getTagCode());
        assertEquals("compact", result.getTagValue());
    }

    @Test
    void tieNeverPicksRandomBranchAndDeclaredTiebreakResolvesIt() {
        Map<String, String> choices = new LinkedHashMap<String, String>();
        choices.put("room", "A");
        choices.put("light", "A");

        PreferenceResultEvaluator.Evaluation tie = evaluator.evaluate(validConfig(), choices);
        assertTrue(tie.isNeedsTiebreak());
        assertEquals("tradeoff", tie.getTiebreak().path("key").asText());

        choices.put("tradeoff", "B");
        PreferenceResultEvaluator.Evaluation resolved = evaluator.evaluate(validConfig(), choices);
        assertFalse(resolved.isNeedsTiebreak());
        assertEquals("open", resolved.getResultCode());
    }

    @Test
    void notApplicableWinsBeforeAnyScore() {
        Map<String, String> choices = new LinkedHashMap<String, String>();
        choices.put("room", "X");
        choices.put("light", "B");

        PreferenceResultEvaluator.Evaluation result = evaluator.evaluate(validConfig(), choices);

        assertEquals("not_applicable", result.getResultCode());
        assertEquals("先现场确认", result.getTitle());
        assertEquals(null, result.getTagCode());
    }

    @Test
    void confirmedUpstreamTagCanOnlySkipQuestionsWhenConsumerDeclaresIt() {
        PreferenceResultEvaluator.Evaluation inherited = evaluator.evaluateInherited(
                validConfig(), "space_constraints", "compact");
        assertEquals("compact", inherited.getResultCode());
        assertTrue(inherited.getBody().contains("上一站已确认"));

        assertEquals("当前节点未声明消费该标签", assertThrows(IllegalArgumentException.class,
                () -> evaluator.evaluateInherited(validConfig(), "social_mode", "compact")).getMessage());
    }

    @Test
    void submissionGateRejectsUnreachableEmptySameCouponAndUnknownTag() {
        assertEquals("偏好题维度不可达:ghost", message(validConfig()
                .replace("\"dimensions\":[\"compact\",\"open\"]", "\"dimensions\":[\"compact\",\"open\",\"ghost\"]")
                .replace("\"results\":{", "\"results\":{\"ghost\":{\"title\":\"幽灵\",\"body\":\"到不了\",\"nextStep\":\"无\",\"nextStepDays\":7},")));
        assertEquals("偏好题结果文案不能为空:open", message(validConfig().replace("开放布局", "")));
        assertEquals("偏好题结果文案不能为空:open", message(validConfig().replace("重排动线", "")));
        assertEquals("偏好题不适用结果文案不能为空", message(validConfig()
                .replace("\"notApplicableResult\":{\"title\":\"先现场确认\"",
                        "\"notApplicableResult\":{\"title\":\"\"")));
        assertEquals("偏好题不能让所有结果绑定同一优惠券", message(validConfig()
                .replace("\"couponId\":11", "\"couponId\":10")));
        assertEquals("偏好标签不在白名单:private_profile", message(validConfig()
                .replace("space_constraints", "private_profile")));
        assertEquals("偏好题存在未声明结果分支:ghost", message(validConfig()
                .replace("\"results\":{", "\"results\":{\"ghost\":{\"title\":\"幽灵\",\"body\":\"不可达\",\"nextStep\":\"无\",\"nextStepDays\":7},")));
        assertEquals("多维偏好题必须配置并列取舍题", message(withoutTiebreak()));
        assertEquals("偏好标签用途、接收方与可撤回说明不能为空", message(validConfig()
                .replace("\"purpose\":\"后续节点减少重复提问\"", "\"purpose\":\"\"")));
    }

    @Test
    void resultBodyMustExposeTheChoicesThatProducedIt() {
        assertEquals("偏好题结果必须展示选择依据:compact", message(validConfig()
                .replace("因为你选择了{{choices}}", "这是一段固定结论")));
    }

    @Test
    void declaredTagOutputMustActuallyComeFromTheWinningDimension() {
        assertEquals("偏好标签必须由结果维度产出", message(validConfig()
                .replace("\"fromDimension\":true", "\"fromDimension\":false")));
    }

    private String message(String config) {
        return assertThrows(IllegalArgumentException.class, () -> evaluator.validate(config)).getMessage();
    }

    private String withoutTiebreak() {
        String config = validConfig();
        int start = config.indexOf(",\"tiebreak\":");
        int end = config.indexOf(",\"tagConsumes\":");
        return config.substring(0, start) + config.substring(end);
    }

    private String validConfig() {
        return "{"
                + "\"steps\":["
                + "{\"key\":\"room\",\"type\":\"single\",\"title\":\"你的空间?\",\"options\":["
                + "{\"key\":\"A\",\"text\":\"小户型\",\"scores\":{\"compact\":2,\"open\":0}},"
                + "{\"key\":\"B\",\"text\":\"大空间\",\"scores\":{\"compact\":0,\"open\":2}},"
                + "{\"key\":\"X\",\"text\":\"不确定\",\"notApplicable\":true,\"scores\":{}}]},"
                + "{\"key\":\"light\",\"type\":\"discard\",\"title\":\"舍弃什么?\",\"options\":["
                + "{\"key\":\"A\",\"text\":\"少收纳\",\"scores\":{\"compact\":0,\"open\":2}},"
                + "{\"key\":\"B\",\"text\":\"少采光\",\"scores\":{\"compact\":2,\"open\":0}}]}],"
                + "\"dimensions\":[\"compact\",\"open\"],"
                + "\"results\":{"
                + "\"compact\":{\"title\":\"小空间先减负\",\"body\":\"因为你选择了{{choices}}\",\"nextStep\":\"清掉一层柜子\",\"nextStepDays\":7,\"couponId\":10},"
                + "\"open\":{\"title\":\"开放布局\",\"body\":\"因为你选择了{{choices}}\",\"nextStep\":\"重排动线\",\"nextStepDays\":30,\"couponId\":11}},"
                + "\"notApplicableResult\":{\"title\":\"先现场确认\",\"body\":\"信息不足\",\"nextStep\":\"现场补一题\",\"nextStepDays\":7},"
                + "\"tiebreak\":{\"key\":\"tradeoff\",\"type\":\"single\",\"title\":\"更在意?\",\"options\":["
                + "{\"key\":\"A\",\"text\":\"收纳\",\"scores\":{\"compact\":1}},"
                + "{\"key\":\"B\",\"text\":\"通透\",\"scores\":{\"open\":1}}]},"
                + "\"tagConsumes\":[\"space_constraints\"],"
                + "\"tagOutput\":{\"code\":\"space_constraints\",\"fromDimension\":true,"
                + "\"purpose\":\"后续节点减少重复提问\",\"recipientLabel\":\"本主题内声明需要它的下一站\",\"revocable\":true}}";
    }
}
