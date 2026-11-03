package com.chengyinhub.business.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.RecruitmentWindowPolicy;

import java.util.Date;

/**
 * 2026-11-03 业主(ROUND4 l5-onboard):自由探索主题**一章都没有**时,商家只带玩法模板也能报名,
 * 服务端替这一站建出它需要的那一章。
 *
 * <p>只有「零章节」放行。有章节但没有一章在招商的,仍是主办方没开招募 —— 不能借这条路绕过去。
 *
 * <p>这里只放主题级的前置闸(读路径和写路径同一份判据):自由探索、已上架过审、未锁价、
 * 招商窗口开着、不是自己的主题、商家已选行业类型。章节级的闸(品类合法、启用商家、名额)
 * 在建章后由原 {@code MerchantChapterApplicationServiceImpl.apply} 再走一遍,不在这里复制。
 */
public final class EmptyTopicChapterPolicy {

    private EmptyTopicChapterPolicy() {}

    /** @return 不能走「零章节报名」的原因;null = 可以 */
    public static String blocker(CmsTopic topic, Long merchantMemberId, Integer merchantCategoryId, Date now) {
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())) return "主题不存在或已删除";
        if (topic.getProductType() == null || topic.getProductType() != ProductType.FREE_EXPLORE) {
            return "仅自由探索主题支持章节承接";
        }
        if (merchantMemberId == null || merchantMemberId <= 0) return "请先登录";
        if (merchantMemberId.equals(topic.getMemberId())) return "不能申请承接自己发布的主题章节";
        if (merchantCategoryId == null || merchantCategoryId <= 0) return "请先选择行业类型";
        if (!Integer.valueOf(1).equals(topic.getStatus()) || !Integer.valueOf(1).equals(topic.getUserStatus())) {
            return "主题未上架或未过审，暂不能申请承接";
        }
        if (TopicLifecycleState.commercialTermsFrozen(topic.getLifecycle())) {
            return "主题已锁价，不能再申请或审核商家承接";
        }
        try {
            RecruitmentWindowPolicy.assertOpen(topic.getLifecycle(), topic.getStatus(), topic.getUserStatus(),
                    topic.getRecruitDeadline(), now);
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
        return null;
    }
}
