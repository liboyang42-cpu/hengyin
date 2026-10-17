package com.chengyinhub.business.domain.dto;
/** 后台原父单撤销；操作人只从登录态取，流水仅服务端查找。 */
public class ParentOrderRedemptionReverseRequest {
 private String parentOrderNo;
 private String reason;
 private Boolean clawbackRewards;
 public String getParentOrderNo() { return parentOrderNo; }
 public void setParentOrderNo(String value) { parentOrderNo=value; }
 public String getReason() { return reason; }
 public void setReason(String value) { reason=value; }
 public Boolean getClawbackRewards() { return clawbackRewards; }
 public void setClawbackRewards(Boolean value) { clawbackRewards=value; }
}
