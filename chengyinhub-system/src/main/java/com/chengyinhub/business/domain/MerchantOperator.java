package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.util.Date;

/** 商家经营团队员工关系。Owner 由 mms_merchant.member_id 派生，不写入本表。 */
public class MerchantOperator extends BaseEntity {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";

    private Long id;
    private Long merchantId;
    private Long operatorMemberId;
    private Long activeOperatorMemberId;
    private String roleCode;
    private String status;
    private Date acceptedAt;
    private Long revokedByMemberId;
    private Date revokedAt;
    private String revokeReason;
    private Integer version;
    private String operatorNickname;
    private String operatorAvatar;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public Long getOperatorMemberId() { return operatorMemberId; }
    public void setOperatorMemberId(Long operatorMemberId) { this.operatorMemberId = operatorMemberId; }
    public Long getActiveOperatorMemberId() { return activeOperatorMemberId; }
    public void setActiveOperatorMemberId(Long activeOperatorMemberId) { this.activeOperatorMemberId = activeOperatorMemberId; }
    public String getRoleCode() { return roleCode; }
    public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Date getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(Date acceptedAt) { this.acceptedAt = acceptedAt; }
    public Long getRevokedByMemberId() { return revokedByMemberId; }
    public void setRevokedByMemberId(Long revokedByMemberId) { this.revokedByMemberId = revokedByMemberId; }
    public Date getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Date revokedAt) { this.revokedAt = revokedAt; }
    public String getRevokeReason() { return revokeReason; }
    public void setRevokeReason(String revokeReason) { this.revokeReason = revokeReason; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getOperatorNickname() { return operatorNickname; }
    public void setOperatorNickname(String operatorNickname) { this.operatorNickname = operatorNickname; }
    public String getOperatorAvatar() { return operatorAvatar; }
    public void setOperatorAvatar(String operatorAvatar) { this.operatorAvatar = operatorAvatar; }
}
