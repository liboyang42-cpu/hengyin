package com.chengyinhub.business.domain.vo.ai;

/**
 * 章节概述响应。
 */
public class AiChapterOverviewResp
{
    private String traceId;
    private String overview;

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getOverview() { return overview; }
    public void setOverview(String overview) { this.overview = overview; }
}
