package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;

/**
 * 收入统计 DTO（主题/邀请收益共用）
 */
public class IncomeStatsDTO {

    /** 累计收益 */
    private BigDecimal totalRevenue;

    /** 统计数量（主题数 或 已邀商户数） */
    private Integer itemCount;

    public IncomeStatsDTO() {
        this.totalRevenue = BigDecimal.ZERO;
        this.itemCount = 0;
    }

    public BigDecimal getTotalRevenue() { return totalRevenue; }
    public void setTotalRevenue(BigDecimal totalRevenue) { this.totalRevenue = totalRevenue; }

    public Integer getItemCount() { return itemCount; }
    public void setItemCount(Integer itemCount) { this.itemCount = itemCount; }
}
