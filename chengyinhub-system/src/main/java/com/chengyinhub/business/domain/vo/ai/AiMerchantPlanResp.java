package com.chengyinhub.business.domain.vo.ai;

import java.util.List;

/**
 * M1 组题响应。
 */
public class AiMerchantPlanResp
{
    private String traceId;
    private List<AiThemePlan> plans;
    private Integer candidateCount;
    /** AI 标识标签,如"AI 辅助生成" */
    private String aiLabel;

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public List<AiThemePlan> getPlans() { return plans; }
    public void setPlans(List<AiThemePlan> plans) { this.plans = plans; }
    public Integer getCandidateCount() { return candidateCount; }
    public void setCandidateCount(Integer candidateCount) { this.candidateCount = candidateCount; }
    public String getAiLabel() { return aiLabel; }
    public void setAiLabel(String aiLabel) { this.aiLabel = aiLabel; }
}
