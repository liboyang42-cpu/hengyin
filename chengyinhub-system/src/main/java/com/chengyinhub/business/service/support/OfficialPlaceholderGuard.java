package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;

import java.util.Objects;
import java.util.Set;

/**
 * x-template-games R9:官方货架玩法里的题目和答案是示例(今晚的暗号「城市醒了」、老板的秘密题选 A、
 * 街角谜题「1932」、城市接力棒「星火继续」)。采用后原样发布,每家店的答案都一样:
 * 一个人在一家店问到答案,就能在所有采用它的店答对;题目也和这家店毫无关系。
 *
 * <p>发布采用来的副本时,如果题目和答案都还是官方示例原文,拒绝并请作者换成自己的。
 * 只管文字题(1)和选项题(3);只认平台官方账号、由 12 个通用玩法种子写入的库行。
 * 开关 {@link #KEY}:默认开(安全值),写 {@code false} 关闭。
 */
public final class OfficialPlaceholderGuard
{
    public static final String KEY = "template.official_placeholder_guard";
    public static final long OFFICIAL_MEMBER_ID = 100000L;
    public static final Set<String> SEEDS = Set.of("seed_library_12packs_20260820");
    public static final String MESSAGE = "这是官方示例题，请把题目和答案换成你自己的再发布";

    private OfficialPlaceholderGuard() {}

    /** 开关值:只有明确写 false/0/off 才关。 */
    public static boolean enabled(String raw)
    {
        if (raw == null) return true;
        String v = raw.trim();
        return !("false".equalsIgnoreCase(v) || "0".equals(v) || "off".equalsIgnoreCase(v));
    }

    /** @return 拒绝文案;不是官方示例或已经改过时返回 null */
    public static String check(CmsTemplateLibrary source, CmsMemberTemplate candidate)
    {
        if (source == null || candidate == null) return null;
        if (!Long.valueOf(OFFICIAL_MEMBER_ID).equals(source.getMemberId())) return null;
        if (source.getCreateBy() == null || !SEEDS.contains(source.getCreateBy())) return null;
        Integer vm = candidate.getValidationMethod();
        if (vm == null || !vm.equals(source.getValidationMethod())) return null;
        if (!same(source.getQuestionName(), candidate.getQuestionName())) return null;
        if (vm == 1) {
            return same(source.getQuestionAnswer(), candidate.getQuestionAnswer()) ? MESSAGE : null;
        }
        if (vm == 3) {
            boolean unchanged = same(source.getCorrectAnswer(), candidate.getCorrectAnswer())
                    && same(source.getQuestionA(), candidate.getQuestionA())
                    && same(source.getQuestionB(), candidate.getQuestionB())
                    && same(source.getQuestionC(), candidate.getQuestionC())
                    && same(source.getQuestionD(), candidate.getQuestionD());
            return unchanged ? MESSAGE : null;
        }
        return null;
    }

    private static boolean same(String a, String b)
    {
        return Objects.equals(a == null ? "" : a.trim(), b == null ? "" : b.trim());
    }
}
