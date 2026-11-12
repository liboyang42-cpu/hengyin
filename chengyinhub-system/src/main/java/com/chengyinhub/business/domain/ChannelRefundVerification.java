package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 渠道退款完成事实 marketing_channel_refund_verification。
 *
 * <p>只有 provider 返回“渠道侧已确认退款”才落 {@link #STATUS_VERIFIED}；
 * 申请/未到账不撤准入。退款金额与币种必须与原购买验证事实一致；
 * 部分退款当前没有既定撤权口径，保留为未支持，不擅自按比例撤。
 */
public class ChannelRefundVerification {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_VERIFIED = 1;
    public static final int STATUS_REJECTED = 2;

    private Long id;
    private Long accountId;
    private Long purchaseVerificationId;
    private String channelKey;
    private String providerKey;
    private String channelOrderNo;
    private String channelRefundNo;
    private String eventId;
    private BigDecimal amount;
    private String currency;
    private Integer fullRefund;
    private Integer status;
    private Integer consumed;
    private String reasonCode;
    private String rawHash;
    private String requestId;
    private String requestFingerprint;
    private Date verifiedAt;
    private Date consumedAt;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }

    public Long getPurchaseVerificationId() { return purchaseVerificationId; }
    public void setPurchaseVerificationId(Long purchaseVerificationId) { this.purchaseVerificationId = purchaseVerificationId; }

    public String getChannelKey() { return channelKey; }
    public void setChannelKey(String channelKey) { this.channelKey = channelKey; }

    public String getProviderKey() { return providerKey; }
    public void setProviderKey(String providerKey) { this.providerKey = providerKey; }

    public String getChannelOrderNo() { return channelOrderNo; }
    public void setChannelOrderNo(String channelOrderNo) { this.channelOrderNo = channelOrderNo; }

    public String getChannelRefundNo() { return channelRefundNo; }
    public void setChannelRefundNo(String channelRefundNo) { this.channelRefundNo = channelRefundNo; }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Integer getFullRefund() { return fullRefund; }
    public void setFullRefund(Integer fullRefund) { this.fullRefund = fullRefund; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Integer getConsumed() { return consumed; }
    public void setConsumed(Integer consumed) { this.consumed = consumed; }

    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }

    public String getRawHash() { return rawHash; }
    public void setRawHash(String rawHash) { this.rawHash = rawHash; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }

    public Date getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Date verifiedAt) { this.verifiedAt = verifiedAt; }

    public Date getConsumedAt() { return consumedAt; }
    public void setConsumedAt(Date consumedAt) { this.consumedAt = consumedAt; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
