package com.chengyinhub.business.service;

import java.util.List;

import com.chengyinhub.business.domain.HostTipConfig;
import com.chengyinhub.business.domain.HostTipIntent;
import com.chengyinhub.business.domain.dto.HostTipConfigRequest;
import com.chengyinhub.business.domain.dto.HostTipPrepareRequest;
import com.chengyinhub.business.domain.dto.HostTipPrepareResult;

/**
 * 主持小费:配置 + 顾客自愿支付的意向单。
 *
 * <p><b>资金边界(不可越过)</b>:
 * <ul>
 *   <li>小费 100% 归 host,平台不抽成;通道手续费承担方由配置单独声明;</li>
 *   <li>本服务<b>不加余额、不写分账、不改原订单</b>;</li>
 *   <li>没有受支持的收款通道时,准备接口返回 {@code CHANNEL_NOT_ENABLED}
 *       并把意向单落为"通道未启用" —— <b>不伪造收款</b>。</li>
 * </ul>
 *
 * @author chengyinhub
 */
public interface IHostTipService
{
    /** 主持配置自己的收款小费(只能配置自己;运营代配需走既有权限)。 */
    HostTipConfig upsertConfig(long hostMemberId, HostTipConfigRequest request);

    /** 主持查看自己的配置。 */
    List<HostTipConfig> listMyConfigs(long hostMemberId);

    /**
     * 顾客准备一笔小费。
     *
     * <p>调用前必须已确认:受益 host 存在且启用、金额满足下限、关联订单(若有)属于该顾客。
     * 通道未启用时抛 {@code BountyTipException#CHANNEL_NOT_ENABLED}(HTTP 501),
     * 并把意向单落为 {@code STATUS_CHANNEL_UNSUPPORTED}。
     */
    HostTipPrepareResult prepare(long payerMemberId, HostTipPrepareRequest request);

    /** 顾客取消自己未支付的小费。 */
    HostTipIntent cancel(long payerMemberId, long intentId);

    /** 顾客查看自己的小费记录。 */
    List<HostTipIntent> listMine(long payerMemberId);

    /** 主持查看收到的小费记录(只读;状态由通道回执推进)。 */
    List<HostTipIntent> listForHost(long hostMemberId);

    // ------------------------------------------------------------------ 通道入口(默认不可用)

    /**
     * 通道确认支付。**只能由受支持的通道回调调用**;服务端会先校验通道是否在配置里启用,
     * 未启用时直接拒绝 —— 任何内部调用方都不能用它给单子"手动标已付"。
     */
    HostTipIntent confirmPaidByChannel(long intentId, String externalTradeNo, String paymentChannel);

    /** 通道退款回执(同样只能来自受支持通道)。 */
    HostTipIntent confirmRefundedByChannel(long intentId, String settlementRef, String paymentChannel);
}
