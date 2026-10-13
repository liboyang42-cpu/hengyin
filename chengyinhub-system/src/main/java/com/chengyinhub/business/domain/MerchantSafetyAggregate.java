package com.chengyinhub.business.domain;

import java.util.Date;

public class MerchantSafetyAggregate {
    private Integer sampleCount;
    private Double averageScore;
    private Date latestAt;

    public Integer getSampleCount() { return sampleCount; }
    public void setSampleCount(Integer sampleCount) { this.sampleCount = sampleCount; }
    public Double getAverageScore() { return averageScore; }
    public void setAverageScore(Double averageScore) { this.averageScore = averageScore; }
    public Date getLatestAt() { return latestAt; }
    public void setLatestAt(Date latestAt) { this.latestAt = latestAt; }
}
