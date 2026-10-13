package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantClaim;
import com.chengyinhub.business.domain.MerchantClaimBatchCommand;
import com.chengyinhub.business.domain.MerchantClaimBatchResult;
import com.chengyinhub.business.domain.MerchantClaimProof;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface MerchantClaimMapper {
    MerchantClaim selectByClaimantRequestForUpdate(@Param("claimantMemberId") Long claimantMemberId,
                                                   @Param("requestId") String requestId);
    MerchantClaim selectActiveByMerchantForUpdate(@Param("merchantId") Long merchantId);
    MerchantClaim selectById(@Param("id") Long id);
    MerchantClaim selectByIdForUpdate(@Param("id") Long id);
    List<MerchantClaim> selectByClaimant(@Param("claimantMemberId") Long claimantMemberId);
    List<MerchantClaim> selectPending(@Param("limit") int limit);
    List<MerchantClaimProof> selectProofsByClaimId(@Param("claimId") Long claimId);
    int insertClaim(MerchantClaim claim);
    int insertProof(MerchantClaimProof proof);
    int auditCas(@Param("id") Long id, @Param("expectedStatus") String expectedStatus,
                 @Param("expectedVersion") Integer expectedVersion, @Param("nextStatus") String nextStatus,
                 @Param("actorId") Long actorId, @Param("reason") String reason,
                 @Param("requestId") String requestId, @Param("fingerprint") String fingerprint);

    MerchantClaimBatchCommand selectBatchCommandForUpdate(@Param("actorId") Long actorId,
                                                           @Param("requestId") String requestId);
    int insertBatchCommand(MerchantClaimBatchCommand command);
    MerchantClaimBatchResult selectBatchResult(@Param("batchCommandId") Long batchCommandId,
                                                @Param("claimId") Long claimId);
    List<MerchantClaimBatchResult> selectBatchResults(@Param("batchCommandId") Long batchCommandId);
    int insertBatchResult(MerchantClaimBatchResult result);
}
