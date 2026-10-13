package com.chengyinhub.business.service;

import java.util.List;

import com.chengyinhub.business.domain.AiGenerationLog;
import com.chengyinhub.business.domain.AiMerchantMatchSnapshot;

/**
 * AI 生成日志 Service 接口(后台审计,只读)
 *
 * @author chengyinhub
 */
public interface IAiGenerationLogService
{
    /**
     * 查询 AI 生成日志列表
     */
    List<AiGenerationLog> selectAiGenerationLogList(AiGenerationLog aiGenerationLog);

    /**
     * 查询 AI 生成日志详情
     */
    AiGenerationLog selectAiGenerationLogById(Long id);

    /**
     * 按 traceId 取组题候选快照(详情下钻)
     */
    AiMerchantMatchSnapshot selectSnapshotByTraceId(String traceId);
}
