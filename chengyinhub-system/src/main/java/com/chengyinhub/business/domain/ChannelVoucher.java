package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 渠道兑换码 marketing_channel_voucher。
 *
 * <p><b>码只存哈希</b>：明文在发行响应中返回一次，库内只有 {@link #codeHash} 与公开标识
 * {@link #publicId}。客户端/运营不能凭明文反查，也不能用「码前缀 + 任意邮箱」代替渠道验证。
 *
 * <p><b>一验证一码</b>：{@code UNIQUE(verification_id)} 防止同一渠道订单重复发行；
 * {@code UNIQUE(account_id, request_id)} 防止同一次发行请求重试生成两张码。
 *
 * <p><b>库存预留</b>：{@link #inventoryTicketId} 非空时在发行阶段用既有
 * {@code oms_ticket.deductInventory} 原子预占；撤销/过期未兑换时用
 * {@code restoreInventory} 释放。已兑换且未使用的权益被撤销时按「已核销不复活」边界处理。
 */
public class ChannelVoucher {

    public static final int SUBJECT_BOUND_MEMBER = 1;
    public static final int SUBJECT_OPEN = 2;

    public static final int STATUS_ACTIVE = 0;
    public static final int STATUS_REDEEMED = 1;
    public static final int STATUS_REVOKED = 2;
    public static final int STATUS_EXPIRED = 3;

    public static final int INVENTORY_NONE = 0;
    public static final int INVENTORY_RESERVED = 1;
    public static final int INVENTORY_CONSUMED = 2;
    public static final int INVENTORY_RELEASED = 3;

    private Long id;
    private Long accountId;
    private Long verificationId;
    private String publicId;
    private String codeHash;
    private Long productId;
    private Long productRevision;
    private String grantSnapshotJson;
    private Integer quantity;
    private Integer seatCount;
    private Integer subjectMode;
    private Long boundMemberId;
    private Long inventoryTicketId;
    private Integer inventoryQuantity;
    private Integer inventoryState;
    private Integer status;
    private Date issuedAt;
    private Date expiresAt;
    private String revokeSource;
    private String revokeReason;
    private String revokeRequestId;
    private Date revokeRequestedAt;
    private Long issuedBy;
    private String requestId;
    private String requestFingerprint;
    private Date createTime;
    private Date updateTime;

    public boolean usableAt(Date now) {
        return Integer.valueOf(STATUS_ACTIVE).equals(status)
                && (expiresAt == null || expiresAt.after(now));
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }

    public Long getVerificationId() { return verificationId; }
    public void setVerificationId(Long verificationId) { this.verificationId = verificationId; }

    public String getPublicId() { return publicId; }
    public void setPublicId(String publicId) { this.publicId = publicId; }

    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public Long getProductRevision() { return productRevision; }
    public void setProductRevision(Long productRevision) { this.productRevision = productRevision; }

    public String getGrantSnapshotJson() { return grantSnapshotJson; }
    public void setGrantSnapshotJson(String grantSnapshotJson) { this.grantSnapshotJson = grantSnapshotJson; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public Integer getSeatCount() { return seatCount; }
    public void setSeatCount(Integer seatCount) { this.seatCount = seatCount; }

    public Integer getSubjectMode() { return subjectMode; }
    public void setSubjectMode(Integer subjectMode) { this.subjectMode = subjectMode; }

    public Long getBoundMemberId() { return boundMemberId; }
    public void setBoundMemberId(Long boundMemberId) { this.boundMemberId = boundMemberId; }

    public Long getInventoryTicketId() { return inventoryTicketId; }
    public void setInventoryTicketId(Long inventoryTicketId) { this.inventoryTicketId = inventoryTicketId; }

    public Integer getInventoryQuantity() { return inventoryQuantity; }
    public void setInventoryQuantity(Integer inventoryQuantity) { this.inventoryQuantity = inventoryQuantity; }

    public Integer getInventoryState() { return inventoryState; }
    public void setInventoryState(Integer inventoryState) { this.inventoryState = inventoryState; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Date getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Date issuedAt) { this.issuedAt = issuedAt; }

    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; }

    public String getRevokeReason() { return revokeReason; }
    public void setRevokeReason(String revokeReason) { this.revokeReason = revokeReason; }

    public String getRevokeRequestId() { return revokeRequestId; }
    public void setRevokeRequestId(String revokeRequestId) { this.revokeRequestId = revokeRequestId; }

    public Date getRevokeRequestedAt() { return revokeRequestedAt; }
    public void setRevokeRequestedAt(Date revokeRequestedAt) { this.revokeRequestedAt = revokeRequestedAt; }

    public String getRevokeSource() { return revokeSource; }
    public void setRevokeSource(String revokeSource) { this.revokeSource = revokeSource; }

    public Long getIssuedBy() { return issuedBy; }
    public void setIssuedBy(Long issuedBy) { this.issuedBy = issuedBy; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
