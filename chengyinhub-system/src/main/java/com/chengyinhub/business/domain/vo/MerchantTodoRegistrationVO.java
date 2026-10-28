package com.chengyinhub.business.domain.vo;

import java.util.Date;

/** 商家待确认扫码所需的报名归属窄投影，不返回报名个人信息。 */
public class MerchantTodoRegistrationVO {
    private Long id;
    private Long ownerType;
    private Long ownerId;
    private Date endDate;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOwnerType() { return ownerType; }
    public void setOwnerType(Long ownerType) { this.ownerType = ownerType; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public Date getEndDate() { return endDate; }
    public void setEndDate(Date endDate) { this.endDate = endDate; }
}
