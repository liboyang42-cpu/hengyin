package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.SettlementDetail;

import java.util.List;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.CoopGroupVerify;
import com.chengyinhub.business.domain.vo.ClubSessionFeeRegistration;
import com.chengyinhub.business.domain.vo.MerchantSessionFeeLine;

public interface SettlementDetailMapper {
    List<SettlementDetail> selectDetailList(SettlementDetail query);

    int batchInsert(List<SettlementDetail> details);

    SettlementDetail selectSettledDetail(@org.apache.ibatis.annotations.Param("sourceType") String sourceType,
                                         @org.apache.ibatis.annotations.Param("sourceId") Long sourceId);

    /**
     * 某来源全部可判定(status=0/1)明细。多方分账退款须逐条处理:系统1历史活路径已打款后明细仍可能停在0;
     * 是否扣余额由退款服务再问父级真实打款态。selectSettledDetail 的 limit 1 会漏冲商家分成。
     */
    List<SettlementDetail> selectSettledDetails(@org.apache.ibatis.annotations.Param("sourceType") String sourceType,
                                                @org.apache.ibatis.annotations.Param("sourceId") Long sourceId);

    List<SettlementDetail> selectRegistrationRefundAdjustments(@Param("registrationId") Long registrationId,
            @Param("excludeRefundId") Long excludeRefundId);

    int existsAdjustment(@org.apache.ibatis.annotations.Param("sourceType") String sourceType,
                         @org.apache.ibatis.annotations.Param("sourceId") Long sourceId);

    int insertAdjustment(SettlementDetail detail);

    SettlementDetail selectByIdempotencyKeyForUpdate(
            @org.apache.ibatis.annotations.Param("idempotencyKey") String idempotencyKey);

    /** 某商家待结算（status=0 且未入批次 batch_id=0）的明细，供批次生成消费。 */
    List<SettlementDetail> selectPendingByMerchant(@org.apache.ibatis.annotations.Param("merchantId") Long merchantId);

    /** 把某商家所有待结算明细绑定到指定批次。 */
    int bindBatch(@org.apache.ibatis.annotations.Param("batchId") Long batchId,
                  @org.apache.ibatis.annotations.Param("merchantId") Long merchantId);

    /** 把某批次下 status=0 的明细置为已结算 status=1。 */
    int markBatchSettled(@org.apache.ibatis.annotations.Param("batchId") Long batchId);

    /** 系统1活动结算真实打款成功后,把本次覆盖的报名账本同步为已结算。 */
    int markActivityRegistrationDetailsSettled(@org.apache.ibatis.annotations.Param("activityId") Long activityId);

    /** 客诉结案补结一张暂扣报名单后,把它的账本同步为已结算。 */
    int markRegistrationDetailsSettled(@org.apache.ibatis.annotations.Param("registrationId") Long registrationId);

    /** 退款时作废某来源仍待结算（未入批次）的明细，防止后续被结算。 */
    int voidPendingBySource(@org.apache.ibatis.annotations.Param("sourceType") String sourceType,
                            @org.apache.ibatis.annotations.Param("sourceId") Long sourceId);

    /**
     * 撤销券核销时删除该领取记录仍待结算（未入批次）的分成明细。
     *
     * <p>为什么是 DELETE 而不是像退款那样 VOID:settlement_detail 有**无条件**唯一键
     * {@code uk_source_receiver(source_type,source_id,receiver_type,receiver_id)},
     * 撤销后同一张券会被**再次核销**(use_status 已回 0),留着旧行会让重核销的入账
     * 撞唯一键 → 幂等终态不一致直接 500,把「撤销后券恢复可用」这件事本身废掉。
     * 待结算行从未进过批次、没有资金动过,删除即正确冲正;撤销事实由 business_audit_event 留痕。
     * 已入批次/已结算的行不在删除半径内,由调用方读回告警。
     */
    int deletePendingCouponVerification(@org.apache.ibatis.annotations.Param("sourceId") Long sourceId);

    /** 对账：某商家已结算金额与冲销金额。 */
    com.chengyinhub.business.domain.vo.SettlementSummaryVO selectReconciliation(@org.apache.ibatis.annotations.Param("merchantId") Long merchantId);


    /** 本场被商家扫过团码的记录(每商家每场至多一行,uk_group_verify_activity_merchant)。 */
    List<CoopGroupVerify> selectGroupVerifiesByActivity(@Param("activityId") Long activityId);

    /**
     * 计费人头:本场已付、未退(任何金额的已批准退款都不算)、已参加(本场名册的到场事实 C-10:更正优先,
     * 否则领队签到 / 票被核销 / 节点进度)的报名。
     * registrationId 为空 = 整场(排除结算时暂扣的涉诉单);非空 = 补结那一单。锁报名行。
     */
    List<ClubSessionFeeRegistration> selectFeeRegistrationsForUpdate(@Param("activityId") Long activityId,
                                                                     @Param("registrationId") Long registrationId);

    int countInAppExploreRedemption(@Param("redemptionId") Long redemptionId);

    Long selectExploreSessionTopicId(@Param("activityId") Long activityId);

    List<CoopGroupVerify> selectExploreCoopVerifiesByActivity(@Param("activityId") Long activityId);

    /** 每家店每张探索票最多一人，重复章节核销不增加人头。 */
    List<ClubSessionFeeRegistration> selectExploreFeeRegistrationsForUpdate(@Param("activityId") Long activityId,
            @Param("merchantMemberId") Long merchantMemberId, @Param("registrationId") Long registrationId);

    /** 存量线下应收不能与新钱包结算并付。 */
    int countLegacyExploreReceivables(@Param("activityId") Long activityId,
            @Param("merchantMemberId") Long merchantMemberId);

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
