package com.chengyinhub.business.domain;

import java.util.Date;

/** 客户标签追加事件；当前状态由同一 merchant row/customer/tag 的最新事件决定。 */
public class MerchantCrmTagAssignment {
    public static final String ASSIGN = "ASSIGN";
    public static final String REMOVE = "REMOVE";

    private Long id;
    private Long merchantId;
    private Long customerMemberId;
    private Long tagId;
    private String action;
    private Long actorMemberId;
    private String requestId;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long value) { merchantId = value; }
    public Long getCustomerMemberId() { return customerMemberId; }
    public void setCustomerMemberId(Long value) { customerMemberId = value; }
    public Long getTagId() { return tagId; }
    public void setTagId(Long value) { tagId = value; }
    public String getAction() { return action; }
    public void setAction(String value) { action = value; }
    public Long getActorMemberId() { return actorMemberId; }
    public void setActorMemberId(Long value) { actorMemberId = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date value) { createTime = value; }
}
