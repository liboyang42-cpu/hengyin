package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 礼品领取来源 gift_entitlement（C06 礼品，<b>映射</b>而非第二权益）。
 *
 * <p>买家/领取人分开；领取码只存 SHA-256；领取一次 CAS（0→1）。
 * 领取成功后由 M2-F 的原注册服务产出 {@code cms_registration}，其 id 写入
 * {@link #claimedRegistrationId}。本表不承载有效期、席位或可玩判定。
 */
public class GiftEntitlement {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_CLAIMED = 1;
    public static final int STATUS_EXPIRED = 2;
    public static final int STATUS_REVOKED = 3;

    private Long id;
    private String publicId;
    private Long orderId;
    private Long productId;
    /** 买家已付的 cms_registration.id：单行语义下唯一可玩真源。 */
    private Long sourceRegistrationId;
    private Long buyerMemberId;
    private Long recipientMemberId;
    private String claimTokenHash;
    private Date claimExpiresAt;
    private Integer status;
    private Long claimedMemberId;
    /** 领取后由原注册服务产出的 cms_registration.id。 */
    private Long claimedRegistrationId;
    private Date claimedAt;
    private String claimRequestId;
    private String revokedReason;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPublicId() { return publicId; }
    public void setPublicId(String publicId) { this.publicId = publicId; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public Long getSourceRegistrationId() { return sourceRegistrationId; }
    public void setSourceRegistrationId(Long sourceRegistrationId) { this.sourceRegistrationId = sourceRegistrationId; }

    public Long getBuyerMemberId() { return buyerMemberId; }
    public void setBuyerMemberId(Long buyerMemberId) { this.buyerMemberId = buyerMemberId; }

    public Long getRecipientMemberId() { return recipientMemberId; }
    public void setRecipientMemberId(Long recipientMemberId) { this.recipientMemberId = recipientMemberId; }

    public String getClaimTokenHash() { return claimTokenHash; }
    public void setClaimTokenHash(String claimTokenHash) { this.claimTokenHash = claimTokenHash; }

    public Date getClaimExpiresAt() { return claimExpiresAt; }
    public void setClaimExpiresAt(Date claimExpiresAt) { this.claimExpiresAt = claimExpiresAt; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Long getClaimedMemberId() { return claimedMemberId; }
    public void setClaimedMemberId(Long claimedMemberId) { this.claimedMemberId = claimedMemberId; }

    public Long getClaimedRegistrationId() { return claimedRegistrationId; }
    public void setClaimedRegistrationId(Long claimedRegistrationId) { this.claimedRegistrationId = claimedRegistrationId; }

    public Date getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Date claimedAt) { this.claimedAt = claimedAt; }

    public String getClaimRequestId() { return claimRequestId; }
    public void setClaimRequestId(String claimRequestId) { this.claimRequestId = claimRequestId; }

    public String getRevokedReason() { return revokedReason; }
    public void setRevokedReason(String revokedReason) { this.revokedReason = revokedReason; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
