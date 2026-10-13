package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.WxPaymentCallbackOutbox;

/**
 * P1-5 provider mock contract(任务书 1.7 节第5项):我方主动发起的终态查询/补发重试出站队列
 * 契约,只定义接口,不含实现——消费方(定时任务轮询、真正发起微信查询调用)留给 P1-6 的 K/L
 * (feature/p1-wechat-pay-refund / feature/p1-wechat-payout)接入
 * {@code wx_payment_callback_outbox} 表(见 migration_wx_payment_callback_inbox_outbox_20260725.sql)。
 *
 * <p>★任务书 1.6 节铁律在接口层面固化:</p>
 * <ul>
 *   <li>重试次数上限默认3次(间隔递增),超过必须转 {@code escalateToManual},不得继续自动重试；</li>
 *   <li>{@code moneyAffecting=true} 的动作(如二次转账)一律不接受自动重试，
 *       {@link #claimForRetry}/{@code claim} 的实现必须拒绝对这类记录发起下一轮自动派发。</li>
 * </ul>
 */
public interface IWxPaymentCallbackOutboxService {

    /** 登记一条待发起的出站请求(如支付/退款/转账终态查询),同 idempotencyKey 重复登记应幂等短路。 */
    boolean enqueue(int bizType, Long bizId, String idempotencyKey, boolean moneyAffecting);

    /**
     * 领取一批可派发的记录(state=pending/retryable 且 next_attempt_at 已到)。
     * moneyAffecting=true 且已进入 manual_required 的记录不得被领取。
     */
    java.util.List<WxPaymentCallbackOutbox> claimForRetry(int limit);

    /** 本次派发成功,标记为终态完成。 */
    boolean complete(Long id, String ownerToken, long fenceToken);

    /**
     * 本次派发失败,按间隔递增计算下次重试时间;attemptCount 达到 maxAttempts 后
     * 必须转 manual_required 而不是继续重试(任务书1.6:不设计成无限重试)。
     */
    boolean retryOrEscalate(Long id, String ownerToken, long fenceToken, String errorCode);

    /** 直接转人工(如识别到 moneyAffecting 场景),跳过自动重试。 */
    boolean escalateToManual(Long id, String ownerToken, long fenceToken, String reason);
}
