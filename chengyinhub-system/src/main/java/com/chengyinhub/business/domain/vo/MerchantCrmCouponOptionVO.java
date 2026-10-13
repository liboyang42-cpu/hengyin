package com.chengyinhub.business.domain.vo;

import java.util.Date;

/** 当前商家自有、有效且仍可发放的券选项。 */
public class MerchantCrmCouponOptionVO {
    private Long id;
    private String name;
    private Date endTime;
    private Long remainingCount;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public Date getEndTime() { return endTime; }
    public void setEndTime(Date value) { endTime = value; }
    public Long getRemainingCount() { return remainingCount; }
    public void setRemainingCount(Long value) { remainingCount = value; }
}
