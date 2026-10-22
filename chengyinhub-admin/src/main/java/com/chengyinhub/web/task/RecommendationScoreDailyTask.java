package com.chengyinhub.web.task;

import com.chengyinhub.business.service.IRecommendationScoreDailyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/** O-14 每日推荐打分(sys_job 默认暂停)。按 Asia/Shanghai 当天重算；同日重跑幂等，中断后重跑即可续上。 */
@Component("recommendationScoreDailyTask")
public class RecommendationScoreDailyTask {
    private static final Logger log = LoggerFactory.getLogger(RecommendationScoreDailyTask.class);

    private final IRecommendationScoreDailyService recommendationScoreDailyService;
    private final Clock clock;

    @Autowired
    public RecommendationScoreDailyTask(IRecommendationScoreDailyService recommendationScoreDailyService) {
        this(recommendationScoreDailyService, Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    RecommendationScoreDailyTask(IRecommendationScoreDailyService recommendationScoreDailyService, Clock clock) {
        this.recommendationScoreDailyService = recommendationScoreDailyService;
        this.clock = clock;
    }

    public void run() {
        LocalDate statDate = LocalDate.now(clock);
        Map<String, Integer> result = recommendationScoreDailyService.computeDaily(statDate);
        Integer failures = result.get("failures");
        if (failures != null && failures > 0) {
            throw new IllegalStateException("每日推荐打分存在失败会员，可同日重跑 statDate=" + statDate + " failures=" + failures);
        }
        log.info("每日推荐打分完成 statDate={} members={} rows={} purged={}",
                statDate, result.get("members"), result.get("rows"), result.get("purged"));
    }
}
