package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.util.OrderRefundCash;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import java.util.List;
import java.util.Objects;

/** Payment/refund callers share the existing topic row, then activity/order current-read locks. */
final class RegistrationTopicFence {
    private RegistrationTopicFence() { }

    record LockedOrder(CmsRegistration registration, List<CmsRegistration> rows,
                       CmsTopic topic, CmsActivity activity) { }

    static LockedOrder lock(CmsRegistration observed, CmsRegistrationMapper registrations,
                            CmsActivityMapper activities, CmsTopicMapper topics) {
        if (observed == null || observed.getId() == null || observed.getOwnerId() == null)
            throw new ServiceException("原报名归属缺失，请人工核对");
        Long topicId;
        CmsActivity locatedActivity = null;
        if (Long.valueOf(1).equals(observed.getOwnerType())) {
            topicId = observed.getOwnerId();
            if (observed.getTopicId() != null && !topicId.equals(observed.getTopicId()))
                throw new ServiceException("原报名主题归属不一致，请人工核对");
        } else if (Long.valueOf(2).equals(observed.getOwnerType())) {
            locatedActivity = activities.selectCmsActivityById(observed.getOwnerId());
            if (locatedActivity == null || !observed.getOwnerId().equals(locatedActivity.getId()))
                throw new ServiceException("原报名活动不存在，请人工核对");
            topicId = locatedActivity.getTopicId();
        } else throw new ServiceException("原报名归属类型不明确，请人工核对");
        CmsTopic lockedTopic = null;
        if (topicId != null) {
            lockedTopic = topics.selectCmsTopicByIdForUpdate(topicId);
            if (lockedTopic == null || !topicId.equals(lockedTopic.getId()))
                throw new ServiceException("原报名主题不存在，请人工核对");
        }
        CmsActivity lockedActivity = null;
        if (locatedActivity != null) {
            lockedActivity = requireCurrentActivity(locatedActivity, activities);
        }
        List<CmsRegistration> order;
        if (StringUtils.isNotEmpty(observed.getParentOrderNo())) {
            order = registrations.selectByParentOrderNoForUpdate(observed.getParentOrderNo(),
                    Long.valueOf(2).equals(observed.getOwnerType()) ? observed.getOwnerId() : null);
            OrderRefundCash.assertOrderScope(order, observed);
        } else {
            CmsRegistration current = registrations.selectCmsRegistrationByIdForUpdate(observed.getId());
            if (current == null) throw new ServiceException("原报名不存在，请重试");
            order = List.of(current);
        }
        for (CmsRegistration current : order) {
            if (!Objects.equals(observed.getTopicId(), current.getTopicId()))
                throw new ServiceException("原订单主题归属已变化，请重试");
            if (observed.getId().equals(current.getId())) {
                requireSameIdentity(observed, current);
            }
        }
        CmsRegistration current = order.stream().filter(row -> observed.getId().equals(row.getId()))
                .findFirst().orElseThrow(() -> new ServiceException("报名不在原父订单中"));
        return new LockedOrder(current, List.copyOf(order), lockedTopic, lockedActivity);
    }

    /** For the outer activity-cancellation owner: call before its first activity/order write lock. */
    static CmsActivity lockActivity(CmsActivity observed, CmsActivityMapper activities, CmsTopicMapper topics) {
        lockActivityTopic(observed, topics);
        return requireCurrentActivity(observed, activities);
    }

    /** Series wrappers retain their existing series/activity order after taking this parent fence. */
    static CmsTopic lockActivityTopic(CmsActivity observed, CmsTopicMapper topics) {
        if (observed == null || observed.getId() == null) throw new ServiceException("原活动不存在，请重试");
        if (observed.getTopicId() != null) {
            CmsTopic topic = topics.selectCmsTopicByIdForUpdate(observed.getTopicId());
            if (topic == null || !observed.getTopicId().equals(topic.getId()))
                throw new ServiceException("原活动主题不存在，请人工核对");
            return topic;
        }
        return null;
    }

    static CmsActivity requireCurrentActivity(CmsActivity expected, CmsActivityMapper activities) {
        CmsActivity current = activities.selectCmsActivityByIdForUpdate(expected.getId());
        if (current == null || !expected.getId().equals(current.getId())
                || !Objects.equals(expected.getTopicId(), current.getTopicId())
                || !Objects.equals(expected.getClubId(), current.getClubId())
                || !Objects.equals(expected.getMemberId(), current.getMemberId()))
            throw new ServiceException("原活动主题归属已变化，请重试");
        return current;
    }

    private static void requireSameIdentity(CmsRegistration expected, CmsRegistration current) {
        if (!Objects.equals(expected.getId(), current.getId())
                || !Objects.equals(expected.getMemberId(), current.getMemberId())
                || !Objects.equals(expected.getOwnerType(), current.getOwnerType())
                || !Objects.equals(expected.getOwnerId(), current.getOwnerId())
                || !Objects.equals(expected.getParentOrderNo(), current.getParentOrderNo())
                || !Objects.equals(expected.getRegistrationNo(), current.getRegistrationNo()))
            throw new ServiceException("原报名身份已变化，请重试");
    }
}
