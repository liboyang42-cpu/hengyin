package com.chengyinhub.business.domain.dto;

/** 章节人工批次容量；三个正整数必须一次性配置。 */
public class ChapterServiceCapacityDTO
{
    /** 每批人数。 */
    private Integer batchSize;

    /** 批次间隔（分钟）。 */
    private Integer batchIntervalMinutes;

    /** 最大同时接待人数。 */
    private Integer effortMaxConcurrent;

    public Integer getBatchSize()
    {
        return batchSize;
    }

    public void setBatchSize(Integer batchSize)
    {
        this.batchSize = batchSize;
    }

    public Integer getBatchIntervalMinutes()
    {
        return batchIntervalMinutes;
    }

    public void setBatchIntervalMinutes(Integer batchIntervalMinutes)
    {
        this.batchIntervalMinutes = batchIntervalMinutes;
    }

    public Integer getEffortMaxConcurrent()
    {
        return effortMaxConcurrent;
    }

    public void setEffortMaxConcurrent(Integer effortMaxConcurrent)
    {
        this.effortMaxConcurrent = effortMaxConcurrent;
    }
}
