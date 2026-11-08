package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 渠道码兑换回执 marketing_channel_redemption。
 *
 * <p>{@code UNIQUE(voucher_id)} 保证一码只产生一份权益；
 * {@code UNIQUE(member_id, request_id)} 保证同一玩家同一幂等键只回放一条回执，
 * 同 request 不同 payload 由 {@link #requestFingerprint} 比对拒绝。
 *
 * <p>{@link #registrationId} 指向既有 {@code cms_registration} 权威事实；
 * {@link #grantRef} 是 M2-F grant 端口的不可变回执。本域不另造钱包、权益表或支付状态。
 * 撤销只改授权状态，不复活已核销内容。
 */
public class ChannelRedemption {

    public static final int STATUS_ACTIVE = 0;
    public static final int STATUS_REVOKED = 1;

    private Long id;
    private Long voucherId;
    private Long memberId;
    private String channelOrderNo;
    private Long registrationId;
    private String registrationNo;
    private String registrationIdsJson;
    private String entitlementIdsJson;
    private String grantRef;
    private String receiptNo;
    private String requestId;
    private String requestFingerprint;
    private Integer status;
    private String revokeReason;
    private Date validFrom;
    private Date validTo;
    private Date redeemedAt;
    private Date revokedAt;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getVoucherId() { return voucherId; }
    public void setVoucherId(Long voucherId) { this.voucherId = voucherId; }

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }

    public String getChannelOrderNo() { return channelOrderNo; }
    public void setChannelOrderNo(String channelOrderNo) { this.channelOrderNo = channelOrderNo; }

    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }

    public String getRegistrationNo() { return registrationNo; }
    public void setRegistrationNo(String registrationNo) { this.registrationNo = registrationNo; }

    public String getRegistrationIdsJson() { return registrationIdsJson; }
    public void setRegistrationIdsJson(String registrationIdsJson) { this.registrationIdsJson = registrationIdsJson; }

    public String getEntitlementIdsJson() { return entitlementIdsJson; }
    public void setEntitlementIdsJson(String entitlementIdsJson) { this.entitlementIdsJson = entitlementIdsJson; }

    public String getGrantRef() { return grantRef; }
    public void setGrantRef(String grantRef) { this.grantRef = grantRef; }

    public String getReceiptNo() { return receiptNo; }
    public void setReceiptNo(String receiptNo) { this.receiptNo = receiptNo; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public String getRevokeReason() { return revokeReason; }
    public void setRevokeReason(String revokeReason) { this.revokeReason = revokeReason; }

    public Date getValidFrom() { return validFrom; }
    public void setValidFrom(Date validFrom) { this.validFrom = validFrom; }

    public Date getValidTo() { return validTo; }
    public void setValidTo(Date validTo) { this.validTo = validTo; }

    public Date getRedeemedAt() { return redeemedAt; }
    public void setRedeemedAt(Date redeemedAt) { this.redeemedAt = redeemedAt; }

    public Date getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Date revokedAt) { this.revokedAt = revokedAt; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
