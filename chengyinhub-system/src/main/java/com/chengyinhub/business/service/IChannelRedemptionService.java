package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.ChannelReceiptRequest;
import com.chengyinhub.business.domain.dto.ChannelRedeemRequest;
import com.chengyinhub.business.domain.dto.ChannelRedeemResult;
import com.chengyinhub.business.domain.dto.ChannelReconciliationView;

import java.util.Date;

/** 玩家兑换、回执回读与渠道对账。 */
public interface IChannelRedemptionService {

    ChannelRedeemResult redeem(long memberId, ChannelRedeemRequest request);

    ChannelRedeemResult receipt(long memberId, ChannelReceiptRequest request);

    ChannelReconciliationView reconcile(Long accountId, Date from, Date to);
}
