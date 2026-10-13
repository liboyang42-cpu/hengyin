package com.chengyinhub.web.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.impl.AlarmService;

/**
 * 结算定时任务
 *
 * 在后台「定时任务」中配置 invokeTarget 为 settlementTask.run() 即可自动执行活动与主题分润结算。
 */
@Component("settlementTask")
public class SettlementTask {
    private static final Logger logger = LoggerFactory.getLogger(SettlementTask.class);

    @Autowired
    private ICmsActivityService cmsActivityService;

    @Autowired
    private ICmsTopicService cmsTopicService;

    @Autowired
    private AlarmService alarmService;

    /**
     * 执行活动结算 + 主题结算
     */
    public void run() {
        // R9-46 §4:两段互不阻断都要跑完;任一段失败末端重抛,Quartz 才会记 FAIL(否则日志恒 SUCCESS)。
        RuntimeException failure = null;
        try {
            int activityCount = cmsActivityService.executeActivitySettlement();
            logger.info("定时任务-活动结算完成，共结算 {} 个活动", activityCount);
        } catch (Exception e) {
            failure = asRuntime(e);
            logger.error("定时任务-活动结算执行失败", e);
            try {
                alarmService.alarm("活动结算失败", "settlementTask.run活动结算执行异常: " + e.getMessage());
            } catch (Exception ignore) {
            }
        }

        try {
            int topicCount = cmsTopicService.executeTopicSettlement();
            logger.info("定时任务-主题结算完成，共结算 {} 个主题", topicCount);
        } catch (Exception e) {
            if (failure == null) failure = asRuntime(e); else failure.addSuppressed(e);
            logger.error("定时任务-主题结算执行失败", e);
            try {
                alarmService.alarm("主题结算失败", "settlementTask.run主题结算执行异常: " + e.getMessage());
            } catch (Exception ignore) {
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException asRuntime(Exception e) {
        return e instanceof RuntimeException ? (RuntimeException) e : new IllegalStateException(e.getMessage(), e);
    }
}
