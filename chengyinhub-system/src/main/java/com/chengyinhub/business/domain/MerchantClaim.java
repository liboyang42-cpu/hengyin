package com.chengyinhub.business.domain;

import java.util.Date;
import java.util.ArrayList;
import java.util.List;

public class MerchantClaim {
    private Long id;
    private Long merchantId;
    private Long claimantMemberId;
    private String contactName;
    private String contactPhone;
    private String statement;
    private String submitRequestId;
    private String submitFingerprint;
    private String status;
    private Long auditActorId;
    private String auditReason;
    private String auditRequestId;
    private String auditFingerprint;
    private Integer version;
    private Date submittedAt;
    private Date auditedAt;
    private Date createTime;
    private Date updateTime;
    private List<MerchantClaimProof> proofs = new ArrayList<>();
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public Long getClaimantMemberId() { return claimantMemberId; }
    public void setClaimantMemberId(Long claimantMemberId) { this.claimantMemberId = claimantMemberId; }
    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public String getStatement() { return statement; }
    public void setStatement(String statement) { this.statement = statement; }
    public String getSubmitRequestId() { return submitRequestId; }
    public void setSubmitRequestId(String submitRequestId) { this.submitRequestId = submitRequestId; }
    public String getSubmitFingerprint() { return submitFingerprint; }
    public void setSubmitFingerprint(String submitFingerprint) { this.submitFingerprint = submitFingerprint; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getAuditActorId() { return auditActorId; }
    public void setAuditActorId(Long auditActorId) { this.auditActorId = auditActorId; }
    public String getAuditReason() { return auditReason; }
    public void setAuditReason(String auditReason) { this.auditReason = auditReason; }
    public String getAuditRequestId() { return auditRequestId; }
    public void setAuditRequestId(String auditRequestId) { this.auditRequestId = auditRequestId; }
    public String getAuditFingerprint() { return auditFingerprint; }
    public void setAuditFingerprint(String auditFingerprint) { this.auditFingerprint = auditFingerprint; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Date getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Date submittedAt) { this.submittedAt = submittedAt; }
    public Date getAuditedAt() { return auditedAt; }
    public void setAuditedAt(Date auditedAt) { this.auditedAt = auditedAt; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
    public Date getUpdatedAt() { return updateTime; }
    public String getSource() { return "CHENGYIN"; }
    public List<MerchantClaimProof> getProofs() { return proofs; }
    public void setProofs(List<MerchantClaimProof> proofs) { this.proofs = proofs; }
}
