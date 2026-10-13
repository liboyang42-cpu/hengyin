package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantOperator;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface MerchantOperatorMapper {
    MerchantOperator selectActiveByMemberId(Long operatorMemberId);
    MerchantOperator selectActiveByMemberIdForUpdate(Long operatorMemberId);
    MerchantOperator selectByIdForUpdate(Long id);
    List<MerchantOperator> selectTeamByMerchantId(Long merchantId);
    int insertMerchantOperator(MerchantOperator operator);
    int updateRoleCas(@Param("id") Long id, @Param("merchantId") Long merchantId,
                      @Param("expectedVersion") Integer expectedVersion,
                      @Param("roleCode") String roleCode, @Param("updateBy") String updateBy);
    int revokeCas(@Param("id") Long id, @Param("merchantId") Long merchantId,
                  @Param("expectedVersion") Integer expectedVersion,
                  @Param("revokedByMemberId") Long revokedByMemberId,
                  @Param("revokeReason") String revokeReason, @Param("updateBy") String updateBy);
    int revokeAllActiveByMerchantId(@Param("merchantId") Long merchantId,
                                    @Param("revokeReason") String revokeReason,
                                    @Param("updateBy") String updateBy);
}
