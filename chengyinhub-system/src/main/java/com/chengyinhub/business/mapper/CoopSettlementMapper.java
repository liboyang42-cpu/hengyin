package com.chengyinhub.business.mapper;

import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.CoopSettlement;

/**
 * 商家结算单 Mapper 接口(B4-1)
 */
public interface CoopSettlementMapper {

    public int insertCoopSettlement(CoopSettlement coopSettlement);

    public int updateCoopSettlement(CoopSettlement coopSettlement);

    /** 该合作单是否已挂结算单(防重复) */
    public int countByOrderId(Long coopOrderId);

    /** 到期可打款:status=0 且 payable_time<=now */
    public List<CoopSettlement> selectDuePayouts(@Param("now") Date now);

    /** 在单行事务内锁定并重新校验一张仍到期、仍待打款的结算单。 */
    public CoopSettlement selectDuePayoutForUpdate(@Param("id") Long id, @Param("now") Date now);

    /** 非锁定位；取得 topic 锁后必须按主键当前锁读并复核归属。 */
    CoopSettlement selectByClaimId(@Param("claimId") Long claimId);

    CoopSettlement selectByIdForUpdate(@Param("id") Long id);

    /** 锁定后以 status=0→1 CAS 写入打款终态；调用方必须校验恰好影响 1 行。 */
    public int markPaidIfPending(@Param("id") Long id, @Param("payoutTime") Date payoutTime);

    /** 某商家的结算记录(工作台) */
    public List<CoopSettlement> selectByMerchant(@Param("merchantId") Long merchantId);

    /** T7:某主题的全部结算单(俱乐部财务页按主题聚合商家应收/打款状态) */
    public List<CoopSettlement> selectByTopicId(@Param("topicId") Long topicId);

    /**
     * 按受益方查结算行（俱乐部分润页 /api/club/settlement/summary 的数据源）。
     *
     * <p>用 beneficiary_role + beneficiary_id 而不是 merchant_id：同一张表既记商家收入
     * 也记俱乐部分润，按 merchant_id 查会把俱乐部那部分整片漏掉。
     */
    public List<CoopSettlement> selectByBeneficiary(@Param("role") String role,
                                                    @Param("beneficiaryId") Long beneficiaryId);

/** 只读展示：已执行调整和精确结算业务键对应的实际入账。 */
    java.util.List<com.chengyinhub.business.domain.vo.ClubSettlementSummaryRow> selectSummaryByBeneficiary(
            @org.apache.ibatis.annotations.Param("role") String role,
            @org.apache.ibatis.annotations.Param("beneficiaryId") Long beneficiaryId);

    /** 同 selectSummaryByBeneficiary 口径,按主题取(发起人分润页 clubFinance)。 */
    java.util.List<com.chengyinhub.business.domain.vo.ClubSettlementSummaryRow> selectSummaryByTopicId(
            @org.apache.ibatis.annotations.Param("topicId") Long topicId);

    /** 退款/取消:作废该主题下未打款(status=0)结算单,返回作废条数 */
    public int voidUnpaidByTopic(@Param("topicId") Long topicId);

    /**
     * [P0-1] 该主题下某收款方是否已真放款(status=1)。退款冲销据此决定扣不扣余额:
     * status=1 只由 {@code payoutDueCoopSettlements} 在 addBalance 成功之后置,是「钱到账了」的凭据。
     * 按收款方判而不是按主题判 —— 同一主题下 A 放了款 B 没放,不能连坐把 B 也扣了。
     */
    public int countPaidByTopicAndPayee(@Param("topicId") Long topicId, @Param("payeeId") Long payeeId);
}
