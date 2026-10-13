package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.route.TopicRouteState;

/** 主题完赛口径 seam；分支主题认权威终点，线性主题保持 done >= total。 */
public class TopicCompletionPolicy {
    public boolean isCompleted(String routeMode, int total, int done, TopicRouteState routeState) {
        if (TopicRouteState.BRANCH_GRAPH.equals(routeMode)) {
            return routeState != null && TopicRouteState.COMPLETED.equals(routeState.getStatus());
        }
        return total > 0 && done >= total;
    }
}
