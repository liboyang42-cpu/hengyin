package com.chengyinhub.business.domain;

public class MerchantSafetyPolicy {
    private Integer minimumSampleCount;
    private Integer windowDays;
    private Double neutralMinimumScore;
    private Double safeMinimumScore;

    public Integer getMinimumSampleCount() { return minimumSampleCount; }
    public void setMinimumSampleCount(Integer minimumSampleCount) { this.minimumSampleCount = minimumSampleCount; }
    public Integer getWindowDays() { return windowDays; }
    public void setWindowDays(Integer windowDays) { this.windowDays = windowDays; }
    public Double getNeutralMinimumScore() { return neutralMinimumScore; }
    public void setNeutralMinimumScore(Double neutralMinimumScore) { this.neutralMinimumScore = neutralMinimumScore; }
    public Double getSafeMinimumScore() { return safeMinimumScore; }
    public void setSafeMinimumScore(Double safeMinimumScore) { this.safeMinimumScore = safeMinimumScore; }
}
