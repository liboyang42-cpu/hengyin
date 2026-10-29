package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.WxPaymentCallbackInbox;

/**
 * P1-5 provider mock contract(任务书 1.7 节第5项):支付/退款/转账回调 inbox 的业务幂等与
 * fencing 权威接口。签名照抄已验证的 {@link IWxContentCallbackInboxService}(内容审核回调)
 * 范式,只把媒体审核语义换成支付回调语义;只定义接口,不含实现——具体消费方
 * (验签、状态推进)留给 P1-6 的 K(feature/p1-wechat-pay-refund)接入
 * {@code wx_payment_callback_inbox} 表(见 migration_wx_payment_callback_inbox_outbox_20260725.sql)。
 *
 * <p>★K 块真正要实现的"支付/退款/转账"业务合同仍是既有的 {@link IWechatPayService}
 * (createOrder/refund/transferToBalance/queryTransferResult 等已冻结,不在此重复定义)，
 * 本接口只负责"同一条回调不重复处理"这一层 fencing,是 IWechatPayService 的补充而非替代。</p>
 */
public interface IWxPaymentCallbackInboxService {

    /**
     * 首次收到回调时登记(未登记过则插入 waiting 态,已登记返回 false 由调用方按幂等处理)。
     * 已登记的 notify_hash 必须对应同一业务(bizType/bizId/eventType);不一致时告警并抛
     * {@link IdentityConflictException},调用方回 FAIL,绝不按「重复成功」处理。
     */
    boolean register(String notifyId, int bizType, Long bizId, String eventType);

    /** Verified SUCCESS only; immutable provider evidence is bound to the original inbox identity. */
    boolean registerVerified(String notifyId, int bizType, Long bizId, String eventType,
            com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference facts);

    /** Checks the immutable fact binding even for an already-DONE replay before invoking the existing fenced processor. */
    boolean processVerified(String notifyId,
            com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference facts, Mutation mutation);

    /** 该回调是否已登记过(用于快速幂等短路,不做业务判断)。 */
    boolean isRegistered(String notifyId);

    /**
     * 领取处理权并在 Mutation 内完成业务状态变更,retryable 结果允许后续重试领取。
     * @return 是否成功推进(被别的 worker 抢占/已终态时返回 false)
     */
    boolean process(String notifyId, Mutation mutation);

    /** 同一 notify_hash 已登记为另一笔业务:拒收,不得当作重复成功。 */
    class IdentityConflictException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public IdentityConflictException(String message) {
            super(message);
        }
    }

    interface Mutation {
        /** 在目标行已经 FOR UPDATE 后，确认当前业务单据状态仍适合应用本次回调。 */
        boolean isCurrent(WxPaymentCallbackInbox inbox);

        /** 只做数据库业务状态变更；返回值必须有真实 affected/already-applied 证据。 */
        boolean apply(WxPaymentCallbackInbox inbox);
    }
}
