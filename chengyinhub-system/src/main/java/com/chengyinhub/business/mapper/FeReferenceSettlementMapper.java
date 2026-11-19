package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.FeReferenceSettlement;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** F11 自由探索店铺参考结算(fe_reference_settlement)。只此一处读写,不进任何资金汇总。 */
public interface FeReferenceSettlementMapper {

    int insertReference(FeReferenceSettlement row);

    FeReferenceSettlement selectByIdempotencyKey(@Param("key") String key);

    /** 撤销核销:该核销的未结清行置 VOID;作者自营(0 元、直接结清)行一并作废。 */
    int voidByRedemption(@Param("redemptionId") Long redemptionId);

    /** 整单退款:这一单还 OPEN 且未核销(无核销或核销已撤销)的行置 VOID;已核销行保持 OPEN。 */
    int voidUnredeemedByRegistration(@Param("registrationId") Long registrationId);

    /** 店铺视角:merchant_id = 本店 member_id;status 为空时不含 VOID。 */
    List<FeReferenceSettlement> selectForMerchant(@Param("merchantId") Long merchantId,
                                                  @Param("status") String status);

    /** 收款人视角:当前场次收款人 = memberId;activityId 为空时列出全部,不含 VOID。 */
    List<FeReferenceSettlement> selectForPayer(@Param("memberId") Long memberId,
                                               @Param("activityId") Long activityId);

    /** 收款人标记结清前锁行;只返回 memberId 为当前场次收款人的那些行。 */
    List<FeReferenceSettlement> selectOwnedByPayerForUpdate(@Param("memberId") Long memberId,
                                                            @Param("ids") List<Long> ids);

    int markSettledOffline(@Param("id") Long id, @Param("memberId") Long memberId,
                           @Param("voucherNo") String voucherNo, @Param("note") String note);

    FeReferenceSettlement selectOwnedByMerchantForUpdate(@Param("id") Long id,
                                                         @Param("merchantId") Long merchantId);

    int markDisputed(@Param("id") Long id, @Param("merchantId") Long merchantId, @Param("note") String note);
}
