package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.route.TopicRouteState;

/** 玩家读侧只认已发布路线快照；作者草稿 routeMode 不得改变线上可见性。 */
public final class TopicRouteModePolicy {
    private TopicRouteModePolicy() {
    }

    public static String publishedMode(CmsTopic topic) {
        if (topic == null) return TopicRouteState.LINEAR;
        return publishedMode(topic.getPublishedRouteGraphJson());
    }

    public static String publishedMode(String snapshot) {
        return snapshot == null || snapshot.trim().isEmpty()
                ? TopicRouteState.LINEAR : TopicRouteState.BRANCH_GRAPH;
    }

    public static boolean isPublishedBranchGraph(String snapshot) {
        return TopicRouteState.BRANCH_GRAPH.equals(publishedMode(snapshot));
    }

    /** 已冻结的分支会话继续按分支运行；否则回落当前已发布快照。 */
    public static boolean isEffectiveBranchGraph(CmsTopic topic, TopicRouteState routeState) {
        return TopicRouteState.BRANCH_GRAPH.equals(publishedMode(topic))
                || (routeState != null && TopicRouteState.BRANCH_GRAPH.equals(routeState.getRouteMode()));
    }
}
