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

    public void run() {
        long now = System.currentTimeMillis();
        try {
            Date browseCutoff = new Date(now - (long) browseDays * DAY_MILLIS);
            int browse = dataRetentionService.cleanBrowseRecords(browseCutoff, batchSize, maxBatches);

            Date eventCutoff = new Date(now - (long) eventDays * DAY_MILLIS);
            int event = dataRetentionService.cleanBizEventLogs(eventCutoff, batchSize, maxBatches);

            Date npcChatCutoff = new Date(now - (long) npcChatDays * DAY_MILLIS);
            int npcChat = dataRetentionService.cleanNpcChatLogs(npcChatCutoff, batchSize, maxBatches);

            Date npcChatRequestCutoff = new Date(now - (long) npcChatRequestHours * HOUR_MILLIS);
            int npcChatRequest = dataRetentionService.cleanNpcChatRequests(npcChatRequestCutoff, batchSize, maxBatches);

            int roam = dataRetentionService.cleanRoamLocations(
                    new Date(now - com.chengyinhub.business.support.RoamLocationRetention.THIRTY_DAYS_MILLIS), batchSize, maxBatches);
            log.info("漫游详细位置清理完成 sessions/shares={} retentionDays=30", roam);
            log.info("留存清理任务完成:浏览记录删 {} 行(保留{}天)、行为事件删 {} 行(保留{}天)、NPC追问删 {} 行(保留{}天)、Chat幂等终态删 {} 行(保留{}小时)",
                    browse, browseDays, event, eventDays, npcChat, npcChatDays,
                    npcChatRequest, npcChatRequestHours);
        } catch (Exception e) {
            log.error("留存清理任务异常", e);
            throw new IllegalStateException("Data retention cleanup failed", e);
        }
    }
}
