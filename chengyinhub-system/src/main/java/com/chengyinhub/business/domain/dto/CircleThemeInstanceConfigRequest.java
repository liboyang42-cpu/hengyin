package com.chengyinhub.business.domain.dto;

import java.util.List;

/** 城市代理从圈层主题模板的 8 个供给角色候选中选择 3—4 个。 */
public class CircleThemeInstanceConfigRequest {
    private Long topicId;
    private List<String> candidateCodes;
    private String scope;

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public List<String> getCandidateCodes() { return candidateCodes; }
    public void setCandidateCodes(List<String> candidateCodes) { this.candidateCodes = candidateCodes; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
}
