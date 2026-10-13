package com.chengyinhub.business.domain;

import java.util.Date;

/** 真实报名/核销/退款投影成的 CRM 时间线内部行。 */
public class MerchantCrmInteraction {
    private Long registrationId;
    private String type;
    private String projectTitle;
    private Date occurredAt;

    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long value) { registrationId = value; }
    public String getType() { return type; }
    public void setType(String value) { type = value; }
    public String getProjectTitle() { return projectTitle; }
    public void setProjectTitle(String value) { projectTitle = value; }
    public Date getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Date value) { occurredAt = value; }
}
