package com.chengyinhub.business.service.support;

/**
 * C:U-169:各结局链路共用的「兜底结局」规则。
 * <ul>
 *   <li>保存期:声明了结局就必须恰好有一个兜底(ChapterFlowCompiler 结局章、GameplayRuntimeConfig storyEndings);</li>
 *   <li>运行期:条件都不中时落到兜底(ThemeRuntimeEngine 终局结算)。</li>
 * </ul>
 * 各链路「怎么挑中一个非兜底结局」(优先级/顺序)仍各自实现,这里只收口兜底这一条。
 */
public final class EndingFallbackRule {
    private EndingFallbackRule() { }

    /** 声明了结局时兜底必须恰好一个;没声明结局则不要求。 */
    public static boolean hasSingleFallback(boolean endingsDeclared, int fallbackCount) {
        return !endingsDeclared || fallbackCount == 1;
    }

    /** 没有命中的结局时用兜底。 */
    public static <T> T chosenOrFallback(T chosen, T fallback) {
        return chosen != null ? chosen : fallback;
    }
}
