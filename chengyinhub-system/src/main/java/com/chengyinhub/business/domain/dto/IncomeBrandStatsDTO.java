package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;

/**
 * 品牌参与收益统计 DTO
 */
public class IncomeBrandStatsDTO {

    /** 累计曝光 */
    private Long totalExposure;

    /** 累计收益 */
    private BigDecimal totalRevenue;

    /** 参与主题数 */
    private Integer topicCount;

    public IncomeBrandStatsDTO() {
        this.totalExposure = 0L;
        this.totalRevenue = BigDecimal.ZERO;
        this.topicCount = 0;
    }

    public Long getTotalExposure() { return totalExposure; }
    public void setTotalExposure(Long totalExposure) { this.totalExposure = totalExposure; }

    public BigDecimal getTotalRevenue() { return totalRevenue; }
    public void setTotalRevenue(BigDecimal totalRevenue) { this.totalRevenue = totalRevenue; }

    public Integer getTopicCount() { return topicCount; }
    public void setTopicCount(Integer topicCount) { this.topicCount = topicCount; }
}
