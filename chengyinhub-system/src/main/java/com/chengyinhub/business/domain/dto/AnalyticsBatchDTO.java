package com.chengyinhub.business.domain.dto;

import java.util.List;

/**
 * P3 行为事件批量上报请求。
 */
public class AnalyticsBatchDTO {
    private List<AnalyticsEventDTO> events;

    public List<AnalyticsEventDTO> getEvents() {
        return events;
    }

    public void setEvents(List<AnalyticsEventDTO> events) {
        this.events = events;
    }
}
