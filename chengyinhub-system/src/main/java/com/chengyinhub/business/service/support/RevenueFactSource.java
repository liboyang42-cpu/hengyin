package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.RefundApplication;

import java.util.Optional;

/**
 * 资金事实读取端口：把"哪些已持久化的服务端事实可以授权记账"集中到一个可替换接缝。
 *
 * <p>生产实现（{@code WechatRevenueFactSource}）有两个来源，二者都只认**不可变已验证事实**：
 * <ul>
 *   <li>微信订单：{@code wx_verified_payment_fact}（SDK 查单后落库）；</li>
 *   <li>渠道/OTA 订单：{@code marketing_channel_order_verification}（M8 渠道域，服务端验证）。</li>
 * </ul>
 * 不使用 {@code payment_status} 本身当付款证明、不使用 total/original 回退金额、也不相信任何
 * HTTP 请求里的金额。渠道分支还要求「同 parent_order_no 各行分摊之和 == 渠道已验证总额」，
 * 且返回的是**本行分摊额**而不是整单金额。
 * 单测可以用它构造各种反例而不碰数据库。
 */
public interface RevenueFactSource {

    /** 已验证支付成功事实。折后实付与币种的唯一真源。 */
    record PaymentFact(long amountFen, String currency, String factHash, String transactionId) {
    }

    /**
     * 已验证退款成功事实(通道回执)。
     *
     * @param amountFen 本次退款金额(分)
     * @param totalFen  原支付金额(分)，用于确认退款归属同一笔原单
     */
    record RefundFact(long amountFen, long totalFen, String currency, String factHash, String refundNo) {
    }

    /**
     * 该报名单的已验证支付事实。没有就返回 empty —— 调用方必须据此落待核事实，
     * 绝不能拿 payment_status 或订单金额字段顶上。
     */
    Optional<PaymentFact> verifiedPayment(CmsRegistration registration);

    /**
     * 该退款单的已验证退款事实。同时要求：
     * 退款单已到"通道确认到账"状态、退款号存在、退款事实与该退款单的原单/金额一致。
     * 申请或后台批准都不满足这里，返回 empty。
     */
    Optional<RefundFact> verifiedRefund(RefundApplication refund, CmsRegistration registration);
}
