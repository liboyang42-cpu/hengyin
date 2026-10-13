package com.chengyinhub.business.domain.vo;

public class TopicChapterWaitVO {
    private Long chapterId;
    private int population;
    private int sampleSize;
    private Double p90Minutes;
    private boolean passed;
    public Long getChapterId() { return chapterId; }
    public void setChapterId(Long chapterId) { this.chapterId = chapterId; }
    public int getPopulation() { return population; }
    public void setPopulation(int population) { this.population = population; }
    public int getSampleSize() { return sampleSize; }
    public void setSampleSize(int sampleSize) { this.sampleSize = sampleSize; }
    public Double getP90Minutes() { return p90Minutes; }
    public void setP90Minutes(Double p90Minutes) { this.p90Minutes = p90Minutes; }
    public boolean isPassed() { return passed; }
    public void setPassed(boolean passed) { this.passed = passed; }
}
