package com.chengyinhub.web.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.impl.AlarmService;

/**
 * B4 · 商家结算 T+7 打款定时任务(报告§5.5)。
 *
 * 在后台「定时任务」中配置 invokeTarget 为 coopSettlementPayoutTask.run(),cron 建议 0 0 3 * * ?(每天凌晨3点),
 * 扫 coop_settlement 里到期(payable_time≤now)且待打款(status=0)的结算单 → 打款进商家余额 → 置已打款。
 */
@Component("coopSettlementPayoutTask")
public class CoopSettlementPayoutTask {
    private static final Logger logger = LoggerFactory.getLogger(CoopSettlementPayoutTask.class);

    @Autowired
    private ICmsTopicService cmsTopicService;

    @Autowired
    private AlarmService alarmService;

    public void run() {
        try {
            int paid = cmsTopicService.payoutDueCoopSettlements();
            logger.info("商家结算 T+7 打款完成,共 {} 笔", paid);
        } catch (Exception e) {
            logger.error("商家结算 T+7 打款执行失败", e);
            try {
                alarmService.alarm("商家结算T+7打款失败", "coopSettlementPayoutTask执行异常: " + e.getMessage());
            } catch (Exception ignore) {
            }
            // 1-04:告警之后必须再抛,否则 Quartz 记 SUCCESS,打款失败对 sys_job_log 不可见
            throw e instanceof RuntimeException ? (RuntimeException) e : new IllegalStateException(e);
        }
    }
}
