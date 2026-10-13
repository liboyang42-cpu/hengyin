package com.chengyinhub.business.mapper;

import java.util.List;

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
}
