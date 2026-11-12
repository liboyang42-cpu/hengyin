package com.chengyinhub.business.service.support;

import com.chengyinhub.business.service.ChannelRedemptionGrantPort;
import com.chengyinhub.business.service.impl.DefaultClosedChannelRedemptionGrantPort;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 选择真实 grant 实现；只有默认关闭实现时如实返回未接线。
 * 两个以上真实实现视为配置错误，启动即失败（不允许静默挑一个）。
 */
@Component
public class ChannelRedemptionGrantPortResolver {

    private final ChannelRedemptionGrantPort port;
    private final boolean wired;

    public ChannelRedemptionGrantPortResolver(List<ChannelRedemptionGrantPort> discovered) {
        ChannelRedemptionGrantPort selected = null;
        if (discovered != null) {
            for (ChannelRedemptionGrantPort candidate : discovered) {
                if (candidate instanceof DefaultClosedChannelRedemptionGrantPort) continue;
                if (selected != null) {
                    throw new IllegalStateException("存在多个渠道 grant 端口实现，拒绝静默选择");
                }
                selected = candidate;
            }
        }
        if (selected == null) {
            selected = new DefaultClosedChannelRedemptionGrantPort();
        }
        this.port = selected;
        this.wired = !(selected instanceof DefaultClosedChannelRedemptionGrantPort);
    }

    public ChannelRedemptionGrantPort port() { return port; }

    public boolean isWired() { return wired; }
}
