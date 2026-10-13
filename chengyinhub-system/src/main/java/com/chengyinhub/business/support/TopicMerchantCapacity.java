package com.chengyinhub.business.support;

import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.vo.MerchantChapterRecruitmentStats;

import java.util.Map;

/**
 * 主题级商家名额聚合 —— 把章节级的剩余名额收敛成招募广场卡片能用的一个数。
 *
 * <p><b>为什么需要它</b>:名额是<b>按章节</b>配置和占用的
 * ({@code cms_topic_chapter.max_merchant} + 该章的已预占商家),
 * 而招募广场一张卡片代表<b>一个主题</b>。中间这层换算不做,前端就只能自己拍脑袋,
 * 或者拿不到数只好恒显「招募中」。
 *
 * <p><b>三条口径,顺序不能乱</b>(这也是本类存在的全部理由 —— 它们很容易被写反):
 * <ol>
 *   <li><b>没有开放招商的章节 ⇒ {@code null},不是 0</b>。
 *       「这个主题不招商」和「名额已满」是两件事,前者渲染成「已满」就是假信息。</li>
 *   <li><b>任一开放章节不限名额 ⇒ {@code null}</b>。
 *       {@code max_merchant} 为 null 或 0 表示不限,{@code getRecruitmentStats} 对这种章节
 *       返回的 remaining 就是 null。不限和 0 的含义相反,不能当成 0 参与求和。</li>
 *   <li>其余情况 ⇒ 各开放章节剩余名额<b>求和</b>。</li>
 * </ol>
 *
 * <p>⇒ 对调用方(前端)的契约:{@code null} = 不适用/不限,<b>不要渲染「已满」};
 * {@code 0} = 确实满了;{@code >0} = 还有名额。
 *
 * <p>⚠️ 「是否开放招商」只认 {@link ChapterRecruitmentRules#isEnabled(Integer)} 这一个开关。
 * {@code max_merchant} 只表示容量(0/空=不限),<b>不能</b>用它反推「有容量就是在招商」——
 * 存量章节普遍带着默认容量值。
 *
 * @author chengyinhub
 * @date 2026-07-26
 */
public final class TopicMerchantCapacity {

    private TopicMerchantCapacity() {
    }

    /**
     * 汇总一个主题的剩余商家名额。
     *
     * @param chapters      该主题的全部章节;null/空表示没有章节
     * @param statsByChapterId 章节 id → 该章招商统计;缺项按「拿不到该章数据」处理
     * @return 剩余名额总数;{@code null} 表示不招商或不限名额(<b>调用方不得渲染成「已满」</b>)
     */
    public static Integer remainingMerchantCount(
            Iterable<CmsTopicChapter> chapters,
            Map<Long, MerchantChapterRecruitmentStats> statsByChapterId) {

        if (chapters == null || statsByChapterId == null) {
            return null;
        }
        int total = 0;
        boolean sawOpenChapter = false;

        for (CmsTopicChapter chapter : chapters) {
            if (chapter == null || chapter.getId() == null
                    || !ChapterRecruitmentRules.isEnabled(chapter.getRecruitEnabled())) {
                continue;
            }
            MerchantChapterRecruitmentStats stats = statsByChapterId.get(chapter.getId());
            if (stats == null) {
                // 拿不到该章统计就不敢下结论 —— 少算一章会把「还有名额」说成「已满」。
                return null;
            }
            Integer remaining = stats.getRemainingMerchantCount();
            if (remaining == null) {
                // 该章不限名额 ⇒ 整个主题不存在「满」的说法。
                return null;
            }
            sawOpenChapter = true;
            total += Math.max(0, remaining);
        }

        // 一个开放招商的章节都没有 ⇒ 不适用,绝不能返回 0(那会被渲染成「已满」)。
        return sawOpenChapter ? Integer.valueOf(total) : null;
    }
}
