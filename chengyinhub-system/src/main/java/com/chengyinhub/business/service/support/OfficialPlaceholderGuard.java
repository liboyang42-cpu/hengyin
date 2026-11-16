package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsMemberTemplate;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * x-template-games R9:官方货架玩法里的题目和答案是示例(今晚的暗号「城市醒了」、老板的秘密题选 A、
 * 街角谜题「1932」、城市接力棒「星火继续」)。采用后原样发布,每家店的答案都一样:
 * 一个人在一家店问到答案,就能在所有采用它的店答对;题目也和这家店毫无关系。
 *
 * <p>发布采用来的副本(有来源)时,如果题目和答案都还是这几道官方示例的原文,拒绝并请作者换成自己的。
 * 示例原文来自 migration_seed_template_library_12packs_20260820.sql,写死在这里:
 * 编辑已采用副本时不得回读来源(来源可能已不可读),守卫只看副本自己的字段。
 * 开关 {@link #KEY}:默认开(安全值),写 {@code false} 关闭。
 */
public final class OfficialPlaceholderGuard
{
    public static final String KEY = "template.official_placeholder_guard";
    public static final String MESSAGE = "这是官方示例题，请把题目和答案换成你自己的再发布";

    /** 文字题:题目 → 示例答案。 */
    static final Map<String, String> TEXT_SAMPLES = Map.of(
            "向店员说出今日暗号,并在这里输入暗号", "城市醒了",
            "找到门牌上的年份,并输入四位数字", "1932",
            "输入你们传给下一队的接力暗号", "星火继续");
    /** 选项题:题目 → A/B/C/D + 正确项。 */
    static final Map<String, List<String>> CHOICE_SAMPLES = Map.of(
            "这家店最早靠什么被街坊记住?", List.of("一杯招牌饮品", "一面老墙", "一只店猫", "一张旧唱片", "A"));

    private OfficialPlaceholderGuard() {}

    /** 开关值:只有明确写 false/0/off 才关。 */
    public static boolean enabled(String raw)
    {
        if (raw == null) return true;
        String v = raw.trim();
        return !("false".equalsIgnoreCase(v) || "0".equals(v) || "off".equalsIgnoreCase(v));
    }

    /** @return 拒绝文案;不是采用来的副本、不是示例题或已经改过时返回 null */
    public static String check(Long originalTemplateId, CmsMemberTemplate candidate)
    {
        if (originalTemplateId == null || originalTemplateId <= 0 || candidate == null) return null;
        Integer vm = candidate.getValidationMethod();
        String question = norm(candidate.getQuestionName());
        if (Integer.valueOf(1).equals(vm)) {
            for (Map.Entry<String, String> sample : TEXT_SAMPLES.entrySet()) {
                if (norm(sample.getKey()).equals(question)
                        && norm(sample.getValue()).equals(norm(candidate.getQuestionAnswer()))) return MESSAGE;
            }
        }
        if (Integer.valueOf(3).equals(vm)) {
            for (Map.Entry<String, List<String>> sample : CHOICE_SAMPLES.entrySet()) {
                List<String> o = sample.getValue();
                if (norm(sample.getKey()).equals(question)
                        && norm(o.get(0)).equals(norm(candidate.getQuestionA()))
                        && norm(o.get(1)).equals(norm(candidate.getQuestionB()))
                        && norm(o.get(2)).equals(norm(candidate.getQuestionC()))
                        && norm(o.get(3)).equals(norm(candidate.getQuestionD()))
                        && norm(o.get(4)).equals(norm(candidate.getCorrectAnswer()))) return MESSAGE;
            }
        }
        return null;
    }

    /** 去掉全部空白,忽略大小写;全角逗号问号按半角比。 */
    private static String norm(String value)
    {
        if (value == null) return "";
        return Objects.toString(value).replaceAll("\\s+", "")
                .replace('，', ',').replace('？', '?').toLowerCase(Locale.ROOT);
    }
}
