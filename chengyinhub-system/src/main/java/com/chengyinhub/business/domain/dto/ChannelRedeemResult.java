package com.chengyinhub.business.domain.dto;

import java.util.Date;

/** 兑换结果与回执回读共用形状；{@link #replayed} 只在校验请求回放时为真。 */
public class ChannelRedeemResult {

    private String receiptNo;
    private Integer status;
    private Long voucherId;
    private String voucherPublicId;
    private String channelOrderNo;
    private Long productId;
    private String productName;
    private Integer quantity;
    private Integer seatCount;
    private Long registrationId;
    private String registrationNo;
    private java.util.List<Long> entitlementIds;
    private Date validFrom;
    private Date validTo;
    private Date redeemedAt;
    private Date revokedAt;
    private Boolean replayed;
    private String reasonCode;

    public String getReceiptNo() { return receiptNo; }
    public void setReceiptNo(String receiptNo) { this.receiptNo = receiptNo; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Long getVoucherId() { return voucherId; }
    public void setVoucherId(Long voucherId) { this.voucherId = voucherId; }

    public String getVoucherPublicId() { return voucherPublicId; }
    public void setVoucherPublicId(String voucherPublicId) { this.voucherPublicId = voucherPublicId; }

    public String getChannelOrderNo() { return channelOrderNo; }
    public void setChannelOrderNo(String channelOrderNo) { this.channelOrderNo = channelOrderNo; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public Integer getSeatCount() { return seatCount; }
    public void setSeatCount(Integer seatCount) { this.seatCount = seatCount; }

    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }

    public String getRegistrationNo() { return registrationNo; }
    public void setRegistrationNo(String registrationNo) { this.registrationNo = registrationNo; }

    public java.util.List<Long> getEntitlementIds() { return entitlementIds; }
    public void setEntitlementIds(java.util.List<Long> entitlementIds) { this.entitlementIds = entitlementIds; }

    public Date getValidFrom() { return validFrom; }
    public void setValidFrom(Date validFrom) { this.validFrom = validFrom; }

    public Date getValidTo() { return validTo; }
    public void setValidTo(Date validTo) { this.validTo = validTo; }

    public Date getRedeemedAt() { return redeemedAt; }
    public void setRedeemedAt(Date redeemedAt) { this.redeemedAt = redeemedAt; }

    public Date getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Date revokedAt) { this.revokedAt = revokedAt; }

    public Boolean getReplayed() { return replayed; }
    public void setReplayed(Boolean replayed) { this.replayed = replayed; }

    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
}
