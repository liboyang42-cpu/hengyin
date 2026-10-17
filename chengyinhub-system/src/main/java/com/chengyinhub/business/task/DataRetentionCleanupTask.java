package com.chengyinhub.business.task;

import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.chengyinhub.business.service.IDataRetentionService;

/**
 * 数据留存清理定时任务(合规《隐私政策》数据留存条款,§6.4)。
 *
 * 在 RuoYi「定时任务管理」(sys_job)新增一条:invokeTarget = dataRetentionCleanupTask.run(),
 * cron 建议每日凌晨低峰(如 0 30 3 * * ?)。分批删除、逐批提交,不锁表。
 *
 * ⚠️ 保留天数默认 browse=180 天(6 个月)、event=365 天(12 个月)、npcChat=90 天(3 个月,
 *    聊天原文含用户输入,取更短期),必须与线上《隐私政策》第四条实际承诺口径一致 —— 通过
 *    application.yml 覆盖:
 *      chengyin.retention.browse-days / event-days / npc-chat-days / npc-chat-request-hours /
 *      batch-size / max-batches
 */
@Component("dataRetentionCleanupTask")
public class DataRetentionCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionCleanupTask.class);

    private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;
    private static final long HOUR_MILLIS = 60L * 60 * 1000;

    @Value("${chengyin.retention.browse-days:180}")
    private int browseDays;

    @Value("${chengyin.retention.event-days:365}")
    private int eventDays;

    @Value("${chengyin.retention.npc-chat-days:90}")
    private int npcChatDays;

    /** requestId 重放窗口固定为 48 小时；不复用含原文的 npc_chat_log 留存期。 */
    @Value("${chengyin.retention.npc-chat-request-hours:48}")
    private int npcChatRequestHours;

    @Value("${chengyin.retention.batch-size:1000}")
    private int batchSize;

    @Value("${chengyin.retention.max-batches:500}")
    private int maxBatches;

    @Autowired
    private IDataRetentionService dataRetentionService;

    @Autowired
    private com.chengyinhub.business.service.impl.AlarmService alarmService;

    /**
     * F-L4-12：各留存步骤互相独立，一步失败不能让后面的合规删除整段跳过；全部跑完后告警并重抛，
     * sys_job_log 记失败而不是 SUCCESS。
     */
    public void run() {
        long now = System.currentTimeMillis();
        java.util.List<String> failedSteps = new java.util.ArrayList<>();
        RuntimeException firstFailure = null;

        int browse = -1, event = -1, npcChat = -1, npcChatRequest = -1, roam = -1;
        try {
            browse = dataRetentionService.cleanBrowseRecords(new Date(now - (long) browseDays * DAY_MILLIS), batchSize, maxBatches);
        } catch (RuntimeException e) { firstFailure = record(failedSteps, "browse", e, firstFailure); }
        try {
            event = dataRetentionService.cleanBizEventLogs(new Date(now - (long) eventDays * DAY_MILLIS), batchSize, maxBatches);
        } catch (RuntimeException e) { firstFailure = record(failedSteps, "event", e, firstFailure); }
        try {
            npcChat = dataRetentionService.cleanNpcChatLogs(new Date(now - (long) npcChatDays * DAY_MILLIS), batchSize, maxBatches);
        } catch (RuntimeException e) { firstFailure = record(failedSteps, "npc_chat", e, firstFailure); }
        try {
            npcChatRequest = dataRetentionService.cleanNpcChatRequests(
                    new Date(now - (long) npcChatRequestHours * HOUR_MILLIS), batchSize, maxBatches);
        } catch (RuntimeException e) { firstFailure = record(failedSteps, "npc_chat_request", e, firstFailure); }
        try {
            roam = dataRetentionService.cleanRoamLocations(
                    new Date(now - com.chengyinhub.business.support.RoamLocationRetention.THIRTY_DAYS_MILLIS), batchSize, maxBatches);
            log.info("漫游详细位置清理完成 sessions/shares={} retentionDays=30", roam);
        } catch (RuntimeException e) { firstFailure = record(failedSteps, "roam_location", e, firstFailure); }

        log.info("留存清理任务结束:浏览记录删 {} 行(保留{}天)、行为事件删 {} 行(保留{}天)、NPC追问删 {} 行(保留{}天)、Chat幂等终态删 {} 行(保留{}小时) 失败步骤={}",
                browse, browseDays, event, eventDays, npcChat, npcChatDays,
                npcChatRequest, npcChatRequestHours, failedSteps);
        if (firstFailure != null) {
            IllegalStateException failure = new IllegalStateException(
                    "Data retention cleanup failed steps=" + failedSteps, firstFailure);
            try {
                if (alarmService != null) {
                    alarmService.alarmThrottled("job.data_retention.failed", "合规留存清理失败",
                            "failedSteps=" + failedSteps + " type=" + firstFailure.getClass().getSimpleName());
                }
            } catch (RuntimeException alarmFailure) {
                failure.addSuppressed(alarmFailure);
            }
            throw failure;
        }
    }

    private RuntimeException record(java.util.List<String> failedSteps, String step, RuntimeException e,
                                    RuntimeException firstFailure) {
        log.error("留存清理步骤失败 step={}", step, e);
        failedSteps.add(step);
        if (firstFailure == null) return e;
        firstFailure.addSuppressed(e);
        return firstFailure;
    }
}
