package com.chengyinhub.business.domain.dto;

import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;

/** 商家经营台账分页查询参数。 */
public class MerchantLedgerQuery {

    private Integer pageNum;
    private Integer pageSize;
    private String period;
    private String status;

    public Integer getPageNum() {
        return pageNum;
    }

    public void setPageNum(Integer pageNum) {
        this.pageNum = pageNum;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int safePageNum() {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    public int safePageSize() {
        if (pageSize == null || pageSize < 1) {
            return 20;
        }
        return Math.min(pageSize, 50);
    }

    public String safeStatus() {
        if ("pending".equals(status) || "completed".equals(status) || "aftersale".equals(status)) {
            return status;
        }
        return "all";
    }

    public static TimeRange resolveTimeRange(String period, LocalDateTime now) {
        if ("today".equals(period)) {
            LocalDateTime start = now.toLocalDate().atStartOfDay();
            return new TimeRange(start, start.plusDays(1));
        }
        if ("month".equals(period)) {
            LocalDateTime start = now.with(TemporalAdjusters.firstDayOfMonth()).toLocalDate().atStartOfDay();
            return new TimeRange(start, start.plusMonths(1));
        }
        return new TimeRange(null, null);
    }

    public static class TimeRange {
        private final LocalDateTime start;
        private final LocalDateTime end;

        public TimeRange(LocalDateTime start, LocalDateTime end) {
            this.start = start;
            this.end = end;
        }

        public LocalDateTime getStart() {
            return start;
        }

        public LocalDateTime getEnd() {
            return end;
        }
    }
}
