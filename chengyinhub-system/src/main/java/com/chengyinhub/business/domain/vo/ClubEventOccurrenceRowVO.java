package com.chengyinhub.business.domain.vo;

import java.util.Date;

/**
 * Figma E1「日期清单」一行。
 * ⚠️ signupCount 允许为 null:算不出来时不能显示成 0 —— 主理人会据此以为这场可以随便改。
 */
public class ClubEventOccurrenceRowVO {
    private Long occurrenceId;
    private Long activityId;
    private Date occurrenceAt;
    /** ACTIVE / CANCELLED */
    private String status;
    private Integer version;
    private Integer signupCount;
    private Integer refundedCount;

    public Long getOccurrenceId() { return occurrenceId; }
    public void setOccurrenceId(Long occurrenceId) { this.occurrenceId = occurrenceId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Date getOccurrenceAt() { return occurrenceAt; }
    public void setOccurrenceAt(Date occurrenceAt) { this.occurrenceAt = occurrenceAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Integer getSignupCount() { return signupCount; }
    public void setSignupCount(Integer signupCount) { this.signupCount = signupCount; }
    public Integer getRefundedCount() { return refundedCount; }
    public void setRefundedCount(Integer refundedCount) { this.refundedCount = refundedCount; }
}
