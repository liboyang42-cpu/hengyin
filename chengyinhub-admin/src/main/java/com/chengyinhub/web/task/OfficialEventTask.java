package com.chengyinhub.web.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.chengyinhub.business.service.IOfficialBroadcastService;
import com.chengyinhub.business.service.IOfficialEventService;

/**
 * 官方活动定时任务:①按时间推进活动 status(进结算态发奖)②发送到点的定时通知。
 *
 * 后台「定时任务」配置 invokeTarget 为 officialEventTask.run(),cron 建议 0 0/5 * * * ?(每5分钟)。
 *
 * @author cy
 * @date 2026-07-08
 */
@Component("officialEventTask")
public class OfficialEventTask {
    private static final Logger logger = LoggerFactory.getLogger(OfficialEventTask.class);

    @Autowired
    private IOfficialEventService officialEventService;
    @Autowired
    private IOfficialBroadcastService officialBroadcastService;

    public void run() {
        try {
            officialEventService.runStatusFlow();
        } catch (Exception e) {
            logger.error("官方活动状态流转执行失败", e);
        }
        try {
            officialBroadcastService.runDueScheduled();
        } catch (Exception e) {
            logger.error("官方活动定时通知发送失败", e);
        }
    }
}
