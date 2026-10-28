package com.chengyinhub.business.mapper;

import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.CoopInvite;

/**
 * 协作邀请 Mapper 接口(P1.5)
 */
public interface CoopInviteMapper {
    List<com.chengyinhub.business.domain.CmsRegistrationMerchant> selectSupplyStations(
            @Param("topicId") Long topicId, @Param("merchantMemberId") Long merchantMemberId);
    com.chengyinhub.business.domain.CmsRegistrationMerchant selectSupplyStationForUpdate(
            @Param("stationId") Long stationId, @Param("topicId") Long topicId,
            @Param("merchantMemberId") Long merchantMemberId);
    List<com.chengyinhub.business.domain.CoopOrder> selectEffectiveSupplyOrders(
            @Param("topicId") Long topicId, @Param("merchantMemberId") Long merchantMemberId);
    List<com.chengyinhub.business.domain.CoopOrder> selectEffectiveSupplyOrdersForUpdate(
            @Param("topicId") Long topicId, @Param("merchantMemberId") Long merchantMemberId);
    List<com.chengyinhub.business.domain.CoopOrder> selectOriginalCoopOrdersForUpdate(
            @Param("inviteId") Long inviteId, @Param("regId") Long regId);
    List<com.chengyinhub.business.domain.CmsRegistrationMerchant> selectSupplyStationNames(
            @Param("invites") List<CoopInvite> invites);


    public CoopInvite selectCoopInviteById(Long id);

    /** B1-4:行锁读取(FOR UPDATE),须在事务内调用 */
    public CoopInvite selectCoopInviteByIdForUpdate(Long id);

    public List<CoopInvite> selectCoopInviteList(CoopInvite coopInvite);

    public List<CoopInvite> selectCoopInviteListReadOnly(@Param("query") CoopInvite query, @Param("now") Date now);

    public int insertCoopInvite(CoopInvite coopInvite);

    public int updateCoopInvite(CoopInvite coopInvite);

    /** 挂起邀约超过显式截止或创建后48h 未处理 → 5已过期,返回受影响行数 */
    public int expirePendingInvites(@Param("now") Date now);

    /** W2 招募窗口关闭:数【本期】还挂着的 pending 邀约。判据与下面那条 update 的 where 逐字同口径。 */
    public int countPendingByTopic(@Param("topicId") Long topicId);

    /**
     * W2 招募窗口关闭:把【本期】还挂着的 pending 邀约置 5已过期,返回受影响行数。
     * 与 {@link #expirePendingInvites(Date)} 的区别:那条是全平台按【邀约自己的】超时扫,
     * 这条按【主题招募窗口已关】扫本期 —— 招募都截止了,还挂着的邀约不能再被接受。
     * 幂等靠 where 里的 status = 0:重放时第二次命中 0 行。
     */
    public int expirePendingInvitesByTopic(@Param("topicId") Long topicId, @Param("now") Date now);

    /** 保证金:按微信商户订单号(DEP前缀)查邀约(回调用) */
    public CoopInvite selectByDepositOutTradeNo(@Param("outTradeNo") String outTradeNo);

    /** 保证金:回调处理时按商户订单号锁行,防止与结算抵扣并发改状态。 */
    public CoopInvite selectByDepositOutTradeNoForUpdate(@Param("outTradeNo") String outTradeNo);

    /** 保证金:更新某邀约的保证金字段(状态/订单号/退款号) */
    public int updateDeposit(@Param("id") Long id, @Param("status") Integer status,
                             @Param("outTradeNo") String outTradeNo, @Param("refundNo") String refundNo,
                             @Param("amount") java.math.BigDecimal amount);

    CoopInvite selectByDepositRefundNo(@Param("refundNo") String refundNo);
    int beginDepositRefund(@Param("id") Long id, @Param("outTradeNo") String outTradeNo,
        @Param("refundNo") String refundNo, @Param("amount") java.math.BigDecimal amount);
    int claimDepositRefundDispatch(@Param("id") Long id,@Param("token") String token,@Param("force") boolean force);
    int renewDepositRefundDispatch(@Param("id") Long id,@Param("token") String token);
    int finishDepositRefundDispatch(@Param("id") Long id,@Param("token") String token,@Param("state") String state);
    int confirmDepositRefundResult(@Param("id") Long id,@Param("outTradeNo") String outTradeNo,
        @Param("refundNo") String refundNo,@Param("amount") java.math.BigDecimal amount,@Param("state") String state);
    int markDepositPaidConfirmed(@Param("id") Long id,@Param("outTradeNo") String outTradeNo,
        @Param("amount") java.math.BigDecimal amount,@Param("transactionId") String transactionId);
    int deductHeldDeposit(@Param("id") Long id);
    int updateDepositIfCurrent(@Param("id") Long id,@Param("expectedStatus") Integer expectedStatus,
        @Param("expectedAmount") java.math.BigDecimal expectedAmount,@Param("status") Integer status,
        @Param("amount") java.math.BigDecimal amount);
    CoopInvite selectDepositFactByIdForUpdate(@Param("id") Long id);
    /** Original locally consumed TX + business identity only; no latest/current-attempt selection. */
    List<Map<String,Object>> selectDepositPaidFactReferences(@Param("outTradeNo") String outTradeNo,
        @Param("transactionId") String transactionId);
    Long selectClubOwnerForDepositQueryNowait(@Param("clubId") Long clubId);
    List<CoopInvite> selectDepositsForRefundReconcile();
    List<CoopInvite> selectAcceptedByTopicForUpdate(@Param("topicId") Long topicId);
}
