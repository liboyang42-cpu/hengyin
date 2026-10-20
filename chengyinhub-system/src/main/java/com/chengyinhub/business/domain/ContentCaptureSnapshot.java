package com.chengyinhub.business.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** Internal mapper row for an immutable, incomplete content snapshot. Not a release or API DTO. */
public class ContentCaptureSnapshot {
    private Long id;
    private Long topicId;
    private Long capturedByMemberId;
    private String requestId;
    private Long sourceConfigVersion;
    private String contentHash;
    private String canonicalJson;
    private String state;
    private String contractVersion;
    private String canonicalizationVersion;

    public Long getId() { return id; }
    public void setId(Long value) { id=value; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long value) { topicId=value; }
    public Long getCapturedByMemberId() { return capturedByMemberId; }
    public void setCapturedByMemberId(Long value) { capturedByMemberId=value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId=value; }
    public Long getSourceConfigVersion() { return sourceConfigVersion; }
    public void setSourceConfigVersion(Long value) { sourceConfigVersion=value; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String value) { contentHash=value; }
    @JsonIgnore
    @com.alibaba.fastjson2.annotation.JSONField(serialize=false)
    public String getCanonicalJson() { return canonicalJson; }
    public void setCanonicalJson(String value) { canonicalJson=value; }
    public String getState() { return state; }
    public void setState(String value) { state=value; }
    public String getContractVersion() { return contractVersion; }
    public void setContractVersion(String value) { contractVersion=value; }
    public String getCanonicalizationVersion() { return canonicalizationVersion; }
    public void setCanonicalizationVersion(String value) { canonicalizationVersion=value; }
}
