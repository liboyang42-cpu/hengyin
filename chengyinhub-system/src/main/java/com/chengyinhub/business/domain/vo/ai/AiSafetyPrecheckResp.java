package com.chengyinhub.business.domain.vo.ai;

import java.util.List;

/**
 * M2 安全预检响应。
 */
public class AiSafetyPrecheckResp
{
    private String traceId;
    private boolean pass;
    private List<AiSafetyIssue> issues;

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public boolean isPass() { return pass; }
    public void setPass(boolean pass) { this.pass = pass; }
    public List<AiSafetyIssue> getIssues() { return issues; }
    public void setIssues(List<AiSafetyIssue> issues) { this.issues = issues; }
}
