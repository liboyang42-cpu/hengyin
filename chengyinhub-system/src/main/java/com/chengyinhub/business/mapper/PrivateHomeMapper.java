package com.chengyinhub.business.mapper;

import com.chengyinhub.business.home.PrivateHomeContracts.*;
import org.apache.ibatis.annotations.Param;

/** Every read/update is owner-scoped. There are deliberately no public, list, nearby or export queries. */
public interface PrivateHomeMapper {
    int ensureOwner(@Param("owner") long owner);
    Home findHome(@Param("owner") long owner);
    Home lockHome(@Param("owner") long owner);
    int writeHome(Home home);
    Mutation findMutation(@Param("owner") long owner, @Param("requestId") String requestId);
    int insertMutation(Mutation mutation);
    int expireProofs(@Param("owner") long owner, @Param("now") long now);
    int revokeProofs(@Param("owner") long owner);
    Proof findProof(@Param("owner") long owner, @Param("proofId") String proofId);
    Proof findProofRequest(@Param("owner") long owner, @Param("requestId") String requestId);
    int insertProof(Proof proof);
    int consumeProof(@Param("owner") long owner, @Param("proofId") String proofId, @Param("now") long now);
    Receipt findReceipt(@Param("owner") long owner, @Param("requestId") String requestId);
    Long lastRestore(@Param("owner") long owner, @Param("scope") String scope);
    Long lastProofIssued(@Param("owner") long owner, @Param("scope") String scope);
    int insertReceipt(Receipt receipt);
    int insertLocationEvidence(@Param("owner") long owner, @Param("proofId") String proofId, @Param("expiresAt") long expiresAt);
    int countLocationEvidence(@Param("owner") long owner, @Param("proofId") String proofId);
    int clearLocationEvidence(@Param("owner") long owner);
    int expireLocationEvidence(@Param("owner") long owner, @Param("now") long now);
}
