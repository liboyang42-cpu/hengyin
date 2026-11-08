package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.ChannelInboundCallbackView;
import com.chengyinhub.business.domain.dto.ChannelVerificationOutcome;

/** 渠道退款完成事实的落库与消费（M2 原注册撤销的唯一 caller）。 */
public interface IChannelRefundService {

    ChannelInboundCallbackView handleVerifiedRefund(String providerKey,
                                                    ChannelVerificationOutcome outcome,
                                                    String rawBody);

    /**
     * 补偿驱动：把已通过服务端验证、但尚未被消费的渠道退款事实重新交给
     * M2 的撤销入口（撤销 + 入队同一事务）。幂等：已撤销的行返回重放，不会二次冲销。
     *
     * <p>存在的理由：退款事实先独立提交，若紧接着的消费事务失败（费用未核、任务写失败、
     * 进程崩溃）且渠道/provider 不再重投，这条链路就会停在半途。补偿任务让它可恢复。
     */
    int retryPendingConsumptions(int limit);
}
