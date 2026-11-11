package com.chengyinhub.business.mapper;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.chengyinhub.business.domain.AiGenerationLog;

/**
 * AI 生成日志 ai_generation_log
 *
 * @author chengyinhub
 */
public interface AiGenerationLogMapper
{
    int insertAiGenerationLog(AiGenerationLog log);

    /**
     * 查询 AI 生成日志列表(后台审计用,只读)
     *
     * @param aiGenerationLog 查询条件(traceId 走 idx_ai_gen_trace 精确匹配)
     * @return 日志列表
     */
    List<AiGenerationLog> selectAiGenerationLogList(AiGenerationLog aiGenerationLog);

    /**
     * 查询 AI 生成日志详情
     *
     * @param id 主键
     * @return 日志
     */
    AiGenerationLog selectAiGenerationLogById(Long id);

    /**
     * 留存到期清理:删 create_time 早于 cutoff 的日志,每批 limit 行(dataRetentionCleanupTask 调用)。
     */
    int deleteAiGenerationLogsBefore(@Param("cutoff") Date cutoff, @Param("limit") int limit);
}
