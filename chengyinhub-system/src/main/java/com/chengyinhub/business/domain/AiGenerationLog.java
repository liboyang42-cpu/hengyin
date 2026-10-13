package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * AI 生成日志 ai_generation_log
 *
 * 所有 /api/ai/* 调用都落一条,用于审计 / 成本复盘 / 申诉追溯。
 *
 * @author chengyinhub
 */
public class AiGenerationLog extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    public static final int SAFETY_OK = 0;
    public static final int SAFETY_INPUT_BLOCKED = 1;
    public static final int SAFETY_OUTPUT_BLOCKED = 2;

    private Long id;
    private Long memberId;
    /** 当前角色 player/club/merchant */
    private String role;
    /** 场景 merchant_plan/theme_draft/node_generate/safety_precheck */
    private String bizType;
    /** 命中模型 mock/qwen/deepseek */
    private String provider;
    private String inputBrief;
    private String outputBrief;
    /** 0正常 1输入被拦 2输出被拦 */
    private Integer safetyFlag;
    private Integer latencyMs;
    private Integer tokenEst;
    private Integer tokenIn;
    private Integer tokenOut;
    private String traceId;
    private Integer delFlag;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getInputBrief() { return inputBrief; }
    public void setInputBrief(String inputBrief) { this.inputBrief = inputBrief; }
    public String getOutputBrief() { return outputBrief; }
    public void setOutputBrief(String outputBrief) { this.outputBrief = outputBrief; }
    public Integer getSafetyFlag() { return safetyFlag; }
    public void setSafetyFlag(Integer safetyFlag) { this.safetyFlag = safetyFlag; }
    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }
    public Integer getTokenEst() { return tokenEst; }
    public void setTokenEst(Integer tokenEst) { this.tokenEst = tokenEst; }
    public Integer getTokenIn() { return tokenIn; }
    public void setTokenIn(Integer tokenIn) { this.tokenIn = tokenIn; }
    public Integer getTokenOut() { return tokenOut; }
    public void setTokenOut(Integer tokenOut) { this.tokenOut = tokenOut; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
}
