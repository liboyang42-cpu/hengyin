package com.chengyinhub.web.task;

import com.chengyinhub.business.service.IAnalyticsEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** 每日重算最近十五个完整自然日，统一吸收允许时间窗内的迟到事件。 */
@Component("analyticsAggregationTask")
public class AnalyticsAggregationTask {
    private static final Logger log = LoggerFactory.getLogger(AnalyticsAggregationTask.class);

    private final IAnalyticsEventService analyticsEventService;
    private final Clock clock;

    @Autowired
    public AnalyticsAggregationTask(IAnalyticsEventService analyticsEventService) {
        this(analyticsEventService, Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    AnalyticsAggregationTask(IAnalyticsEventService analyticsEventService, Clock clock) {
        this.analyticsEventService = analyticsEventService;
        this.clock = clock;
    }

    public void aggregateRecentWindow() {
        LocalDate today = LocalDate.now(clock);
        int totalRows = 0;
        for (int daysAgo = 15; daysAgo >= 1; daysAgo--) {
            String statDate = today.minusDays(daysAgo).toString();
            totalRows += analyticsEventService.aggregateDaily(statDate);
        }
        log.info("每日行为漏斗窗口聚合完成 from={} to={} rows={}",
                today.minusDays(15), today.minusDays(1), totalRows);
    }
}
