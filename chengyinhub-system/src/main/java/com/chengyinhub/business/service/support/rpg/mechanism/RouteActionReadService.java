package com.chengyinhub.business.service.support.rpg.mechanism;

import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 原动作只读。每次只调用既有 {@link TopicRouteSessionMapper#selectActionByAction} 一次。
 * 行上的局、成员、动作号都与本次请求一致才返回该行。不 insertAction，不加权限，不发奖。
 */
@Service
public final class RouteActionReadService {
    private final TopicRouteSessionMapper actions;

    public RouteActionReadService(TopicRouteSessionMapper actions) {
        this.actions = Objects.requireNonNull(actions, "topicRouteSessionMapper");
    }

    /**
     * 局、成员、动作号可用时查询一次。行上任一 id 与请求不同则不是这一条动作，返回 {@code null}。
     */
    public TopicRouteAction read(Long sessionId, Long memberId, String actionId) {
        String id = actionId == null ? null : actionId.trim();
        if (sessionId == null || sessionId <= 0L || memberId == null || memberId <= 0L
                || id == null || id.isEmpty()) {
            return null;
        }
        TopicRouteAction row = actions.selectActionByAction(sessionId, memberId, id);
        if (row == null || !id.equals(row.getActionId()) || !sessionId.equals(row.getSessionId())
                || !memberId.equals(row.getMemberId())) {
            return null;
        }
        return row;
    }
}
