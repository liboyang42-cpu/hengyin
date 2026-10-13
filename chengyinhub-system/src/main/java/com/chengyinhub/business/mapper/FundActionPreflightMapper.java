package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.FundActionPreflight;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

public interface FundActionPreflightMapper {
    int insert(FundActionPreflight row);
    FundActionPreflight selectById(@Param("id") Long id);
    FundActionPreflight selectByIntent(@Param("memberId") Long memberId,
                                       @Param("actionType") String actionType,
                                       @Param("requestId") String requestId);
    int countConsumedByCounterparty(@Param("memberId") Long memberId,
                                    @Param("actionType") String actionType,
                                    @Param("counterpartyKeyHash") String counterpartyKeyHash);
    int rearmExpired(@Param("id") Long id, @Param("memberId") Long memberId,
                     @Param("expectedState") Integer expectedState,
                     @Param("challengeNonce") String challengeNonce,
                     @Param("challengeTokenHash") String challengeTokenHash,
                     @Param("expiresAt") Date expiresAt,
                     @Param("now") Date now);
    int confirmIfPending(@Param("id") Long id, @Param("memberId") Long memberId,
                         @Param("actionType") String actionType, @Param("requestId") String requestId,
                         @Param("requestFingerprint") String requestFingerprint,
                         @Param("challengeTokenHash") String challengeTokenHash, @Param("now") Date now);
    int rejectIfPending(@Param("id") Long id, @Param("memberId") Long memberId,
                        @Param("challengeTokenHash") String challengeTokenHash, @Param("now") Date now);
    int consumeIfConfirmed(@Param("id") Long id, @Param("memberId") Long memberId,
                           @Param("actionType") String actionType, @Param("requestId") String requestId,
                           @Param("requestFingerprint") String requestFingerprint,
                           @Param("now") Date now);
}
