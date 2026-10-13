package com.chengyinhub.business.domain.vo.ai;

/**
 * M2 主题草稿响应。
 */
public class AiThemeDraftResp
{
    private String requestId;
    private String outcomeStatus;
    private String errorCode;
    private String safeText;
    private String traceId;
    private AiThemePlan draft;
    /** 玩家简单创作成功后的今日剩余额度；俱乐部/商家为 null。 */
    private Integer remainingQuota;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getOutcomeStatus() { return outcomeStatus; }
    public void setOutcomeStatus(String outcomeStatus) { this.outcomeStatus = outcomeStatus; }
    public String getSafeText() { return safeText; }
    public void setSafeText(String safeText) { this.safeText = safeText; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public AiThemePlan getDraft() { return draft; }
    public void setDraft(AiThemePlan draft) { this.draft = draft; }
    public Integer getRemainingQuota() { return remainingQuota; }
    public void setRemainingQuota(Integer remainingQuota) { this.remainingQuota = remainingQuota; }
}
