package com.chengyinhub.business.service.impl;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.domain.NpcChatLog;
import com.chengyinhub.business.domain.vo.ai.NpcChatDataExportVO;
import com.chengyinhub.business.mapper.NpcChatLogMapper;

/**
 * NPC 聊天数据权益服务(《漫游AI方案》§7.4:用户复制/导出与主动删除)。
 *
 * <p>与 90 天留存自动清理({@link DataRetentionServiceImpl#cleanNpcChatLogs})相互独立:
 * 那边是平台按《隐私政策》到期批量清,这边是用户主动行使数据权利、立即硬删。
 *
 * <p>鉴权边界:两个方法的 userId 都必须来自登录态(BaseController.getAppUserId()),
 * 本服务与 mapper 均不提供任何按"客户端指定用户"操作的入口。
 *
 * @author chengyinhub
 */
@Service
public class NpcChatDataService
{
    private static final Logger log = LoggerFactory.getLogger(NpcChatDataService.class);

    /** 与 DataRetentionCleanupTask 同源的留存天数配置,导出时向用户披露保存策略。 */
    @Value("${chengyin.retention.npc-chat-days:90}")
    private int npcChatRetentionDays;

    @Autowired
    private NpcChatLogMapper npcChatLogMapper;

    @Autowired
    private NpcChatWriteService npcChatWriteService;

    /**
     * 导出当前用户在 npc_chat_log 里的全部记录(可读 JSON,按登录用户归属过滤)。
     */
    public NpcChatDataExportVO exportForUser(Long userId)
    {
        List<NpcChatLog> records = npcChatLogMapper.selectNpcChatLogsByUserId(userId);
        // §8.3:原文导出要留审计痕迹。app 侧最小实现 = 服务端日志记操作者与行数(不记原文)。
        log.info("[npc/chat-data] 用户 {} 导出聊天记录 {} 条", userId, records.size());
        NpcChatDataExportVO vo = new NpcChatDataExportVO();
        vo.setRetentionDays(npcChatRetentionDays);
        vo.setRetentionPolicy("聊天原文最长保留 " + npcChatRetentionDays
                + " 天,到期自动删除;你也可以在此随时主动删除全部记录,删除立即生效且不可恢复。");
        vo.setRecords(records);
        return vo;
    }

    /**
     * 用户主动删除自己的全部聊天记录(立即硬删,不做软删)。
     *
     * @return 实际删除行数
     */
    public int deleteForUser(Long userId)
    {
        int deleted = npcChatWriteService.deleteForUser(userId);
        log.info("[npc/chat-data] 用户 {} 主动删除聊天记录 {} 条", userId, deleted);
        return deleted;
    }
}
