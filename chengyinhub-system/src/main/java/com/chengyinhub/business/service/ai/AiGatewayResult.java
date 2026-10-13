package com.chengyinhub.business.service.ai;

/**
 * AI 网关调用结果。
 *
 * @author chengyinhub
 */
public class AiGatewayResult
{
    private String traceId;
    private String text;
    private String model;
    /** 0正常 1输入被拦 2输出被拦 */
    private int safetyFlag;

    public AiGatewayResult() {}

    public AiGatewayResult(String traceId, String text, String model, int safetyFlag)
    {
        this.traceId = traceId;
        this.text = text;
        this.model = model;
        this.safetyFlag = safetyFlag;
    }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getSafetyFlag() { return safetyFlag; }
    public void setSafetyFlag(int safetyFlag) { this.safetyFlag = safetyFlag; }

    public boolean isSafetyOk() { return safetyFlag == 0; }
}
