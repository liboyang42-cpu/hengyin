package com.chengyinhub.business.domain;

import java.util.Date;

public class ContributionRewardLedger {
    public static final String STATUS_PROVISIONAL = "PROVISIONAL";
    public static final String STATUS_CREDITED = "CREDITED";
    public static final String STATUS_VOIDED = "VOIDED";

    private Long id;
    private Long contributionId;
    private Long contributorMemberId;
    private String businessKey;
    private String status;
    private Integer points;
    private String externalLedgerNo;
    private Date creditedAt;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getContributionId() { return contributionId; }
    public void setContributionId(Long contributionId) { this.contributionId = contributionId; }
    public Long getContributorMemberId() { return contributorMemberId; }
    public void setContributorMemberId(Long contributorMemberId) { this.contributorMemberId = contributorMemberId; }
    public String getBusinessKey() { return businessKey; }
    public void setBusinessKey(String businessKey) { this.businessKey = businessKey; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getPoints() { return points; }
    public void setPoints(Integer points) { this.points = points; }
    public String getExternalLedgerNo() { return externalLedgerNo; }
    public void setExternalLedgerNo(String externalLedgerNo) { this.externalLedgerNo = externalLedgerNo; }
    public Date getCreditedAt() { return creditedAt; }
    public void setCreditedAt(Date creditedAt) { this.creditedAt = creditedAt; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
