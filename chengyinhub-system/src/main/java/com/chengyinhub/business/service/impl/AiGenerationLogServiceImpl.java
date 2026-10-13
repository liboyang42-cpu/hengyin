package com.chengyinhub.business.service.impl;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.domain.AiGenerationLog;
import com.chengyinhub.business.domain.AiMerchantMatchSnapshot;
import com.chengyinhub.business.mapper.AiGenerationLogMapper;
import com.chengyinhub.business.mapper.AiMerchantMatchSnapshotMapper;
import com.chengyinhub.business.service.IAiGenerationLogService;

/**
 * AI 生成日志 Service 业务层处理(只读)
 *
 * @author chengyinhub
 */
@Service
public class AiGenerationLogServiceImpl implements IAiGenerationLogService
{
    @Autowired
    private AiGenerationLogMapper aiGenerationLogMapper;

    @Autowired
    private AiMerchantMatchSnapshotMapper aiMerchantMatchSnapshotMapper;

    @Override
    public List<AiGenerationLog> selectAiGenerationLogList(AiGenerationLog aiGenerationLog)
    {
        return aiGenerationLogMapper.selectAiGenerationLogList(aiGenerationLog);
    }

    @Override
    public AiGenerationLog selectAiGenerationLogById(Long id)
    {
        return aiGenerationLogMapper.selectAiGenerationLogById(id);
    }

    @Override
    public AiMerchantMatchSnapshot selectSnapshotByTraceId(String traceId)
    {
        return aiMerchantMatchSnapshotMapper.selectSnapshotByTraceId(traceId);
    }
}
