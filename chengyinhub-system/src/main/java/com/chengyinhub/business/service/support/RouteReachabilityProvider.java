package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopicNode;

/**
 * 路网可达性外部事实边界。默认没有可靠路网提供方时必须返回 UNKNOWN，
 * 不得用两点直线距离冒充道路可达。
 */
@FunctionalInterface
public interface RouteReachabilityProvider {
    RouteReachabilityProvider UNKNOWN = (activityId, from, to) -> Reachability.UNKNOWN;

    Reachability evaluate(Long activityId, CmsTopicNode from, CmsTopicNode to);

    enum Reachability {
        REACHABLE,
        UNREACHABLE,
        UNKNOWN
    }
}
