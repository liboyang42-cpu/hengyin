package com.chengyinhub.business.domain;

import java.util.Date;

/** finalizeForSale 与 SELLING CAS 同事务写入的不可变 canonical-15。 */
public class TopicSaleSnapshot {
    private Long id;
    private Long topicId;
    private String schemaVersion;
    private Long parameterVersion;
    private Long sourceReviewTaskId;
    private String canonicalJson;
    private Date frozenAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public Long getParameterVersion() { return parameterVersion; }
    public void setParameterVersion(Long parameterVersion) { this.parameterVersion = parameterVersion; }
    public Long getSourceReviewTaskId() { return sourceReviewTaskId; }
    public void setSourceReviewTaskId(Long sourceReviewTaskId) { this.sourceReviewTaskId = sourceReviewTaskId; }
    public String getCanonicalJson() { return canonicalJson; }
    public void setCanonicalJson(String canonicalJson) { this.canonicalJson = canonicalJson; }
    public Date getFrozenAt() { return frozenAt; }
    public void setFrozenAt(Date frozenAt) { this.frozenAt = frozenAt; }
}
