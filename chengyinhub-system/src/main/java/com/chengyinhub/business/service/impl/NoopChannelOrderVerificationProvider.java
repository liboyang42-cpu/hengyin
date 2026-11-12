package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ChannelOrderVerification;
import com.chengyinhub.business.domain.dto.ChannelVerificationCommand;
import com.chengyinhub.business.domain.dto.ChannelVerificationOutcome;
import com.chengyinhub.business.service.ChannelOrderVerificationProvider;
import org.springframework.stereotype.Component;

/**
 * 未配置真实渠道时的默认端口。它不验签、不发码，只如实返回未启用。
 * 这不是 mock 成功：调用方看到 NOT_CONFIGURED 后必须停止，不得继续走发码。
 */
@Component
public class NoopChannelOrderVerificationProvider implements ChannelOrderVerificationProvider {

    public static final String PROVIDER_KEY = "__not_configured__";

    @Override
    public String providerKey() {
        return PROVIDER_KEY;
    }

    @Override
    public ChannelVerificationOutcome verify(ChannelVerificationCommand command) {
        ChannelVerificationOutcome outcome = new ChannelVerificationOutcome();
        outcome.setStatus(ChannelOrderVerification.STATUS_REJECTED);
        outcome.setReasonCode("PROVIDER_NOT_CONFIGURED");
        return outcome;
    }
}
