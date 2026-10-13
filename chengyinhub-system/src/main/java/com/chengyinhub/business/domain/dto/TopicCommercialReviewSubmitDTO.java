package com.chengyinhub.business.domain.dto;

/** 商业主题提审请求；立项审必须显式冻结拟走的 DRAFT 出口。 */
public class TopicCommercialReviewSubmitDTO {
    private String requestedTarget;

    public String getRequestedTarget() { return requestedTarget; }
    public void setRequestedTarget(String requestedTarget) { this.requestedTarget = requestedTarget; }
}
