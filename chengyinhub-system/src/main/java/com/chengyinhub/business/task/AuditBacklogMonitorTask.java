package com.chengyinhub.business.task;

import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.service.impl.AlarmService;

/**
 * UGC 审核积压 SLA 监控定时任务(合规 §3.4 先审后发,§HOLD 队列不能无人认领)。
 *
 * 在 RuoYi「定时任务管理」(sys_job)新增:invokeTarget = auditBacklogMonitorTask.run(),
 * cron 建议每 30 分钟。纯 DB 聚合计数 + 超阈值告警(复用 AlarmService → 钉钉/企微 webhook)。
 *
 * 判定:待审(status=0)提交超过 sla-hours 仍未处理 = 积压;deadline_at 已过 = 超期。
 * 积压超 backlog-threshold 或存在超期 → 告警。配置:
 *   chengyin.audit.sla-hours / chengyin.audit.backlog-threshold
 */
@Component("auditBacklogMonitorTask")
public class AuditBacklogMonitorTask {

    private static final Logger log = LoggerFactory.getLogger(AuditBacklogMonitorTask.class);

    private static final long HOUR_MILLIS = 60L * 60 * 1000;

    @Value("${chengyin.audit.sla-hours:24}")
    private int slaHours;

    @Value("${chengyin.audit.backlog-threshold:50}")
    private int backlogThreshold;

    @Autowired
    private AuditTaskMapper auditTaskMapper;

    @Autowired
    private AlarmService alarmService;

    public void run() {
        try {
            Date now = new Date();
            Date cutoff = new Date(now.getTime() - (long) slaHours * HOUR_MILLIS);

            int backlog = auditTaskMapper.countPendingBacklog(cutoff);
            int overdue = auditTaskMapper.countPendingOverdue(now);

            log.info("审核积压巡检:待审超{}h积压 {} 条(阈值{})、已超期 {} 条", slaHours, backlog, backlogThreshold, overdue);

            if (backlog > backlogThreshold || overdue > 0) {
                alarmService.alarm("UGC审核积压",
                        "待审超" + slaHours + "h积压 " + backlog + " 条(阈值" + backlogThreshold + ")、已超期 " + overdue
                                + " 条,请安排审核人及时处理 HOLD 队列。");
            }
        } catch (Exception e) {
            log.error("审核积压巡检异常", e);
        }
    }
}
