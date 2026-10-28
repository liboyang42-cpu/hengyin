package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.TopicRouteSession;

/** 命令入口核实并锁定的原运行身份；无局与共享局不能当作当前个人局。 */
public record PlayOriginalRun(TopicRouteSession session, Long routeSessionId,
        String runtimeInstance, Integer authorityEpoch) {
    public String progressInstance() {
        if (session != null) return session.getPlayInstanceId();
        return runtimeInstance == null ? "default" : "game:" + routeSessionId;
    }

    public boolean hasRuntime() {
        return session != null || runtimeInstance != null;
    }
}
