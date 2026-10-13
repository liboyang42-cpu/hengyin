package com.chengyinhub.business.domain.vo.ai;

import java.util.List;

import com.chengyinhub.business.domain.NpcChatLog;

/**
 * NPC 聊天记录导出结果(《漫游AI方案》§7.4/§8.3:可读 JSON,同时向用户披露保存策略)。
 *
 * @author chengyinhub
 */
public class NpcChatDataExportVO
{
    /** 原文保留天数(与留存清理任务 DataRetentionCleanupTask 同一配置源) */
    private Integer retentionDays;

    /** 面向用户的保存策略说明 */
    private String retentionPolicy;

    /** 服务器当前仍保存的全部记录(仅当前登录用户归属) */
    private List<NpcChatLog> records;

    public Integer getRetentionDays()
    {
        return retentionDays;
    }

    public void setRetentionDays(Integer retentionDays)
    {
        this.retentionDays = retentionDays;
    }

    public String getRetentionPolicy()
    {
        return retentionPolicy;
    }

    public void setRetentionPolicy(String retentionPolicy)
    {
        this.retentionPolicy = retentionPolicy;
    }

    public List<NpcChatLog> getRecords()
    {
        return records;
    }

    public void setRecords(List<NpcChatLog> records)
    {
        this.records = records;
    }
}
