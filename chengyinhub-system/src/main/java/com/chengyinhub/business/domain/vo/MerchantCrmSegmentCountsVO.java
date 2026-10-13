package com.chengyinhub.business.domain.vo;

public class MerchantCrmSegmentCountsVO {
    private Long all;
    private Long pending;
    private Long repeat;
    private Long newCount;
    private Long noted;
    private Long monthlyNew;
    private Long dormant;
    private Long abnormal;

    public Long getAll() { return all; }
    public void setAll(Long value) { all = value; }
    public Long getPending() { return pending; }
    public void setPending(Long value) { pending = value; }
    public Long getRepeat() { return repeat; }
    public void setRepeat(Long value) { repeat = value; }
    public Long getNew() { return newCount; }
    public void setNew(Long value) { newCount = value; }
    public Long getNoted() { return noted; }
    public void setNoted(Long value) { noted = value; }
    public Long getMonthlyNew() { return monthlyNew; }
    public void setMonthlyNew(Long value) { monthlyNew = value; }
    public Long getDormant() { return dormant; }
    public void setDormant(Long value) { dormant = value; }
    public Long getAbnormal() { return abnormal; }
    public void setAbnormal(Long value) { abnormal = value; }
}
