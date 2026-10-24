package com.chengyinhub.business.domain.vo.ai;

/** Phase 3 NPC 自由追问终态 VO(普通 POST，一次性返回)。 */
public class NpcChatResp
{
    private String requestId;
    /** SUCCEEDED / REJECTED / FAILED / PROCESSING。 */
    private String outcomeStatus;
    /** PASS / BLOCKED / NOT_RUN。 */
    private String safetyDecision;
    private String errorCode;
    private String limitType;
    private Integer retryAfterSeconds;
    private boolean retryable;
    /** 已过安全边界的展示文本；客户端只可对它做本地逐字动画。 */
    private String safeText;
    /** 门店音色已就绪时的合成音频；缺席不影响文字回答。 */
    private String audioUrl;
    private boolean ai;
    /** Original ASR phase/reason only; neither field is a retry or provider-send authorization. */
    private String asrState;
    private String asrReason;
    /** Advisory: explicit POST may continue this saved transcript using the same requestId, never re-ASR. */
    private boolean asrResumable;
    /** User function quota only; PROCESSING is neither a completed debit nor proof of provider billing. */
    private String quotaStatus;

    public NpcChatResp() {}

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getOutcomeStatus() { return outcomeStatus; }
    public void setOutcomeStatus(String outcomeStatus) { this.outcomeStatus = outcomeStatus; }
    public String getSafetyDecision() { return safetyDecision; }
    public void setSafetyDecision(String safetyDecision) { this.safetyDecision = safetyDecision; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getLimitType() { return limitType; }
    public void setLimitType(String limitType) { this.limitType = limitType; }
    public Integer getRetryAfterSeconds() { return retryAfterSeconds; }
    public void setRetryAfterSeconds(Integer retryAfterSeconds) { this.retryAfterSeconds = retryAfterSeconds; }
    public boolean isRetryable() { return retryable; }
    public void setRetryable(boolean retryable) { this.retryable = retryable; }
    public String getSafeText() { return safeText; }
    public void setSafeText(String safeText) { this.safeText = safeText; }
    public String getAudioUrl() { return audioUrl; }
    public void setAudioUrl(String audioUrl) { this.audioUrl = audioUrl; }
    public String getAsrState() { return asrState; }
    public void setAsrState(String value) { this.asrState = value; }
    public String getAsrReason() { return asrReason; }
    public void setAsrReason(String value) { this.asrReason = value; }
    public boolean isAsrResumable() { return asrResumable; }
    public void setAsrResumable(boolean value) { this.asrResumable = value; }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public String getQuotaStatus() { return quotaStatus; }
    public void setQuotaStatus(String value) { this.quotaStatus=value; }
    public boolean isAi() { return ai; }
    public void setAi(boolean ai) { this.ai = ai; }
}
