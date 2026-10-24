package com.chengyinhub.business.domain.dto;

import java.util.List;

/** 客户端只提交意见与凭证；不接受 merchantId、退款审核态或打款态。 */
public class MerchantAftercareRespondRequest {
    private String decision;
    private String content;
    private List<String> evidenceKeys;
    private String evidenceUrl;
    private String requestId;

    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public List<String> getEvidenceKeys() { return evidenceKeys; }
    public void setEvidenceKeys(List<String> evidenceKeys) { this.evidenceKeys = evidenceKeys; }
    /** 已停用输入；保留字段仅用于对旧客户端明确返回 426（需更新客户端），不能重新落库长期 URL。 */
    public String getEvidenceUrl() { return evidenceUrl; }
    public void setEvidenceUrl(String evidenceUrl) { this.evidenceUrl = evidenceUrl; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
