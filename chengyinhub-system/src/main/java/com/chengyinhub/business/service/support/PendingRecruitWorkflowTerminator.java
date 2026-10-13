package com.chengyinhub.business.service.support;

import java.util.Date;

/**
 * W2 招商工作流写侧的窄 seam。调用方已经锁住 topic 行；实现必须先终结该期 pending，
 * 再返回，不能把“仍可接受”的工作流与开售终检并发运行。
 */
public interface PendingRecruitWorkflowTerminator {

    void terminatePendingUnderTopicLock(Long topicId, Date now);
}
