package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 主持小费配置(host_tip_config)。
 *
 * <p><b>规则边界</b>:小费 100% 归主持(host),平台不抽成;支付通道手续费由谁承担
 * ({@code channelFeeBearer})单独配置,不隐含在"平台纯支出"里。本配置不创建任何
 * 钱包、余额或代收账户,也不表示支付通道已经可用。
 *
 * <p>{@code paymentChannel} 为空 = 未接入受支持的收款通道;此时只能准备意向单并明确返回
 * "通道未启用",不得伪造收款、不得给余额加钱。
 *
 * @author chengyinhub
 */
public class HostTipConfig
{
    /** 通道手续费由 host 承担。 */
    public static final int FEE_BEARER_HOST = 0;
    /** 通道手续费由平台承担(单独配置,不记为代收 host 资金的支出)。 */
    public static final int FEE_BEARER_PLATFORM = 1;

    private Long id;
    /** 受益主持会员 id:小费明确给谁。 */
    private Long hostMemberId;
    /** 作用域俱乐部;0 = 不限定俱乐部。 */
    private Long clubId;
    /** 0=未启用;1=已启用(仍需受支持收款通道)。 */
    private Integer enabled;
    /** 归主持比例,必须 100(平台不抽成);保留字段便于审计。 */
    private Integer hostSharePercent;
    /** 通道手续费承担方:{@link #FEE_BEARER_HOST} / {@link #FEE_BEARER_PLATFORM}。 */
    private Integer channelFeeBearer;
    /** 单笔最低小费;0 = 不设下限。 */
    private BigDecimal minAmount;
    private String currency;
    /** 受支持的收款通道标识;为空 = 未启用。 */
    private String paymentChannel;
    private Integer configVersion;
    private String delFlag;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getHostMemberId() { return hostMemberId; }
    public void setHostMemberId(Long hostMemberId) { this.hostMemberId = hostMemberId; }
    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
    public Integer getHostSharePercent() { return hostSharePercent; }
    public void setHostSharePercent(Integer hostSharePercent) { this.hostSharePercent = hostSharePercent; }
    public Integer getChannelFeeBearer() { return channelFeeBearer; }
    public void setChannelFeeBearer(Integer channelFeeBearer) { this.channelFeeBearer = channelFeeBearer; }
    public BigDecimal getMinAmount() { return minAmount; }
    public void setMinAmount(BigDecimal minAmount) { this.minAmount = minAmount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getPaymentChannel() { return paymentChannel; }
    public void setPaymentChannel(String paymentChannel) { this.paymentChannel = paymentChannel; }
    public Integer getConfigVersion() { return configVersion; }
    public void setConfigVersion(Integer configVersion) { this.configVersion = configVersion; }
    public String getDelFlag() { return delFlag; }
    public void setDelFlag(String delFlag) { this.delFlag = delFlag; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
