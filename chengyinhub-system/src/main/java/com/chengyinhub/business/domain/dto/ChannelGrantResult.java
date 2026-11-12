package com.chengyinhub.business.domain.dto;

import java.util.Date;
import java.util.List;

/**
 * 权威 grant 端口的回执。{@code registrationIds} 必须来自既有
 * {@code cms_registration}，不是营销表自造订单。
 */
public class ChannelGrantResult {

    private Date validFrom;
    private Date validTo;
    private Boolean pendingActivation;
    private Long primaryRegistrationId;
    private String primaryRegistrationNo;
    private List<Long> registrationIds;
    private List<Long> entitlementIds;
    private String grantRef;
    private Date grantedAt;
    private Boolean replayed;
    private Boolean revocable;
    private String reasonCode;
    private String message;

    public Long getPrimaryRegistrationId() { return primaryRegistrationId; }
    public void setPrimaryRegistrationId(Long primaryRegistrationId) { this.primaryRegistrationId = primaryRegistrationId; }

    public String getPrimaryRegistrationNo() { return primaryRegistrationNo; }
    public void setPrimaryRegistrationNo(String primaryRegistrationNo) { this.primaryRegistrationNo = primaryRegistrationNo; }

    public List<Long> getRegistrationIds() { return registrationIds; }
    public void setRegistrationIds(List<Long> registrationIds) { this.registrationIds = registrationIds; }

    public List<Long> getEntitlementIds() { return entitlementIds; }
    public void setEntitlementIds(List<Long> entitlementIds) { this.entitlementIds = entitlementIds; }

    public String getGrantRef() { return grantRef; }
    public void setGrantRef(String grantRef) { this.grantRef = grantRef; }


    public Date getValidFrom() { return validFrom; }
    public void setValidFrom(Date validFrom) { this.validFrom = validFrom; }

    public Date getValidTo() { return validTo; }
    public void setValidTo(Date validTo) { this.validTo = validTo; }
    public Date getGrantedAt() { return grantedAt; }
    public void setGrantedAt(Date grantedAt) { this.grantedAt = grantedAt; }

    public Boolean getPendingActivation() { return pendingActivation; }
    public void setPendingActivation(Boolean pendingActivation) { this.pendingActivation = pendingActivation; }

    public Boolean getRevocable() { return revocable; }
    public void setRevocable(Boolean revocable) { this.revocable = revocable; }

    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Boolean getReplayed() { return replayed; }
    public void setReplayed(Boolean replayed) { this.replayed = replayed; }
}
