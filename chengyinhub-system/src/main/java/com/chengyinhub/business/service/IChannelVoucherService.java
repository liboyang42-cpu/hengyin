package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.ChannelVoucherIssueRequest;
import com.chengyinhub.business.domain.dto.ChannelVoucherRevokeRequest;
import com.chengyinhub.business.domain.dto.ChannelVoucherView;

import java.util.Date;
import java.util.List;

/** 渠道兑换码发行、撤销与只读投影。 */
public interface IChannelVoucherService {

    ChannelVoucherView issue(long operatorUserId, ChannelVoucherIssueRequest request);

    ChannelVoucherView revoke(long operatorUserId, Long voucherId, ChannelVoucherRevokeRequest request);

    ChannelVoucherView get(long operatorUserId, Long voucherId);

    List<ChannelVoucherView> list(Long accountId, Date from, Date to, int limit);

    /** 渠道订单验证被撤销时联动撤销尚未使用/未兑换的码；已使用权益拒绝并保持原状。 */
    ChannelVoucherView revokeByVerification(Long verificationId, String reason, String source);
}
