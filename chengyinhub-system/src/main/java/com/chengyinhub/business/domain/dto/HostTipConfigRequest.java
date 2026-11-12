package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;

/**
 * 主持小费配置请求。
 *
 * <p>⚠️ 没有 {@code hostSharePercent} 字段:分成固定 100% 归 host,
 * 想改只能改代码并重新审批,不能靠一次请求把平台抽成打开。
 *
 * @author chengyinhub
 */
public class HostTipConfigRequest
{
    /** 作用域俱乐部;0 或不传 = 不限定。 */
    private Long clubId;
    private Boolean enabled;
    /** 0=host 承担通道手续费 1=平台承担;不传按 host 承担。 */
    private Integer channelFeeBearer;
    private BigDecimal minAmount;
    private String currency;
    /** 受支持的收款通道标识;不传 = 未启用。 */
    private String paymentChannel;

    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Integer getChannelFeeBearer() { return channelFeeBearer; }
    public void setChannelFeeBearer(Integer channelFeeBearer) { this.channelFeeBearer = channelFeeBearer; }
    public BigDecimal getMinAmount() { return minAmount; }
    public void setMinAmount(BigDecimal minAmount) { this.minAmount = minAmount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getPaymentChannel() { return paymentChannel; }
    public void setPaymentChannel(String paymentChannel) { this.paymentChannel = paymentChannel; }
}
