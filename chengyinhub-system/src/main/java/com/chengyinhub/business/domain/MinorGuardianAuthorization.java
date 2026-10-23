package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * DOC2 F7 未成年人监护授权：被监护人 × 监护人 × 用途 × 政策版本 × 有效期。
 * 不含任何证件号、手机号、验证码或生物特征；邀请码只存 SHA-256 摘要。
 */
public class MinorGuardianAuthorization {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";
    public static final String EVIDENCE_MUTUAL_ACCOUNT_CONFIRM = "MUTUAL_ACCOUNT_CONFIRM";

    private Long id;
    private Long guardianMemberId;
    private Long wardMemberId;
    private String purpose;
    private String policyVersion;
    private String relation;
    private String evidenceMethod;
    private String status;
    private String requestId;
    private String inviteTokenHash;
    private Date inviteExpiresAt;
    private Integer validDays;
    private Date validFrom;
    private Date validUntil;
    private Date wardConfirmedAt;
    private Date revokedAt;
    private Long revokedBy;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getGuardianMemberId() { return guardianMemberId; } public void setGuardianMemberId(Long v) { guardianMemberId = v; }
    public Long getWardMemberId() { return wardMemberId; } public void setWardMemberId(Long v) { wardMemberId = v; }
    public String getPurpose() { return purpose; } public void setPurpose(String v) { purpose = v; }
    public String getPolicyVersion() { return policyVersion; } public void setPolicyVersion(String v) { policyVersion = v; }
    public String getRelation() { return relation; } public void setRelation(String v) { relation = v; }
    public String getEvidenceMethod() { return evidenceMethod; } public void setEvidenceMethod(String v) { evidenceMethod = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public String getRequestId() { return requestId; } public void setRequestId(String v) { requestId = v; }
    public String getInviteTokenHash() { return inviteTokenHash; } public void setInviteTokenHash(String v) { inviteTokenHash = v; }
    public Date getInviteExpiresAt() { return inviteExpiresAt; } public void setInviteExpiresAt(Date v) { inviteExpiresAt = v; }
    public Integer getValidDays() { return validDays; } public void setValidDays(Integer v) { validDays = v; }
    public Date getValidFrom() { return validFrom; } public void setValidFrom(Date v) { validFrom = v; }
    public Date getValidUntil() { return validUntil; } public void setValidUntil(Date v) { validUntil = v; }
    public Date getWardConfirmedAt() { return wardConfirmedAt; } public void setWardConfirmedAt(Date v) { wardConfirmedAt = v; }
    public Date getRevokedAt() { return revokedAt; } public void setRevokedAt(Date v) { revokedAt = v; }
    public Long getRevokedBy() { return revokedBy; } public void setRevokedBy(Long v) { revokedBy = v; }
    public Date getCreateTime() { return createTime; } public void setCreateTime(Date v) { createTime = v; }
    public Date getUpdateTime() { return updateTime; } public void setUpdateTime(Date v) { updateTime = v; }
}
