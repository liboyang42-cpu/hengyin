package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 渠道订单验证事实 marketing_channel_order_verification。
 *
 * <p><b>这是 L1 的唯一可信来源</b>：只有 {@code ChannelOrderVerificationProvider}
 * 返回 VERIFIED 并核对金额/币种/商品数量后才写 {@link #STATUS_VERIFIED}。
 * 客户端提交的码、邮箱、前缀、运营手填金额都不会直接变成这里的一行。
 *
 * <p><b>金额不在本域转成平台已收</b>：{@link #amount} 是渠道侧订单实付快照，
 * 仅用于对账；本域不写 settlement_detail、merchant_payable、可提现余额。
 *
 * <p>{@link #eventId} 为渠道回调事件唯一标识，与 {@code provider_key} 组成入站幂等键；
 * 同事件重复投递只保留一行，绝不重复发码。
 */
public class ChannelOrderVerification {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_VERIFIED = 1;
    public static final int STATUS_REJECTED = 2;
    public static final int STATUS_REVOKED = 3;

    public static final int MODE_PULL = 1;
    public static final int MODE_INBOUND = 2;

    private Long id;
    /** 回调先到的未知渠道允许为空；空值只用于留痕拒绝，不允许发码。 */
    private Long accountId;
    private String channelKey;
    private String providerKey;
    private String channelOrderNo;
    private String externalReference;
    private String eventId;
    private BigDecimal amount;
    private String currency;
    private String skuJson;
    private Long productId;
    private Integer quantity;
    private Integer status;
    private String reasonCode;
    private Date verifiedAt;
    private Date expiresAt;
    private String rawHash;
    private String requestId;
    private String requestFingerprint;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }

    public String getChannelKey() { return channelKey; }
    public void setChannelKey(String channelKey) { this.channelKey = channelKey; }

    public String getProviderKey() { return providerKey; }
    public void setProviderKey(String providerKey) { this.providerKey = providerKey; }

    public String getChannelOrderNo() { return channelOrderNo; }
    public void setChannelOrderNo(String channelOrderNo) { this.channelOrderNo = channelOrderNo; }

    public String getExternalReference() { return externalReference; }
    public void setExternalReference(String externalReference) { this.externalReference = externalReference; }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getSkuJson() { return skuJson; }
    public void setSkuJson(String skuJson) { this.skuJson = skuJson; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }

    public Date getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Date verifiedAt) { this.verifiedAt = verifiedAt; }

    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; }

    public String getRawHash() { return rawHash; }
    public void setRawHash(String rawHash) { this.rawHash = rawHash; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
