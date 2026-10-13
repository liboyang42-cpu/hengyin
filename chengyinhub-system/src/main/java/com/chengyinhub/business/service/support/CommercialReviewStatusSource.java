package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.enums.TopicLifecycleState;

/** 商业主题两阶段审核的只读状态源；缺失、未知、非通过状态一律拒绝。 */
public interface CommercialReviewStatusSource {
    void assertProjectApproved(Long topicId, TopicLifecycleState requestedTarget);
    /** 公开查询专用：重验同一快照但不获取主题行锁。 */
    void assertProjectApprovedReadOnly(Long topicId, TopicLifecycleState requestedTarget);
    void assertProjectRejected(Long topicId);
    void assertSaleApproved(Long topicId);
}
