package com.chengyinhub.business.domain.dto;

/**
 * P3 成长中心排行榜查询请求。
 * metric: point(积分榜) / exp(成长榜);period: total(总榜) / week(周榜);limit: 取前 N 名。
 */
public class LeaderboardQueryDTO {
    private String metric;
    private String period;
    private Integer limit;

    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public Integer getLimit() { return limit; }
    public void setLimit(Integer limit) { this.limit = limit; }
}
