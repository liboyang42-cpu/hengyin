package com.chengyinhub.web.task;

import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.chengyinhub.business.growthloops.WinbackService;

/** R11 growth-loops 召回（GRO-09）。sys_job：growthWinbackTask.run()，每小时。模式 OFF 时安全空跑。 */
@Component("growthWinbackTask")
public class GrowthWinbackTask
{
    private static final Logger log = LoggerFactory.getLogger(GrowthWinbackTask.class);

    @Autowired
    private WinbackService winbackService;

    public void run()
    {
        WinbackService.Summary s = winbackService.runDue(new Date());
        log.info("event=WINBACK_RUN mode={} resolved={} evaluated={} sent={} shadow={} skipped={} note={}",
                s.mode(), s.resolved(), s.evaluated(), s.sent(), s.shadow(), s.skipped(), s.note());
    }
}
