package com.chengyinhub.business.domain.dto.ai;

import java.util.List;

/**
 * M2 主题草稿请求。
 */
public class AiThemeDraftReq
{
    private String idea;
    /** 主题产品模式:1=经典定向,2=自由探索。 */
    private Integer productType;
    /** 可选:已有节点名,AI 按此续写 */
    private List<String> existingNodeNames;
    /** 可选:客户端幂等键(UUID)。带上后同一 requestId 只调一次模型,结果可按 requestId 回读。 */
    private String requestId;

    public String getIdea() { return idea; }
    public void setIdea(String idea) { this.idea = idea; }
    public Integer getProductType() { return productType; }
    public void setProductType(Integer productType) { this.productType = productType; }
    public List<String> getExistingNodeNames() { return existingNodeNames; }
    public void setExistingNodeNames(List<String> existingNodeNames) { this.existingNodeNames = existingNodeNames; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
