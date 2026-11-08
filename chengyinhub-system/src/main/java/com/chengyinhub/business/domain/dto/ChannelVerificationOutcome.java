package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;
import java.util.Date;

/**
 * provider 返回的验证结果。{@link #status} 取
 * {@code ChannelOrderVerification.STATUS_*}；只有 VERIFIED 才可发码。
 * 未配置/签名失败/未知单统一返回非 VERIFIED + reasonCode。
 */
public class ChannelVerificationOutcome {

    public static final int EVENT_PURCHASE = 1;
    public static final int EVENT_REFUND = 2;

    private int status;
    private int eventKind = EVENT_PURCHASE;
    private String channelRefundNo;
    private Boolean refundConfirmed;
    private Boolean fullRefund;
    private String channelKey;
    private String channelOrderNo;
    private String eventId;
    private Long productId;
    private Integer quantity;
    private BigDecimal amount;
    private String currency;
    private String skuJson;
    private String reasonCode;
    private String rawHash;
    private Date verifiedAt;

    public int getEventKind() { return eventKind; }
    public void setEventKind(int eventKind) { this.eventKind = eventKind; }

    public String getChannelRefundNo() { return channelRefundNo; }
    public void setChannelRefundNo(String channelRefundNo) { this.channelRefundNo = channelRefundNo; }

    public Boolean getRefundConfirmed() { return refundConfirmed; }
    public void setRefundConfirmed(Boolean refundConfirmed) { this.refundConfirmed = refundConfirmed; }

    public Boolean getFullRefund() { return fullRefund; }
    public void setFullRefund(Boolean fullRefund) { this.fullRefund = fullRefund; }

    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }

    public String getChannelKey() { return channelKey; }
    public void setChannelKey(String channelKey) { this.channelKey = channelKey; }

    public String getChannelOrderNo() { return channelOrderNo; }
    public void setChannelOrderNo(String channelOrderNo) { this.channelOrderNo = channelOrderNo; }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getSkuJson() { return skuJson; }
    public void setSkuJson(String skuJson) { this.skuJson = skuJson; }

    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }

    public String getRawHash() { return rawHash; }
    public void setRawHash(String rawHash) { this.rawHash = rawHash; }

    public Date getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Date verifiedAt) { this.verifiedAt = verifiedAt; }
}
