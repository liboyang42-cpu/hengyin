package com.chengyinhub.business.service;

import java.util.Date;

/**
 * 数据留存清理服务:按《隐私政策》承诺的保留期,定期删除过期的浏览记录、行为事件日志与 NPC 追问原文。
 * 分批删除(单批 LIMIT + 逐批提交),避免长事务/锁表。
 */
public interface IDataRetentionService {
    int cleanRoamLocations(Date cutoff, int batchSize, int maxBatches);

    /**
     * 清理 tms_browse_record(含 IP/UA)中早于 cutoff 的记录。
     *
     * @param cutoff     保留期截止时间(早于此的删)
     * @param batchSize  单批删除上限
     * @param maxBatches 单次运行最多删多少批(总量护栏,防跑飞)
     * @return 实际删除的总行数
     */
    int cleanBrowseRecords(Date cutoff, int batchSize, int maxBatches);

    /**
     * 清理 biz_event_log 中早于 cutoff 的行为事件日志。
     *
     * @param cutoff     保留期截止时间(早于此的删)
     * @param batchSize  单批删除上限
     * @param maxBatches 单次运行最多删多少批(总量护栏,防跑飞)
     * @return 实际删除的总行数
     */
    int cleanBizEventLogs(Date cutoff, int batchSize, int maxBatches);

    /**
     * 清理 npc_chat_log 中早于 cutoff 的 NPC 追问日志(含聊天原文 user_msg/npc_reply)。
     *
     * @param cutoff     保留期截止时间(早于此的删)
     * @param batchSize  单批删除上限
     * @param maxBatches 单次运行最多删多少批(总量护栏,防跑飞)
     * @return 实际删除的总行数
     */
    int cleanNpcChatLogs(Date cutoff, int batchSize, int maxBatches);

    /**
     * 清理 npc_chat_request 中超过 48 小时的幂等终态。
     * 该表只承载短期重放，不替代 npc_chat_log 的合规留存策略。
     */
    int cleanNpcChatRequests(Date cutoff, int batchSize, int maxBatches);
}
