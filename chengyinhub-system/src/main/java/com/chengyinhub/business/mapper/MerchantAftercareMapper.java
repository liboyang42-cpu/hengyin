package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantAftercareResponse;
import com.chengyinhub.business.domain.MerchantAftercareListRow;
import com.chengyinhub.business.domain.MerchantRefundPolicySnapshot;
import com.chengyinhub.business.domain.RefundApplication;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface MerchantAftercareMapper {
    Long lockActiveMerchantOwner(@Param("merchantId") Long merchantId,
                                 @Param("ownerMemberId") Long ownerMemberId);

    RefundApplication selectOwnedRefundById(@Param("merchantId") Long merchantId,
                                            @Param("ownerMemberId") Long ownerMemberId,
                                            @Param("refundId") Long refundId);

    RefundApplication selectOwnedRefundByIdForUpdate(@Param("merchantId") Long merchantId,
                                                     @Param("ownerMemberId") Long ownerMemberId,
                                                     @Param("refundId") Long refundId);

    /** 详情头展示列(活动名/申请人昵称/转平台时间),与 selectOwnedRefundById 同一归属范围;只读。 */
    MerchantAftercareListRow selectOwnedRefundDisplay(@Param("merchantId") Long merchantId,
                                                      @Param("ownerMemberId") Long ownerMemberId,
                                                      @Param("refundId") Long refundId);

    MerchantRefundPolicySnapshot selectOwnedRefundPolicySnapshot(
            @Param("merchantId") Long merchantId,
            @Param("ownerMemberId") Long ownerMemberId,
            @Param("refundId") Long refundId);

    List<MerchantAftercareListRow> selectOwnedRefundList(@Param("merchantId") Long merchantId,
                                                         @Param("ownerMemberId") Long ownerMemberId,
                                                         @Param("bucket") String bucket,
                                                         @Param("offset") int offset,
                                                         @Param("limit") int limit);

    long countOwnedRefundList(@Param("merchantId") Long merchantId,
                              @Param("ownerMemberId") Long ownerMemberId,
                              @Param("bucket") String bucket);

    List<MerchantAftercareResponse> selectResponses(@Param("merchantId") Long merchantId,
                                                    @Param("refundId") Long refundId);

    MerchantAftercareResponse selectLatestOpinion(@Param("merchantId") Long merchantId,
                                                  @Param("refundId") Long refundId);

    MerchantAftercareResponse selectByRequestIdForUpdate(@Param("merchantId") Long merchantId,
                                                         @Param("requestId") String requestId);

    /** 该凭证 key 已被其他退款单的商家记录引用的条数(一张凭证只能用于一张退款单)。 */
    int countEvidenceKeyUsedByOtherRefund(@Param("evidenceKey") String evidenceKey,
                                          @Param("refundId") Long refundId);

    int insertResponse(MerchantAftercareResponse response);
}
