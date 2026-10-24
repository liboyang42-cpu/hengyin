package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.NpcAsrIntent;
import org.apache.ibatis.annotations.Param;

/** Permanent identity; there is deliberately no delete, reclaim or reset-to-IN_FLIGHT operation. */
public interface NpcAsrIntentMapper {
    java.util.List<NpcAsrIntent> selectQuotaRefundPending(@Param("limit") int limit);
    NpcAsrIntent selectQuotaRefundProof(@Param("userId") Long userId,@Param("requestId") String requestId);
    int completeQuotaRefund(@Param("userId") Long userId,@Param("requestId") String requestId,
                            @Param("reservation") String reservation);
    int finishNotSent(NpcAsrIntent update);
    NpcAsrIntent selectForUpdate(@Param("userId") Long userId,@Param("requestId") String requestId);
    int insert(NpcAsrIntent intent);
    int finish(NpcAsrIntent intent);
    int beginHandoff(@Param("userId") Long userId,@Param("requestId") String requestId);
    int claimTextAdmission(@Param("userId") Long userId,@Param("requestId") String requestId,@Param("sessionId") Long sessionId);
    int redactForUser(@Param("userId") Long userId);
    int redactExpiredForUser(@Param("userId") Long userId);
    int redactExpired(@Param("limit") int limit);
}
