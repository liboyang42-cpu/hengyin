package com.chengyinhub.business.domain.dto;

import com.fasterxml.jackson.databind.JsonNode;

/** 作者申诉命令；申诉人只取登录态，不信任请求体。evidence 为可选 JSON 对象/数组(如图片地址列表)。 */
public class AuditAppealDTO {
    private String bizType;
    private Long bizId;
    private String reason;
    private JsonNode evidence;

    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public Long getBizId() { return bizId; }
    public void setBizId(Long bizId) { this.bizId = bizId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public JsonNode getEvidence() { return evidence; }
    public void setEvidence(JsonNode evidence) { this.evidence = evidence; }
}
