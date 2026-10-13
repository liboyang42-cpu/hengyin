package com.chengyinhub.business.domain.vo.ai;

/**
 * AI 自动填表响应。
 */
public class AiTemplateFillResp
{
    private String traceId;
    private AiTemplateContent template;

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public AiTemplateContent getTemplate() { return template; }
    public void setTemplate(AiTemplateContent template) { this.template = template; }
}
