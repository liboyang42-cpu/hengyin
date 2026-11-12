package com.chengyinhub.business.service;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 营销商品成交 → 既有订单事实({@code cms_registration})的唯一入口(M2-F owner)。
 *
 * <p>调用方:M3 {@code MarketingProductPurchaseServiceImpl}(积分成交 / 现金 verified 成交)与激活路径;
 * M8 渠道成交若落到同一商品域亦调用本接口。<b>不得</b>直接 insert {@code cms_registration}、
 * 不得 insert 伪造 PAID、不得自建第二套可玩真源(见 root 裁决 A)。
 *
 * <p>事务:实现方法为 {@code @Transactional(rollbackFor = Exception.class)},加入调用方事务。
 * 调用方须在同一事务内已完成:库存/配额占用、积分扣减或 verified 付款事实落库。
 */
public interface IMarketingProductFulfillmentService {

    /**
     * 成交落单:把一个已付款的商品订单变成 1…N 行 {@code cms_registration}。
     *
     * <p>幂等:同一 {@code memberId + requestId} 重复调用返回**原行**;内容指纹不同 ⇒
     * {@link RegistrationRequestConflictException}(409)。
     *
     * @throws RegistrationRequestConflictException 幂等键复用但内容不同、商品单非已付、分摊不守恒
     * @throws IllegalStateException 目标主题不可售、平台自营判据不成立、配额不足、退款政策无法表达
     */
    FulfillmentOutcome grant(FulfillmentCommand command);

    /**
     * 首次使用型激活:原子回写 {@code expires_at} 并让准入生效,**必须在发放可玩准入之前完成**。
     *
     * <p>未激活时不创建准入行 —— {@code cms_registration.expires_at = NULL} 在
     * {@code CurrentPlayRegistration} 里等于永久有效,不能用来表示"尚未激活"。
     * 重放不延长有效期;并发只有一次生效(靠 {@code uk_reg_member_request(member_id, request_id)})。
     */
    FulfillmentOutcome activateOnFirstUse(String requestId, long memberId, long topicId, Date now);

    /** 按订单号查已落地的准入行(只读;退款/对账/客服定位用)。 */
    java.util.List<Long> findGrantedRegistrationIds(String orderNo);

    /**
     * 渠道撤销:按<b>渠道单号</b>回查本域准入行并给出可撤销判定。
     *
     * <p><b>边界(重要)</b>:本方法**不直接作废已付准入**。渠道单一旦已收款,撤销必须走原退款链路
     * (C07/RefundService → 退款成功广播 → 撤销准入/释放席位/回冲),否则就是"钱不退、权益先没了"
     * 或"权益撤了但钱没退",两边都会资损。本方法负责:
     * <ol>
     *   <li>按 parent_order_no 回查准入行(找不到 ⇒ 抛"渠道单未成交");</li>
     *   <li>已核销(verification_status=1)⇒ 拒绝,列明需人工/退款复核;</li>
     *   <li>全部已处于撤销终态 ⇒ 幂等返回 replayed=true;</li>
     *   <li>可撤销 ⇒ 返回该渠道单的准入行 id,供调用方交给原退款链路撤销。</li>
     * </ol>
     *
     * @param requestId 渠道撤销的幂等键(由渠道域生成),用于调用方去重
     */
    ChannelRevocationOutcome revokeChannel(String parentOrderNo, String reason, String requestId);

    /**
     * 渠道退款**已由服务端验证完成**后,撤销受影响的原准入(沿既有 C07 口径)。
     *
     * <p>这不再是"判定":它会真的把受影响 registration 置为已退款终态
     * ({@code payment_status=4 / registration_status=3},走既有 CAS)、释放 ③ 章节容量,
     * 并留可追溯日志。前提是渠道退款事实已确认,因此**绝不会**在"只是申请/待确认"时调用。
     *
     * <p>本方法<b>不发起任何退款</b>、不碰微信退款、不新增支付/退款状态机、不产生第二资金真源:
     * OTA 的钱只退渠道,这里只是把"钱已退"映射到本域权益。
     *
     * <p>部分退款:本域没有既定政策 ⇒ 明确抛 {@code PARTIAL_CHANNEL_REFUND_UNSUPPORTED},
     * 不擅自按比例撤权。
     */
    ChannelRevocationOutcome consumeVerifiedChannelRefund(ChannelRefundCommand command);
}
