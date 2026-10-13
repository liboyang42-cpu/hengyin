package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.AiMerchantMatchSnapshot;

/**
 * @author chengyinhub
 */
public interface AiMerchantMatchSnapshotMapper
{
    int insertAiMerchantMatchSnapshot(AiMerchantMatchSnapshot snapshot);

    /**
     * 按 traceId 取组题候选快照(后台日志详情下钻用)。
     * traceId 与 ai_generation_log.trace_id 同源,才 join 得起来。
     *
     * @param traceId 链路 id
     * @return 快照,没有则 null
     */
    AiMerchantMatchSnapshot selectSnapshotByTraceId(String traceId);
}
