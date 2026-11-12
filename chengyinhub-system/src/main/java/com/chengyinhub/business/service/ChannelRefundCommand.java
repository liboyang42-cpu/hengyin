package com.chengyinhub.business.service;

import java.math.BigDecimal;

/** 渠道退款完成后的撤销命令。全部字段都是**查询键/核对值**,事实以 reader 回读为准。 */
public final class ChannelRefundCommand {

    /** 渠道订单号(= 本域准入行的 parent_order_no)。 */
    public String parentOrderNo;
    public String providerKey;
    /** 渠道退款单号。 */
    public String channelRefundNo;
    /** 渠道退款事实主键(渠道域不可变事实表)。 */
    public long refundFactId;
    /** 渠道退款事实哈希,仅用于定位/核对。 */
    public String refundFactHash;
    /** 调用方声称的退款金额(元),用于一致性核对。 */
    public BigDecimal claimedAmount;
    /** 调用方声称的币种。 */
    public String claimedCurrency;
    /** 幂等键(渠道域生成)。 */
    public String requestId;
}
