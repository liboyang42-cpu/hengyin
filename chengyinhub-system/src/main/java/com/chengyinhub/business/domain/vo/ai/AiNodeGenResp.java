package com.chengyinhub.business.domain.vo.ai;

/**
 * M2 节点助手响应。
 */
public class AiNodeGenResp
{
    private String traceId;
    private AiNodeContent node;

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public AiNodeContent getNode() { return node; }
    public void setNode(AiNodeContent node) { this.node = node; }
}
