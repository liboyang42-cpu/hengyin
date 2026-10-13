package com.chengyinhub.business.domain.vo;

/** 结算首屏的五个互不偷算口径，金额均为 CNY 两位字符串。 */
public class MerchantSettlementOverview {
    private String personalArrivedThisMonthGross;
    private String personalExecutedAdjustmentsThisMonth;
    private String personalArrivedThisMonthNet;
    private String publicPayablePending;
    private String adjustmentPending;
    private Integer adjustmentPendingCount;

    public String getPersonalArrivedThisMonthGross() { return personalArrivedThisMonthGross; }
    public void setPersonalArrivedThisMonthGross(String value) { this.personalArrivedThisMonthGross = value; }
    public String getPersonalExecutedAdjustmentsThisMonth() { return personalExecutedAdjustmentsThisMonth; }
    public void setPersonalExecutedAdjustmentsThisMonth(String value) { this.personalExecutedAdjustmentsThisMonth = value; }
    public String getPersonalArrivedThisMonthNet() { return personalArrivedThisMonthNet; }
    public void setPersonalArrivedThisMonthNet(String value) { this.personalArrivedThisMonthNet = value; }
    public String getPublicPayablePending() { return publicPayablePending; }
    public void setPublicPayablePending(String value) { this.publicPayablePending = value; }
    public String getAdjustmentPending() { return adjustmentPending; }
    public void setAdjustmentPending(String value) { this.adjustmentPending = value; }
    public Integer getAdjustmentPendingCount() { return adjustmentPendingCount; }
    public void setAdjustmentPendingCount(Integer value) { this.adjustmentPendingCount = value; }
}
