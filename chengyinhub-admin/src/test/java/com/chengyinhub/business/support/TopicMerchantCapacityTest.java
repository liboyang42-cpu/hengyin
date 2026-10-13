package com.chengyinhub.business.support;

import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.vo.MerchantChapterRecruitmentStats;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 主题级名额聚合的四条规则。
 *
 * <p>最重要的一条是 {@link #noOpenChapterIsNotFull()}:
 * 「不招商」和「已满」必须是两个结果,把前者算成 0 会在卡片上显示一条假信息。
 */
class TopicMerchantCapacityTest {

    @Test
    void sumsRemainingAcrossOpenChaptersOnly() {
        List<CmsTopicChapter> chapters = Arrays.asList(
                chapter(1L, 1),   // 开放招商
                chapter(2L, 1),   // 开放招商
                chapter(3L, 0));  // 未开放,不该计入
        Map<Long, MerchantChapterRecruitmentStats> stats = new HashMap<>();
        stats.put(1L, remaining(2));
        stats.put(2L, remaining(3));
        stats.put(3L, remaining(99)); // 即使有统计也必须被忽略

        assertEquals(Integer.valueOf(5),
                TopicMerchantCapacity.remainingMerchantCount(chapters, stats),
                "只应汇总开放招商章节:2+3=5,未开放的 99 不能计入");
    }

    /** ★ 没有任何开放招商的章节 ⇒ null(不适用),不是 0(已满)。 */
    @Test
    void noOpenChapterIsNotFull() {
        List<CmsTopicChapter> chapters = Arrays.asList(chapter(1L, 0), chapter(2L, null));
        Map<Long, MerchantChapterRecruitmentStats> stats = new HashMap<>();
        stats.put(1L, remaining(0));
        stats.put(2L, remaining(0));

        assertNull(TopicMerchantCapacity.remainingMerchantCount(chapters, stats),
                "不招商的主题必须返回 null;返回 0 会被前端渲染成「已满」,那是假信息");
    }

    /** 任一开放章节不限名额(stats.remaining=null)⇒ 整个主题不限。 */
    @Test
    void anyUnlimitedOpenChapterMakesTopicUnlimited() {
        List<CmsTopicChapter> chapters = Arrays.asList(chapter(1L, 1), chapter(2L, 1));
        Map<Long, MerchantChapterRecruitmentStats> stats = new HashMap<>();
        stats.put(1L, remaining(4));
        stats.put(2L, remaining(null)); // max_merchant 为 null/0 ⇒ 不限

        assertNull(TopicMerchantCapacity.remainingMerchantCount(chapters, stats),
                "不限名额不能当成 0 参与求和 —— 两者含义相反");
    }

    /** 开放章节全部占满 ⇒ 0,这才是真正的「已满」。 */
    @Test
    void allOpenChaptersFullYieldsZero() {
        List<CmsTopicChapter> chapters = Arrays.asList(chapter(1L, 1), chapter(2L, 1));
        Map<Long, MerchantChapterRecruitmentStats> stats = new HashMap<>();
        stats.put(1L, remaining(0));
        stats.put(2L, remaining(0));

        assertEquals(Integer.valueOf(0),
                TopicMerchantCapacity.remainingMerchantCount(chapters, stats),
                "开放章节全满才是 0");
    }

    /** 部分满:仍有名额就必须是正数,不能因为有一章满了就报「已满」。 */
    @Test
    void partiallyFullStillReportsRemaining() {
        List<CmsTopicChapter> chapters = Arrays.asList(chapter(1L, 1), chapter(2L, 1));
        Map<Long, MerchantChapterRecruitmentStats> stats = new HashMap<>();
        stats.put(1L, remaining(0));
        stats.put(2L, remaining(2));

        assertEquals(Integer.valueOf(2),
                TopicMerchantCapacity.remainingMerchantCount(chapters, stats),
                "一章满、另一章还有 2 个坑 ⇒ 主题仍有名额");
    }

    /** 缺某开放章节的统计 ⇒ 不敢下结论,返回 null;少算一章会把「有名额」说成「已满」。 */
    @Test
    void missingStatsForOpenChapterYieldsNull() {
        List<CmsTopicChapter> chapters = Arrays.asList(chapter(1L, 1), chapter(2L, 1));
        Map<Long, MerchantChapterRecruitmentStats> stats = new HashMap<>();
        stats.put(1L, remaining(3)); // 2L 的统计缺失

        assertNull(TopicMerchantCapacity.remainingMerchantCount(chapters, stats),
                "统计不全时不得给出数字");
    }

    @Test
    void nullInputsAreSafe() {
        assertNull(TopicMerchantCapacity.remainingMerchantCount(null, new HashMap<>()));
        assertNull(TopicMerchantCapacity.remainingMerchantCount(Collections.emptyList(), null));
        assertNull(TopicMerchantCapacity.remainingMerchantCount(
                Collections.emptyList(), new HashMap<>()), "没有章节 ⇒ 不适用");
    }

    private static CmsTopicChapter chapter(Long id, Integer recruitEnabled) {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(id);
        chapter.setRecruitEnabled(recruitEnabled);
        return chapter;
    }

    private static MerchantChapterRecruitmentStats remaining(Integer remaining) {
        MerchantChapterRecruitmentStats stats = new MerchantChapterRecruitmentStats();
        stats.setRemainingMerchantCount(remaining);
        return stats;
    }
}
