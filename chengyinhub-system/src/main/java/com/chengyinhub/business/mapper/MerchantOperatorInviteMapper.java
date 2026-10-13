package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantOperatorInvite;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface MerchantOperatorInviteMapper {
    MerchantOperatorInvite selectByTokenHash(String tokenHash);
    MerchantOperatorInvite selectByTokenHashForUpdate(String tokenHash);
    MerchantOperatorInvite selectAcceptedByConsumedToken(
            @Param("acceptedByMemberId") Long acceptedByMemberId,
            @Param("originalTokenHash") String originalTokenHash);
    MerchantOperatorInvite selectAcceptedByConsumedTokenForUpdate(
            @Param("acceptedByMemberId") Long acceptedByMemberId,
            @Param("originalTokenHash") String originalTokenHash);
    MerchantOperatorInvite selectByIdForUpdate(Long id);
    MerchantOperatorInvite selectByRequestId(@Param("merchantId") Long merchantId,
                                             @Param("requestId") String requestId);
    MerchantOperatorInvite selectByRequestIdForUpdate(@Param("merchantId") Long merchantId,
                                                      @Param("requestId") String requestId);
    List<MerchantOperatorInvite> selectTeamInvites(Long merchantId);
    int insertMerchantOperatorInvite(MerchantOperatorInvite invite);
    int acceptCas(@Param("id") Long id, @Param("expectedVersion") Integer expectedVersion,
                  @Param("acceptedByMemberId") Long acceptedByMemberId);
    int revokeCas(@Param("id") Long id, @Param("merchantId") Long merchantId,
                  @Param("expectedVersion") Integer expectedVersion,
                  @Param("revokedByMemberId") Long revokedByMemberId,
                  @Param("revokeReason") String revokeReason);
    int expireCas(@Param("id") Long id, @Param("expectedVersion") Integer expectedVersion);
    int revokeAllPendingByMerchantId(@Param("merchantId") Long merchantId,
                                     @Param("revokeReason") String revokeReason);
}
