package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.SmsCouponHistory;
import org.apache.ibatis.annotations.Param;

/** Append-only proposed snapshot storage. No update, delete or historical backfill method. */
public interface CouponTermsSnapshotMapper {
    SmsCoupon lockDefinition(@Param("couponId") long couponId, @Param("issuer") long issuer, @Param("merchant") long merchant);
    Row byNonce(@Param("issuer") long issuer, @Param("nonce") String nonce);
    Row bySource(@Param("receipt") String receipt, @Param("beneficiary") long beneficiary, @Param("couponId") long couponId);
    SmsCouponHistory ownedHistory(@Param("beneficiary") long beneficiary, @Param("historyId") long historyId);
    Row ownedSnapshot(@Param("beneficiary") long beneficiary, @Param("historyId") long historyId);
    int insert(Row row);
    final class Row {
        public Long historyId, couponId, issuerMemberId, merchantId, actorMemberId, beneficiaryMemberId, recordedAt;
        public String nonce, sourceReceiptId, intentDigest, termsJson, termsDigest;
    }
}
