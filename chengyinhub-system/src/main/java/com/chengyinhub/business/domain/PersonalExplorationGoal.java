package com.chengyinhub.business.domain;
/** Internal row. Dates are Asia/Shanghai calendar dates; never serialize owner/request metadata. */
public class PersonalExplorationGoal {
    private Long id;
    private Long memberId;
    private String requestId;
    private String metric;
    private Integer target;
    private String startDate;
    private String endDateExclusive;
    private Long createdAtMs;
    private Integer cancelled;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { memberId = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public String getMetric() { return metric; }
    public void setMetric(String value) { metric = value; }
    public Integer getTarget() { return target; }
    public void setTarget(Integer value) { target = value; }
    public String getStartDate() { return startDate; }
    public void setStartDate(String value) { startDate = value; }
    public String getEndDateExclusive() { return endDateExclusive; }
    public void setEndDateExclusive(String value) { endDateExclusive = value; }
    public Long getCreatedAtMs() { return createdAtMs; }
    public void setCreatedAtMs(Long value) { createdAtMs = value; }
    public Integer getCancelled() { return cancelled; }
    public void setCancelled(Integer value) { cancelled = value; }
}
