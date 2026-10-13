package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ClubDirectMerchantReceivable;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface ClubDirectMerchantReceivableMapper {
    int insertReceivable(ClubDirectMerchantReceivable row);
    ClubDirectMerchantReceivable selectByRedemptionForUpdate(Long redemptionId);
    ClubDirectMerchantReceivable selectOwnedForUpdate(@Param("id") Long id, @Param("topicId") Long topicId,
            @Param("payerClubId") Long payerClubId, @Param("merchantMemberId") Long merchantMemberId);
    List<ClubDirectMerchantReceivable> selectScoped(@Param("topicId") Long topicId,
            @Param("payerClubId") Long payerClubId, @Param("merchantMemberId") Long merchantMemberId);
    int reverse(@Param("row") ClubDirectMerchantReceivable row);
    int recordSettled(@Param("row") ClubDirectMerchantReceivable row, @Param("actor") String actor,
            @Param("voucher") String voucher);
    int recordRefunded(@Param("row") ClubDirectMerchantReceivable row, @Param("actor") String actor,
            @Param("voucher") String voucher);
}
