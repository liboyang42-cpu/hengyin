package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.ChannelAccountRequest;
import com.chengyinhub.business.domain.dto.ChannelAccountView;
import com.chengyinhub.business.domain.dto.ChannelInboundCallbackView;
import com.chengyinhub.business.domain.dto.ChannelOrderVerificationRequest;
import com.chengyinhub.business.domain.dto.ChannelOrderVerificationView;

import java.util.List;
import java.util.Map;

/** 渠道主体、订单验证与入站回调（L1 可信来源 + L2 端口边界）。 */
public interface IChannelOrderVerificationService {

    ChannelAccountView registerAccount(long operatorUserId, ChannelAccountRequest request);

    java.util.List<ChannelOrderVerificationView> listVerifications(Long accountId, Integer status,
                                                                   java.util.Date from, java.util.Date to,
                                                                   int limit);

    ChannelAccountView updateAccountStatus(long operatorUserId, Long accountId, Integer status);

    List<ChannelAccountView> listAccounts(Integer status, int limit);

    ChannelOrderVerificationView verifyByReference(long operatorUserId, ChannelOrderVerificationRequest request);

    ChannelOrderVerificationView getVerification(long operatorUserId, Long verificationId);

    ChannelOrderVerificationView revokeVerification(long operatorUserId, Long verificationId,
                                                   String reason, String requestId);

    ChannelInboundCallbackView handleInbound(String providerKey, Map<String, String> headers, String rawBody);
}
