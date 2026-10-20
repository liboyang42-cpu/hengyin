package com.chengyinhub.business.reward;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Read-only adapter for the accepted W17 ledger. Every award selector includes the owner. */
public interface NonCashRewardReadMapper {
    Row owned(@Param("memberId") long memberId, @Param("awardId") String awardId);
    List<Row> page(@Param("memberId") long memberId, @Param("beforeTime") Long beforeTime,
                   @Param("beforeId") String beforeId, @Param("fetchSize") int fetchSize);
    MerchantRow merchant(long merchantId);
    final class Row {
        public String awardId, pledgeId, payload, fingerprint, state;
        public long memberId, awardedAt;
    }
    final class MerchantRow {
        public Long id, status, accountStatus;
        public Integer delFlag;
    }
}
