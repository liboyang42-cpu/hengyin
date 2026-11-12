package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;

import com.chengyinhub.business.domain.HostTipIntent;

/**
 * 准备小费的结果。
 *
 * <p>{@code payable=false} + {@code reasonCode=CHANNEL_NOT_ENABLED} 表示<b>通道未启用</b>:
 * 意向单已记录,但没有任何收款发生。客户端不得显示"已支付"。
 *
 * @author chengyinhub
 */
public class HostTipPrepareResult
{
    private Long intentId;
    private Long hostMemberId;
    private BigDecimal amount;
    private String currency;
    private Integer hostSharePercent;
    private Integer channelFeeBearer;
    private String status;
    private boolean payable;
    private String reasonCode;

    public static HostTipPrepareResult of(HostTipIntent intent, boolean payable, String reasonCode) {
        HostTipPrepareResult result = new HostTipPrepareResult();
        result.intentId = intent.getId();
        result.hostMemberId = intent.getHostMemberId();
        result.amount = intent.getAmount();
        result.currency = intent.getCurrency();
        result.hostSharePercent = intent.getHostSharePercent();
        result.channelFeeBearer = intent.getChannelFeeBearer();
        result.status = statusLabel(intent.getStatus());
        result.payable = payable;
        result.reasonCode = reasonCode;
        return result;
    }

    private static String statusLabel(Integer status) {
        if (status == null) return "UNKNOWN";
        switch (status) {
            case HostTipIntent.STATUS_CREATED: return "CREATED";
            case HostTipIntent.STATUS_AWAITING_PAYMENT: return "AWAITING_PAYMENT";
            case HostTipIntent.STATUS_PAID: return "PAID";
            case HostTipIntent.STATUS_CANCELLED: return "CANCELLED";
            case HostTipIntent.STATUS_REFUNDED: return "REFUNDED";
            case HostTipIntent.STATUS_CHANNEL_UNSUPPORTED: return "CHANNEL_UNSUPPORTED";
            default: return "UNKNOWN";
        }
    }

    public Long getIntentId() { return intentId; }
    public Long getHostMemberId() { return hostMemberId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Integer getHostSharePercent() { return hostSharePercent; }
    public Integer getChannelFeeBearer() { return channelFeeBearer; }
    public String getStatus() { return status; }
    public boolean isPayable() { return payable; }
    public String getReasonCode() { return reasonCode; }
}
