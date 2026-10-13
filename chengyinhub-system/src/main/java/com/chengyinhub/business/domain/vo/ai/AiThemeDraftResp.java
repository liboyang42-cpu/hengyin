package com.chengyinhub.business.domain.vo.ai;

/**
 * M2 主题草稿响应。
 */
public class AiThemeDraftResp
{
    private String traceId;
    private AiThemePlan draft;
    /** 玩家简单创作成功后的今日剩余额度；俱乐部/商家为 null。 */
    private Integer remainingQuota;

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public AiThemePlan getDraft() { return draft; }
    public void setDraft(AiThemePlan draft) { this.draft = draft; }
    public Integer getRemainingQuota() { return remainingQuota; }
    public void setRemainingQuota(Integer remainingQuota) { this.remainingQuota = remainingQuota; }
}
