package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.ChannelVerificationCommand;
import com.chengyinhub.business.domain.dto.ChannelVerificationOutcome;

/**
 * 真实渠道验证端口（L2 边界）。
 *
 * <p>实现方必须：
 * <ul>
 *   <li>用渠道侧 API / 回调签名 / 合同约定凭据验证订单，不得信任客户端或运营自填；</li>
 *   <li>返回渠道实付金额、币种、映射后的我方商品与数量；</li>
 *   <li>无法验证时返回非 VERIFIED + reasonCode，绝不返回“假成功”。</li>
 * </ul>
 *
 * <p>默认实现 {@code Noop...} 返回 {@code PROVIDER_NOT_CONFIGURED}，
 * 因此本域在真实凭据落地前保持未启用，但表结构、幂等、撤销与对账可先验收。
 */
public interface ChannelOrderVerificationProvider {

    /** 与 {@code marketing_channel_voucher_account.channel_key} / 回调路径一致的稳定键。 */
    String providerKey();

    ChannelVerificationOutcome verify(ChannelVerificationCommand command);
}
