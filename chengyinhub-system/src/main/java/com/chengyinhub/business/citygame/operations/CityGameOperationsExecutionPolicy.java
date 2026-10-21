package com.chengyinhub.business.citygame.operations;

import org.springframework.stereotype.Component;

/**
 * 执行策略。资产执行为关闭；跑步新鲜度窗口没有配置值，调用方不得自填。
 */
@Component
public class CityGameOperationsExecutionPolicy {

    private final boolean assetExecutionEnabled;
    private final Long runnerFreshMillis;

    public CityGameOperationsExecutionPolicy() {
        this(false, null);
    }

    public CityGameOperationsExecutionPolicy(boolean assetExecutionEnabled, Long runnerFreshMillis) {
        this.assetExecutionEnabled = assetExecutionEnabled;
        this.runnerFreshMillis = runnerFreshMillis;
    }

    public boolean assetExecutionEnabled() {
        return assetExecutionEnabled;
    }

    public Long runnerFreshMillis() {
        return runnerFreshMillis;
    }
}
