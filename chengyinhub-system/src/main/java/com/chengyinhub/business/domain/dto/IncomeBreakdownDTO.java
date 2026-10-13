package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;

/**
 * 收入来源分解 DTO
 * 对应 /api/user/income/breakdown 接口
 */
public class IncomeBreakdownDTO {

    /** 主题创作收益 (eventType=1) */
    private BigDecimal topicRevenue;

    /** 邀请商户收益 (eventType=2) */
    private BigDecimal inviteRevenue;

    /** 品牌参与收益 (eventType=5) */
    private BigDecimal brandRevenue;

    public IncomeBreakdownDTO() {
        this.topicRevenue = BigDecimal.ZERO;
        this.inviteRevenue = BigDecimal.ZERO;
        this.brandRevenue = BigDecimal.ZERO;
    }

    public BigDecimal getTopicRevenue() { return topicRevenue; }
    public void setTopicRevenue(BigDecimal topicRevenue) { this.topicRevenue = topicRevenue; }

    public BigDecimal getInviteRevenue() { return inviteRevenue; }
    public void setInviteRevenue(BigDecimal inviteRevenue) { this.inviteRevenue = inviteRevenue; }

    public BigDecimal getBrandRevenue() { return brandRevenue; }
    public void setBrandRevenue(BigDecimal brandRevenue) { this.brandRevenue = brandRevenue; }
}
