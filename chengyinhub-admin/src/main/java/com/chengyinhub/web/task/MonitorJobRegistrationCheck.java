package com.chengyinhub.web.task;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.quartz.domain.SysJob;
import com.chengyinhub.quartz.mapper.SysJobMapper;

/**
 * F-W3-04:RuoYi 只在启动时装载 sys_job,从仓库重建的环境里巡检任务曾整批缺失且零提示。
 * 启动后自检一次「必需巡检任务已登记」:缺失即告警(按固定事件限频);已登记但暂停只记 WARN,
 * 因为暂停可能是运维/资金 owner 的有意选择(例如结算金额巡检),不能自动启用或改计划。
 */
@Component
@ConditionalOnProperty(name = "chengyin.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class MonitorJobRegistrationCheck {
    private static final Logger log = LoggerFactory.getLogger(MonitorJobRegistrationCheck.class);

    /** 巡检/对账类任务:只告警或按微信终态回补,不主动出款。 */
    static final List<String> REQUIRED_MONITOR_TARGETS = Collections.unmodifiableList(Arrays.asList(
            "metricsProbeTask.run()",
            "auditBacklogMonitorTask.run()",
            "withdrawReconciliationTask.run()",
            "settlementReconciliationTask.run()",
            "refundReconcileTask.reconcile()",
            "clubSessionReconciliationTask.run()",
            "merchantCommerceReconcileTask.reconcile()"));

    private final SysJobMapper sysJobMapper;
    private final AlarmService alarmService;

    public MonitorJobRegistrationCheck(SysJobMapper sysJobMapper, AlarmService alarmService) {
        this.sysJobMapper = sysJobMapper;
        this.alarmService = alarmService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            check(sysJobMapper.selectJobAll());
        } catch (RuntimeException failure) {
            // 自检失败不能阻断启动,但也不能静默。
            log.error("[MONITOR-JOBS] 巡检任务登记自检失败 type={}", failure.getClass().getSimpleName());
        }
    }

    /** @return 缺失的 invoke_target(供测试断言) */
    List<String> check(List<SysJob> jobs) {
        Map<String, List<SysJob>> byTarget = new HashMap<>();
        for (SysJob job : jobs == null ? Collections.<SysJob>emptyList() : jobs) {
            if (job != null && job.getInvokeTarget() != null) {
                byTarget.computeIfAbsent(job.getInvokeTarget().trim(), key -> new ArrayList<>()).add(job);
            }
        }
        List<String> missing = new ArrayList<>();
        List<String> paused = new ArrayList<>();
        for (String target : REQUIRED_MONITOR_TARGETS) {
            List<SysJob> rows = byTarget.get(target);
            if (rows == null) {
                missing.add(target);
            } else if (rows.stream().noneMatch(job -> "0".equals(job.getStatus()))) {
                paused.add(target);
            }
        }
        if (!paused.isEmpty()) {
            log.warn("[MONITOR-JOBS] 巡检任务已登记但处于暂停,对应告警不会触发 paused={}", paused);
        }
        if (!missing.isEmpty()) {
            alarmService.alarmThrottled("monitor.jobs.missing", "巡检任务未登记",
                    "missing=" + String.join(",", missing) + " ;按 ops/monitor-registration-readback.md 补登记后重启");
        }
        return missing;
    }
}
