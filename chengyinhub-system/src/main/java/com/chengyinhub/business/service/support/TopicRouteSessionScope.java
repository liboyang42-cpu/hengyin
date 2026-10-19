package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.common.exception.ServiceException;
import java.util.List;

/** 当前主题局与显式历史读取分开；旧请求只有在本会员、本主题唯一局时才能兼容。 */
public final class TopicRouteSessionScope {
    private TopicRouteSessionScope() { }

    public static TopicRouteSession find(TopicRouteSessionMapper mapper, Long memberId,
                                        Long topicId, Long activityId) {
        validateScope(memberId, topicId, activityId);
        TopicRouteSession session;
        if (activityId == null) {
            List<TopicRouteSession> matches = mapper.selectMemberTopicSessions(memberId, topicId);
            if (matches == null || matches.isEmpty()) return null;
            if (matches.size() != 1) throw new IllegalArgumentException("存在多个场次，请指定 activityId");
            session = matches.get(0);
        } else {
            TopicRouteSession current = mapper.selectCurrentByOwner(activityId, topicId, memberId);
            assertScope(current, memberId, topicId, activityId);
            // 已冻结的主题重玩局是唯一真源；未配置主题仍按原 default 合同选择。
            session = ThemeRuntimeFeaturePolicy.hasFrozenTheme(current) ? current
                    : mapper.selectByOwner(activityId, topicId, memberId, "default");
        }
        assertScope(session, memberId, topicId, activityId);
        return session;
    }

    /** Resolve the bounded default/latest candidates of one explicit owner scope in Java. */
    public static TopicRouteSession resolveCurrentCandidates(List<TopicRouteSession> rows, Long memberId,
                                                              Long topicId, Long activityId) {
        validateScope(memberId, topicId, activityId);
        if (activityId == null) throw new IllegalArgumentException("批量场次必须明确");
        TopicRouteSession latest = null;
        TopicRouteSession legacy = null;
        if (rows != null) for (TopicRouteSession row : rows) {
            if (row == null) throw new ServiceException("本局候选不可读取");
            assertScope(row, memberId, topicId, activityId);
            if (row.getId() == null || row.getId() <= 0) throw new ServiceException("本局候选不可读取");
            if (latest == null || row.getId() > latest.getId()) latest = row;
            if ("default".equals(row.getPlayInstanceId())) {
                if (legacy != null && !legacy.getId().equals(row.getId())) throw new ServiceException("本局候选不唯一");
                legacy = row;
            }
        }
        return ThemeRuntimeFeaturePolicy.hasFrozenTheme(latest) ? latest : legacy;
    }

    /** 显式历史局只用于读取；传入的活动不允许被 sessionId 静默覆盖。 */
    public static TopicRouteSession findHistorical(TopicRouteSessionMapper mapper, Long memberId,
                                                  Long topicId, Long activityId, Long sessionId) {
        validateScope(memberId, topicId, activityId);
        if (sessionId == null || sessionId <= 0) throw new IllegalArgumentException("场次参数有误");
        TopicRouteSession session = mapper.selectAccessibleById(sessionId, memberId);
        assertScope(session, memberId, topicId, activityId);
        if (session != null && !sessionId.equals(session.getId())) {
            throw new ServiceException("无权访问这一局");
        }
        return session;
    }

    /** 写入前使用数据库当前局重新校验；历史局即使仍为 RUNNING 也不能写入。 */
    public static void requireCurrent(TopicRouteSessionMapper mapper, TopicRouteSession session) {
        if (session == null || session.getId() == null || session.getId() <= 0
                || session.getActivityId() == null) {
            throw new IllegalArgumentException("场次参数有误");
        }
        TopicRouteSession current = find(mapper, session.getMemberId(), session.getTopicId(),
                session.getActivityId());
        if (current == null || !session.getId().equals(current.getId())) {
            throw new ServiceException("本局已不是当前局，请刷新后重试", 409);
        }
    }

    private static void validateScope(Long memberId, Long topicId, Long activityId) {
        if (memberId == null || memberId <= 0 || topicId == null || topicId <= 0
                || (activityId != null && activityId < 0)) {
            throw new IllegalArgumentException("场次参数有误");
        }
    }

    private static void assertScope(TopicRouteSession session, Long memberId, Long topicId, Long activityId) {
        if (session != null && (!memberId.equals(session.getMemberId())
                || !topicId.equals(session.getTopicId())
                || (activityId != null && !activityId.equals(session.getActivityId())))) {
            throw new ServiceException("无权访问这一局");
        }
    }
}
