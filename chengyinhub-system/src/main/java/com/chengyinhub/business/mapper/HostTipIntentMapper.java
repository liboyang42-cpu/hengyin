package com.chengyinhub.business.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.chengyinhub.business.domain.HostTipIntent;

/**
 * 主持小费意向单 Mapper。
 *
 * <p>⚠️ 这里<b>没有</b>任何余额、钱包、分账写入。状态推进只做 CAS;
 * 真实支付/退款只能由受支持的通道回调驱动,本域默认不写 PAID。
 *
 * @author chengyinhub
 */
public interface HostTipIntentMapper
{
    HostTipIntent selectById(Long id);

    HostTipIntent selectByIdForUpdate(Long id);

    /** 幂等查:同一付款人的同一 requestId 只应有一条。 */
    HostTipIntent selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    List<HostTipIntent> selectByHost(@Param("hostMemberId") Long hostMemberId);

    List<HostTipIntent> selectByPayer(@Param("payerMemberId") Long payerMemberId);

    int insertIntent(HostTipIntent intent);

    /** 顾客取消:仅 CREATED / AWAITING_PAYMENT 可取消,且必须不改变已付事实。 */
    int cancelIfOpen(@Param("id") Long id,
                     @Param("payerMemberId") Long payerMemberId);

    /**
     * 通道回执置已支付:仅 AWAITING_PAYMENT 可置 PAID。
     *
     * <p>⚠️ 本域不调用它 —— 没有受支持的通道,写 PAID 就是伪造收款。
     * 方法存在只为"接入真实通道时的唯一入口",届时由通道适配层调用。
     */
    int markPaidIfAwaiting(@Param("id") Long id,
                           @Param("externalTradeNo") String externalTradeNo);

    /** 退款:仅 PAID 可置 REFUNDED(需真实退款回执)。 */
    int markRefundedIfPaid(@Param("id") Long id,
                           @Param("settlementRef") String settlementRef);
}
