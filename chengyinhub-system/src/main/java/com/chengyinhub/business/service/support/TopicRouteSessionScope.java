package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import java.util.List;

/** 场次必须精确匹配；旧请求只有在本会员、本主题唯一局时才能兼容。 */
public final class TopicRouteSessionScope {
    private TopicRouteSessionScope() { }

    public static TopicRouteSession find(TopicRouteSessionMapper mapper, Long memberId,
                                        Long topicId, Long activityId) {
        if (memberId == null || memberId <= 0 || topicId == null || topicId <= 0
                || (activityId != null && activityId < 0)) {
            throw new IllegalArgumentException("场次参数有误");
        }
        TopicRouteSession session;
        if (activityId == null) {
            List<TopicRouteSession> matches = mapper.selectMemberTopicSessions(memberId, topicId);
            if (matches == null || matches.isEmpty()) return null;
            if (matches.size() != 1) throw new IllegalArgumentException("存在多个场次，请指定 activityId");
            session = matches.get(0);
        } else {
            session = mapper.selectByOwner(activityId, topicId, memberId, "default");
        }
        if (session != null && (!memberId.equals(session.getMemberId())
                || !topicId.equals(session.getTopicId())
                || (activityId != null && !activityId.equals(session.getActivityId())))) {
            throw new com.chengyinhub.common.exception.ServiceException("无权访问这一局");
        }
        return session;
    }

    /** Opt-in current run lookup; omitted activity retains the legacy unique default-scope contract. */
    public static TopicRouteSession findCurrent(TopicRouteSessionMapper mapper, Long memberId,
                                               Long topicId, Long activityId) {
        if (memberId == null || memberId <= 0 || topicId == null || topicId <= 0
                || (activityId != null && activityId < 0)) throw new IllegalArgumentException("场次参数有误");
        Long resolvedActivity = activityId;
        if (resolvedActivity == null) {
            TopicRouteSession legacy = find(mapper, memberId, topicId, null);
            if (legacy == null) return null;
            resolvedActivity = legacy.getActivityId();
        }
        TopicRouteSession session = mapper.selectCurrentByOwner(
                resolvedActivity == null ? 0L : resolvedActivity, topicId, memberId);
        if (session == null) session = mapper.selectByOwner(
                resolvedActivity == null ? 0L : resolvedActivity, topicId, memberId, "default");
        if (session != null && (!memberId.equals(session.getMemberId()) || !topicId.equals(session.getTopicId())
                || !java.util.Objects.equals(resolvedActivity, session.getActivityId()))) {
            throw new com.chengyinhub.common.exception.ServiceException("无权访问这一局");
        }
        return session;
    }

}
