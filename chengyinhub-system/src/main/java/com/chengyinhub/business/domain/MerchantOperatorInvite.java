package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.util.Date;

/** 商家员工单次邀请。原 token 只在创建响应中出现一次，数据库仅保存 tokenHash。 */
public class MerchantOperatorInvite extends BaseEntity {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_REVOKED = "REVOKED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    private Long id;
    private Long merchantId;
    private String roleCode;
    private String tokenHash;
    private String status;
    private Long invitedByMemberId;
    private Date invitedAt;
    private Date expiresAt;
    private Long acceptedByMemberId;
    private Date acceptedAt;
    private Long revokedByMemberId;
    private Date revokedAt;
    private String revokeReason;
    private String requestId;
    private Integer version;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public String getRoleCode() { return roleCode; }
    public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getInvitedByMemberId() { return invitedByMemberId; }
    public void setInvitedByMemberId(Long invitedByMemberId) { this.invitedByMemberId = invitedByMemberId; }
    public Date getInvitedAt() { return invitedAt; }
    public void setInvitedAt(Date invitedAt) { this.invitedAt = invitedAt; }
    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; }
    public Long getAcceptedByMemberId() { return acceptedByMemberId; }
    public void setAcceptedByMemberId(Long acceptedByMemberId) { this.acceptedByMemberId = acceptedByMemberId; }
    public Date getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(Date acceptedAt) { this.acceptedAt = acceptedAt; }
    public Long getRevokedByMemberId() { return revokedByMemberId; }
    public void setRevokedByMemberId(Long revokedByMemberId) { this.revokedByMemberId = revokedByMemberId; }
    public Date getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Date revokedAt) { this.revokedAt = revokedAt; }
    public String getRevokeReason() { return revokeReason; }
    public void setRevokeReason(String revokeReason) { this.revokeReason = revokeReason; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
