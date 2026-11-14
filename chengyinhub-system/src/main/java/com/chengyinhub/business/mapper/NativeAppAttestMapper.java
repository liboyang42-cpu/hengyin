package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.NativeAppAttestKey;
import com.chengyinhub.business.domain.NativeAppAttestChallenge;
import com.chengyinhub.business.domain.NativeAppAttestGuard;
import org.apache.ibatis.annotations.Param;

/** All account reads/writes remain owner scoped; key_id is globally unique to prohibit reassignment. */
public interface NativeAppAttestMapper {
    int ensureGuard(@Param("memberId") Long memberId);
    NativeAppAttestGuard lockGuard(@Param("memberId") Long memberId);
    int updateGuard(NativeAppAttestGuard guard);
    int countActiveKeys(@Param("memberId") Long memberId);
    NativeAppAttestKey selectKey(@Param("memberId") Long memberId, @Param("keyId") String keyId);
    NativeAppAttestKey lockKey(@Param("memberId") Long memberId, @Param("keyId") String keyId);
    int insertKey(NativeAppAttestKey key);
    int advanceCounter(@Param("memberId") Long memberId, @Param("keyId") String keyId,
            @Param("oldCounter") Long oldCounter, @Param("newCounter") Long newCounter, @Param("now") Long now);
    int revokeKey(@Param("memberId") Long memberId, @Param("keyId") String keyId, @Param("now") Long now);
    int insertChallenge(NativeAppAttestChallenge challenge);
    NativeAppAttestChallenge lockChallenge(@Param("memberId") Long memberId, @Param("challengeId") String challengeId);
    int consumeChallenge(@Param("memberId") Long memberId, @Param("challengeId") String challengeId,
            @Param("requestDigest") String requestDigest, @Param("now") Long now);
    int pruneChallenges(@Param("memberId") Long memberId, @Param("cutoff") Long cutoff);
}
