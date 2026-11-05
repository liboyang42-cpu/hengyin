package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CoopGroupVerify;
import com.chengyinhub.business.domain.SettlementDetail;
import com.chengyinhub.business.domain.vo.ClubSessionFeeRegistration;
import com.chengyinhub.business.domain.vo.MerchantSessionFeeLine;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

/**
 * 第十二轮 k5(2026-11-05 业主):俱乐部场次「按核销付费」商家费,在场次结算时从主理人份额里付。
 * 只读选集 + 主理人分账行的减额;入账、冲销都走既有 settlement_detail / 余额流水。
 */
public interface ClubSessionMerchantFeeMapper {

    /** 本场被商家扫过团码的记录(每商家每场至多一行,uk_group_verify_activity_merchant)。 */
    List<CoopGroupVerify> selectGroupVerifiesByActivity(@Param("activityId") Long activityId);

    /**
     * 计费人头:本场已付、未退(任何金额的已批准退款都不算)、已参加(本场名册的到场事实 C-10:更正优先,
     * 否则领队签到 / 票被核销 / 节点进度)的报名。
     * registrationId 为空 = 整场(排除结算时暂扣的涉诉单);非空 = 补结那一单。锁报名行。
     */
    List<ClubSessionFeeRegistration> selectFeeRegistrationsForUpdate(@Param("activityId") Long activityId,
                                                                     @Param("registrationId") Long registrationId);

    /** 报名支付时落的主理人(receiver_type=merchant)分账行,锁行。 */
    List<SettlementDetail> selectPayeeLinesForUpdate(@Param("registrationId") Long registrationId);

    /** 主理人分账行减去商家费;只减待结算行,且不减成负数。 */
    int reducePayeeLine(@Param("id") Long id, @Param("fee") BigDecimal fee);

    /** 商家视角:本人收到的场次核销费行(含之后退款冲回的部分)。 */
    List<MerchantSessionFeeLine> selectSessionFeeLinesByMerchant(@Param("merchantMemberId") Long merchantMemberId,
                                                                 @Param("activityId") Long activityId);

    /**
     * 余额页「待结算 / X月X日可提现」用的估算:未结算俱乐部场次上按核销付费(shareMode=2)商家的
     * 冻结单价 × 当前计费人头。merchantMemberId 与 activityIds 至少给一个。只读,不锁行。
     */
    List<com.chengyinhub.business.domain.vo.ClubSessionFeeEstimate> selectUnsettledFeeEstimates(
            @Param("merchantMemberId") Long merchantMemberId, @Param("activityIds") List<Long> activityIds);
}
