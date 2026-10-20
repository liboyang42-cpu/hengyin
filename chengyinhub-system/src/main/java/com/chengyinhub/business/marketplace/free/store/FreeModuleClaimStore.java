package com.chengyinhub.business.marketplace.free.store;
import org.apache.ibatis.annotations.Param;
/** Unregistered store: same datasource/transaction as member and publication writers required. */
public interface FreeModuleClaimStore {
    Long findActiveMember(@Param("memberId") long memberId);
    int retirePublication(@Param("publicationId") String publicationId);
    int withdrawPublication(@Param("publicationId") String publicationId);
    int revokeOwnedClaim(@Param("buyer") long buyer, @Param("claimId") String claimId);
    Long lockActiveMember(@Param("memberId") long memberId);
    FreePublicationRow lockPublication(@Param("publicationId") String publicationId);
    FreeClaimRow findRequest(@Param("buyer") long buyer, @Param("requestKey") String requestKey);
    FreeClaimRow findOwned(@Param("buyer") long buyer, @Param("publicationId") String publicationId);
    int insertClaim(FreeClaimRow row);
    FreeClaimRow findClaim(@Param("buyer") long buyer, @Param("claimId") String claimId);
}
