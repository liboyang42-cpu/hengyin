package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.BizEventLog;

import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * P3 行为事件日志 Mapper。
 */
public interface BizEventLogMapper {
    int insertBizEventLog(BizEventLog bizEventLog);

    /** Unique identity read after INSERT IGNORE; current read also observes a competing commit. */
    BizEventLog selectIdempotentEventForUpdate(BizEventLog event);

    /** Existing successful registration fact; no financial data is returned from analytics. */
    BizEventLog selectRegistrationEventFact(@Param("memberId") Long memberId,
            @Param("registrationId") Long registrationId, @Param("eventName") String eventName);

    List<BizEventLog> selectBizEventLogList(BizEventLog bizEventLog);

    /**
     * 留存清理:删除 occurred_at/create_time 早于 cutoff 的行为事件日志,单批 LIMIT。
     * 合规《隐私政策》数据留存条款,由 dataRetentionCleanupTask 分批调用。
     *
     * @param cutoff 保留期截止时间(早于此的删)
     * @param limit  单批删除上限
     * @return 实际删除行数
     */
    int deleteBizEventLogsBefore(@Param("cutoff") Date cutoff, @Param("limit") int limit);
}
