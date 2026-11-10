package com.chengyinhub.business.domain;

/** 电商收付通二级商户进件（ecom_sub_merchant）。 */
public class EcomSubMerchant {
    private Long id;
    private String ownerType;
    private Long ownerId;
    private String outRequestNo;
    private Long applymentId;
    private String applymentState;
    private String applymentStateDesc;
    private String signUrl;
    private String subMchid;
    private java.util.Date lastQueriedAt;
    private String createdBy;
    private java.util.Date createdAt;
    private java.util.Date updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOwnerType() { return ownerType; }
    public void setOwnerType(String ownerType) { this.ownerType = ownerType; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public String getOutRequestNo() { return outRequestNo; }
    public void setOutRequestNo(String outRequestNo) { this.outRequestNo = outRequestNo; }
    public Long getApplymentId() { return applymentId; }
    public void setApplymentId(Long applymentId) { this.applymentId = applymentId; }
    public String getApplymentState() { return applymentState; }
    public void setApplymentState(String applymentState) { this.applymentState = applymentState; }
    public String getApplymentStateDesc() { return applymentStateDesc; }
    public void setApplymentStateDesc(String applymentStateDesc) { this.applymentStateDesc = applymentStateDesc; }
    public String getSignUrl() { return signUrl; }
    public void setSignUrl(String signUrl) { this.signUrl = signUrl; }
    public String getSubMchid() { return subMchid; }
    public void setSubMchid(String subMchid) { this.subMchid = subMchid; }
    public java.util.Date getLastQueriedAt() { return lastQueriedAt; }
    public void setLastQueriedAt(java.util.Date lastQueriedAt) { this.lastQueriedAt = lastQueriedAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public java.util.Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.util.Date createdAt) { this.createdAt = createdAt; }
    public java.util.Date getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.util.Date updatedAt) { this.updatedAt = updatedAt; }
}
