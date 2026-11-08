package com.chengyinhub.business.domain.dto;

/**
 * 玩家本人作为推荐人的收入分录按 (分配状态, 币种, 分录方向) 聚合的只读行。
 *
 * <p>金额以整数分为真源：{@code entryType=1} 正向应计，{@code entryType=2} 退款/chargeback 反向。
 * 冲销分录金额本身为负，展示时由服务层取绝对值单列，不做二次定价。
 */
public class PlayerAffiliateBucketRow {

    /** 1=ACCRUED 2=SETTLEABLE 3=PAID 4=PARTIALLY_REVERSED 5=REVERSED */
    private Integer allocationStatus;
    private String currency;
    /** 1=正向应计 2=反向冲销 */
    private Integer entryType;
    private long allocationCount;
    private long amountFen;

    public Integer getAllocationStatus() { return allocationStatus; }
    public void setAllocationStatus(Integer allocationStatus) { this.allocationStatus = allocationStatus; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Integer getEntryType() { return entryType; }
    public void setEntryType(Integer entryType) { this.entryType = entryType; }

    public long getAllocationCount() { return allocationCount; }
    public void setAllocationCount(long allocationCount) { this.allocationCount = allocationCount; }

    public long getAmountFen() { return amountFen; }
    public void setAmountFen(long amountFen) { this.amountFen = amountFen; }
}
