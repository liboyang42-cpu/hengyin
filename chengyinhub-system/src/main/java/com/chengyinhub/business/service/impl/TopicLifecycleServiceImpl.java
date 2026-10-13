package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.ITopicLifecycleService;
import com.chengyinhub.business.service.support.CommercialReviewStatusSource;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TopicLifecycleServiceImpl implements ITopicLifecycleService {

    private final CmsTopicMapper topicMapper;
    private final CommercialTopicPolicy commercialTopicPolicy;
    private final CommercialReviewStatusSource reviewStatusSource;

    public TopicLifecycleServiceImpl(CmsTopicMapper topicMapper, CommercialTopicPolicy commercialTopicPolicy,
                                     CommercialReviewStatusSource reviewStatusSource) {
        this.topicMapper = topicMapper;
        this.commercialTopicPolicy = commercialTopicPolicy;
        this.reviewStatusSource = reviewStatusSource;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean transition(Long topicId, TopicLifecycleState expected, TopicLifecycleState target) {
        if (topicId == null || expected == null || target == null) {
            throw new IllegalArgumentException("主题和生命周期状态不能为空");
        }
        if (!isAllowed(expected, target)) {
            throw new IllegalArgumentException("非法主题生命周期迁移: "
                    + expected.getLabel() + " → " + target.getLabel());
        }
        if (expected == TopicLifecycleState.PRICING && target == TopicLifecycleState.SELLING) {
            throw new IllegalStateException("PRICING→SELLING 必须走 finalizeForSale 原子终检");
        }
        assertReviewGate(topicId, expected, target);
        return topicMapper.transitionLifecycle(topicId, expected.getCode(), target.getCode(),
                DateUtils.getNowDate()) == 1;
    }

    private void assertReviewGate(Long topicId, TopicLifecycleState expected, TopicLifecycleState target) {
        if (expected == TopicLifecycleState.DRAFT
                && (target == TopicLifecycleState.RECRUITING || target == TopicLifecycleState.PRICING)
                && commercialTopicPolicy.evaluate(topicId).isCommercial()) {
            throw new IllegalStateException("商业主题必须先提交并通过立项审核");
        }
        if (expected == TopicLifecycleState.DRAFT && target == TopicLifecycleState.PENDING_REVIEW
                && !commercialTopicPolicy.evaluate(topicId).isCommercial()) {
            throw new IllegalStateException("纯 UGC 主题无需提交商业审核");
        }
        if (expected == TopicLifecycleState.PENDING_REVIEW) {
            if (target == TopicLifecycleState.DRAFT) {
                reviewStatusSource.assertProjectRejected(topicId);
            } else {
                reviewStatusSource.assertProjectApproved(topicId, target);
                assertContentReviewApproved(topicId);
            }
        }
    }

    /**
     * 立项审(TOPIC_PROJECT_REVIEW)只管商业阶段;内容「先审后发」是另一条审核任务(BIZ_TOPIC),
     * 它写的是 status/user_status。<b>两条都过才算真的开放招商。</b>
     *
     * <p><b>为什么必须在这里拦</b>:只审了立项那条时,lifecycle 会大方地走到 RECRUITING,
     * 而商家侧 {@code MerchantChapterApplicationServiceImpl.assertRecruitmentOpen} 因为
     * status/user_status 仍是 0/0 恒红「主题未上架或未过审」—— 平台侧却没有任何一处显示
     * 「还差一个审」。那是一条「看着通过、闸恒红、零提示」的假成功,与本仓对
     * {@code applyWhitelistEdit} 写死的价值判断同族:<b>拒收,不静默</b>。
     *
     * <p><b>不会造成审核顺序死锁</b>:BIZ_TOPIC 那条的过审 {@code applyModeration} 不读 lifecycle,
     * 任何时候都能先审;review() 是 @Transactional,这里抛异常会整体回滚,立项任务留在待审可重试。
     * 建议版(simple)建题本就直接 status=1/user_status=1、不建 BIZ_TOPIC 任务,这道断言对它天然为真。
     *
     * <p><b>两个字段都要看</b>:过审但个人配额已满时 {@code applyModeration} 写的是
     * status=1 而 user_status=0(审核结论生效但不自动上架),商家闸两个字段都要 1。
     */
    private void assertContentReviewApproved(Long topicId) {
        com.chengyinhub.business.domain.CmsTopic topic = topicMapper.selectCmsTopicById(topicId);
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())) {
            throw new IllegalStateException("主题不存在或已删除,拒绝离开立项待审");
        }
        if (!Integer.valueOf(1).equals(topic.getStatus()) || !Integer.valueOf(1).equals(topic.getUserStatus())) {
            throw new IllegalStateException("内容审核尚未通过或主题未上架(status=" + topic.getStatus()
                    + ", userStatus=" + topic.getUserStatus() + "):立项审核与内容审核是两条独立的审核任务,"
                    + "请先在审核台通过该主题的内容审核任务(bizType=TOPIC)再通过立项审核,"
                    + "否则招商开放了商家也永远申请不了承接");
        }
    }

    private static boolean isAllowed(TopicLifecycleState from, TopicLifecycleState to) {
        switch (from) {
            case DRAFT:
                return to == TopicLifecycleState.RECRUITING || to == TopicLifecycleState.PRICING
                        || to == TopicLifecycleState.PENDING_REVIEW;
            case PENDING_REVIEW:
                return to == TopicLifecycleState.DRAFT || to == TopicLifecycleState.RECRUITING
                        || to == TopicLifecycleState.PRICING;
            case RECRUITING:
                return to == TopicLifecycleState.DRAFT || to == TopicLifecycleState.PRICING;
            case PRICING:
                return to == TopicLifecycleState.SELLING;
            case SELLING:
                return to == TopicLifecycleState.RUNNING;
            case RUNNING:
                return to == TopicLifecycleState.SETTLING;
            case SETTLING:
                return to == TopicLifecycleState.CLOSED;
            default:
                return false;
        }
    }
}
