package com.chengyinhub.business.domain;

import java.util.Date;

/** PRICING 阶段 canonical-15 人工录入真源；每次保存单调递增 parameterVersion。 */
public class TopicSaleCanonicalDraft {
    private Long topicId;
    private String schemaVersion;
    private Long parameterVersion;
    private String canonicalJson;
    private Long updatedBy;
    private Date updateTime;
    private com.chengyinhub.business.domain.vo.TopicSaleReviewProgress saleReviewProgress;

    public com.chengyinhub.business.domain.vo.TopicSaleReviewProgress getSaleReviewProgress() { return saleReviewProgress; }
    public void setSaleReviewProgress(com.chengyinhub.business.domain.vo.TopicSaleReviewProgress value) { this.saleReviewProgress = value; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public Long getParameterVersion() { return parameterVersion; }
    public void setParameterVersion(Long parameterVersion) { this.parameterVersion = parameterVersion; }
    public String getCanonicalJson() { return canonicalJson; }
    public void setCanonicalJson(String canonicalJson) { this.canonicalJson = canonicalJson; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
