package com.chengyinhub.business.service;

import java.math.BigDecimal;

/**
 * 已核验的付款事实(typed)。
 *
 * <p>现金:{@code verifiedFactId + verifiedFactHash + verifiedAmount} 必须来自
 * {@code WechatVerifiedPaymentFactService.requireVerified(...)};本服务会再用
 * {@code requireConsumablePayment} 复核一次并使用其金额,**不信任**任何客户端或上游自报数值。
 * 积分:{@code pointsUsed} 是调用方在同一事务内已扣减的真实积分数。
 */
public final class PaymentFacts {

    public static final int MODE_POINTS = 1;
    public static final int MODE_CASH = 2;
    /** 第三方渠道(OTA 等)。事实由渠道域服务端回读,不是调用方自报。 */
    public static final int MODE_CHANNEL = 3;

    public int mode;

    /** 积分成交:实际扣减积分数。 */
    public long pointsUsed;

    /** 现金成交:verified fact 主键。 */
    public long verifiedFactId;
    /** 现金成交:verified fact 哈希。 */
    public String verifiedFactHash;
    /** 现金成交:事实里的金额(元)。本服务会用事实库的值复核,不一致即拒。 */
    public BigDecimal verifiedAmount;
    /** 现金成交:微信交易号(写入首行 transaction_id)。 */
    public String transactionId;

    // ---- MODE_CHANNEL:渠道维度。全部只作**查询键**,事实值由渠道域服务端回读校验 ----
    /** 渠道标识(如 OTA 供应商 key)。 */
    public String sourceProviderKey;
    /** 渠道订单号(唯一绑定键,写 parent_order_no)。 */
    public String externalOrderNo;
    /** 渠道事实主键(渠道域不可变事实表)。 */
    public long externalFactId;
    /** 渠道事实哈希,仅用于**定位/复核**,不当作付款证明。 */
    public String externalFactHash;

    public static PaymentFacts points(long pointsUsed) {
        PaymentFacts facts = new PaymentFacts();
        facts.mode = MODE_POINTS;
        facts.pointsUsed = pointsUsed;
        return facts;
    }

    public static PaymentFacts cash(long factId, String factHash, BigDecimal amount, String transactionId) {
        PaymentFacts facts = new PaymentFacts();
        facts.mode = MODE_CASH;
        facts.verifiedFactId = factId;
        facts.verifiedFactHash = factHash;
        facts.verifiedAmount = amount;
        facts.transactionId = transactionId;
        return facts;
    }
}
