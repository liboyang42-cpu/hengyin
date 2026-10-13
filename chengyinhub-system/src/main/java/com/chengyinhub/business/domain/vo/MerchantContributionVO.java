package com.chengyinhub.business.domain.vo;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.ContributionRewardLedger;
import com.chengyinhub.business.domain.MerchantContribution;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MerchantContributionVO {
    private Long id;
    private Long merchantId;
    private String contributionType;
    private Map<String, String> fieldChanges = new LinkedHashMap<>();
    private String issueType;
    private String description;
    private List<Long> evidenceAssetIds = new ArrayList<>();
    private String reviewStatus;
    private String reviewReason;
    private Boolean canSupplement;
    private Date reviewedAt;
    private Date mergedAt;
    private Integer version;
    private Date createTime;
    private String rewardStatus;
    private Integer rewardPoints;
    private String rewardLedgerNo;
    private Date rewardCreditedAt;

    public static MerchantContributionVO from(MerchantContribution row,
                                              ContributionRewardLedger reward) {
        MerchantContributionVO vo = new MerchantContributionVO();
        vo.id = row.getId();
        vo.merchantId = row.getMerchantId();
        vo.contributionType = row.getContributionType();
        if (row.getFieldChangesJson() != null) {
            Map<String, String> parsed = JSON.parseObject(row.getFieldChangesJson(), Map.class);
            if (parsed != null) vo.fieldChanges.putAll(parsed);
        }
        vo.issueType = row.getIssueType();
        vo.description = row.getDescription();
        if (row.getEvidenceAssetIdsJson() != null) {
            vo.evidenceAssetIds = JSON.parseArray(row.getEvidenceAssetIdsJson(), Long.class);
        }
        vo.reviewStatus = row.getReviewStatus();
        vo.reviewReason = row.getReviewReason();
        vo.canSupplement = row.getCanSupplement();
        vo.reviewedAt = row.getReviewedAt();
        vo.mergedAt = row.getMergedAt();
        vo.version = row.getVersion();
        vo.createTime = row.getCreateTime();
        if (reward != null) {
            vo.rewardStatus = reward.getStatus();
            vo.rewardPoints = reward.getPoints();
            vo.rewardLedgerNo = reward.getExternalLedgerNo();
            vo.rewardCreditedAt = reward.getCreditedAt();
        } else if (MerchantContribution.STATUS_REJECTED.equals(row.getReviewStatus())) {
            vo.rewardStatus = "NOT_CREDITED";
        } else {
            vo.rewardStatus = "PENDING_REVIEW";
        }
        return vo;
    }

    public Long getId() { return id; }
    public Long getMerchantId() { return merchantId; }
    public String getContributionType() { return contributionType; }
    public Map<String, String> getFieldChanges() { return fieldChanges; }
    public String getIssueType() { return issueType; }
    public String getDescription() { return description; }
    public List<Long> getEvidenceAssetIds() { return evidenceAssetIds; }
    public String getReviewStatus() { return reviewStatus; }
    public String getReviewReason() { return reviewReason; }
    public Boolean getCanSupplement() { return canSupplement; }
    public Date getReviewedAt() { return reviewedAt; }
    public Date getMergedAt() { return mergedAt; }
    public Integer getVersion() { return version; }
    public Date getCreateTime() { return createTime; }
    public String getRewardStatus() { return rewardStatus; }
    public Integer getRewardPoints() { return rewardPoints; }
    public String getRewardLedgerNo() { return rewardLedgerNo; }
    public Date getRewardCreditedAt() { return rewardCreditedAt; }
}
