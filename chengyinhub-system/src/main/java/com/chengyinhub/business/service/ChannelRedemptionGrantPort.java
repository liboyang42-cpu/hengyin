package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.ChannelGrantCommand;
import com.chengyinhub.business.domain.dto.ChannelGrantResult;
import com.chengyinhub.business.domain.dto.ChannelGrantRevocation;

/**
 * 渠道兑换 → 既有 {@code cms_registration} / {@code registration_entitlement} 的权威 grant 端口。
 *
 * <p><b>所有权</b>：本接口是 M8 与 M2-F 的精确边界；实现必须由 M2-F（原报价/建单唯一 writer）
 * 在 {@code CmsRegistrationServiceImpl} 的事务内提供，本域不直接 insert 订单或伪造 PAID。
 *
 * <p><b>输入事实</b>：{@link ChannelGrantCommand} 只携带已验证的外部订单引用
 * （题头来源 CHANNEL、provider、external order、不可变 fact id/hash、原金额币种、数量、责任）。
 * 端口必须：
 * <ul>
 *   <li>在同一事务内复用既有注册/权益服务，产出 {@code PlayNodeAccessService} 认可的
 *       {@code cms_registration}（及 ③ 场景的 {@code registration_entitlement}）；</li>
 *   <li>同一 {@code requestId}+指纹只建一次；不同指纹 409；</li>
 *   <li>不把渠道事实混入微信/积分事实；不直接写 PAID 或零元已付；</li>
 *   <li>撤销时按原单回查；已使用内容拒绝回收（已核销不复活）。</li>
 * </ul>
 *
 * <p>默认实现 {@code DefaultClosedChannelRedemptionGrantPort} 返回未接线，
 * 因此 provider 未配置或 M2-F 端口未接入前，兑换端不会宣称完成。
 */
public interface ChannelRedemptionGrantPort {

    ChannelGrantResult grant(ChannelGrantCommand command);

    /** 撤销渠道成交对应的既有注册事实；已使用内容抛 {@code ENTITLEMENT_IN_USE}。 */
    ChannelGrantResult revoke(ChannelGrantRevocation command);
}
