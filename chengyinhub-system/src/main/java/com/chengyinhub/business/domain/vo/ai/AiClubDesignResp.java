package com.chengyinhub.business.domain.vo.ai;

import java.util.List;

/**
 * 俱乐部活动策划响应。
 *
 * @author chengyinhub
 */
public class AiClubDesignResp
{
    private String traceId;
    private AiThemePlan plan;
    private List<String> merchantSuggestions;
    private String promoCopy;

    /**
     * 解析失败标记。null = 正常(plan 为空只代表 AI 没给方案);
     * 非 null = AI 响应解析失败的用户可读文案,plan/merchantSuggestions/promoCopy 均不可信。
     * 与 safety_precheck 的 parse_error issue 同一语义、同一文案。
     */
    private String parseError;

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getParseError() { return parseError; }
    public void setParseError(String parseError) { this.parseError = parseError; }
    public AiThemePlan getPlan() { return plan; }
    public void setPlan(AiThemePlan plan) { this.plan = plan; }
    public List<String> getMerchantSuggestions() { return merchantSuggestions; }
    public void setMerchantSuggestions(List<String> merchantSuggestions) { this.merchantSuggestions = merchantSuggestions; }
    public String getPromoCopy() { return promoCopy; }
    public void setPromoCopy(String promoCopy) { this.promoCopy = promoCopy; }
}
