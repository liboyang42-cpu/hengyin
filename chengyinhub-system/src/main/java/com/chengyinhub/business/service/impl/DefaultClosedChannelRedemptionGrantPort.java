package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.ChannelGrantCommand;
import com.chengyinhub.business.domain.dto.ChannelGrantResult;
import com.chengyinhub.business.domain.dto.ChannelGrantRevocation;
import com.chengyinhub.business.exception.ChannelVoucherException;
import com.chengyinhub.business.service.ChannelRedemptionGrantPort;
import org.springframework.stereotype.Component;

/**
 * 权威 grant 端口未接线时的默认实现。它不创建注册单、不发权益、不伪造 PAID；
 * 兑换接口会如实返回 {@code GRANT_PORT_NOT_WIRED}，由 M2-F 接上实现后自动生效。
 */
@Component
public class DefaultClosedChannelRedemptionGrantPort implements ChannelRedemptionGrantPort {

    @Override
    public ChannelGrantResult grant(ChannelGrantCommand command) {
        throw ChannelVoucherException.unavailable("GRANT_PORT_NOT_WIRED",
                "权威注册/权益 grant 端口未接线，渠道兑换暂不启用");
    }

    @Override
    public ChannelGrantResult revoke(ChannelGrantRevocation command) {
        throw ChannelVoucherException.unavailable("GRANT_PORT_NOT_WIRED",
                "权威注册/权益 grant 端口未接线，渠道撤销暂不启用");
    }
}
