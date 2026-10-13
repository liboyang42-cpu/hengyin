package com.chengyinhub.business.domain;

import java.util.Date;

/** 商家对退款售后单的不可变追加意见；不承载平台审核或资金状态。 */
public class MerchantAftercareResponse {
    public static final String DECISION_AGREE = "AGREE";
    public static final String DECISION_REJECT = "REJECT";
    public static final String DECISION_EVIDENCE = "EVIDENCE";

    private Long id;
    private Long refundApplicationId;
    private Long merchantId;
    private Long merchantOwnerMemberId;
    private Long actorMemberId;
    private String actorRoleCode;
    private String decision;
    private String content;
    private String evidenceUrl;
    private String requestId;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRefundApplicationId() { return refundApplicationId; }
    public void setRefundApplicationId(Long refundApplicationId) { this.refundApplicationId = refundApplicationId; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public Long getMerchantOwnerMemberId() { return merchantOwnerMemberId; }
    public void setMerchantOwnerMemberId(Long merchantOwnerMemberId) { this.merchantOwnerMemberId = merchantOwnerMemberId; }
    public Long getActorMemberId() { return actorMemberId; }
    public void setActorMemberId(Long actorMemberId) { this.actorMemberId = actorMemberId; }
    public String getActorRoleCode() { return actorRoleCode; }
    public void setActorRoleCode(String actorRoleCode) { this.actorRoleCode = actorRoleCode; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getEvidenceUrl() { return evidenceUrl; }
    public void setEvidenceUrl(String evidenceUrl) { this.evidenceUrl = evidenceUrl; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
