package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 微信支付服务接口
 */
public interface IWechatPayService extends WechatPaymentOperations {
    /** 支付下单、回调验签和退款服务是否都已就绪。 */
    boolean isReady();

    /** 通用支付能力和移动应用 appid 均已就绪，保证金等写状态前必须先检查。 */
    boolean isAppReady();

    /**
     * 创建微信支付订单
     * @param registration 报名记录
     * @return 支付参数
     */
    Map<String, String> createOrder(CmsRegistration registration);

    /**
     * 创建 App 支付订单(trade_type=APP)。只有下单这一步与 JSAPI 不同,
     * 回调/退款/查单/关单/幂等/状态机全部复用同一套。
     * App 下单无 payer 字段,不需要 openid。
     */
    Map<String, String> createAppOrder(CmsRegistration registration);

    /**
     * 通用 App 支付下单(不耦合报名;保证金等复用)。
     * App 下单无 payer 字段,不需要 openid。
     */
    Map<String, String> createGenericAppOrder(String outTradeNo, BigDecimal amountYuan, String description);

    /**
     * 通用 JSAPI 下单(不耦合报名;保证金等复用)。
     * @param outTradeNo  商户订单号
     * @param amountYuan  金额(元)
     * @param description 商品描述
     * @param openid      付款人 openid
     * @return 前端 wx.requestPayment 参数
     */
    Map<String, String> createGenericOrder(String outTradeNo, BigDecimal amountYuan, String description, String openid);

    /**
     * 处理微信支付回调
     */
    String handlePayCallback(String requestBody, Map<String, String> headers);

    /** 处理微信退款结果通知回调(验签+解析+回写打款态)。返回微信 ACK 应答体。 */
    String handleRefundCallback(String requestBody, Map<String, String> headers);

    /** 查微信退款真实状态(SUCCESS/PROCESSING/ABNORMAL/CLOSED),失败返回 null。 */
    String queryRefundStatus(String outRefundNo);

    /** 原路退款的渠道归属与金额事实；NOT_FOUND 只用于渠道明确不存在。 */
    java.util.Map<String,String> queryRefundFacts(String outRefundNo);

    /** Query under the original persisted payment success, never another merchant/app/attempt. Null remains UNKNOWN. */
    java.util.Map<String,String> queryRefundFacts(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference originalPayment, String outRefundNo);

    /** 主动查微信退款态并回写本地 payout(P3 回调的拉取兜底 / admin 刷新)。返回查到的状态。 */
    String refreshPayoutFromWx(String outRefundNo);

    /**
     * 查询订单支付状态
     * @param outTradeNo 商户订单号
     * @return 支付状态信息
     */
    Map<String, String> queryOrderStatus(String outTradeNo);

    /**
     * 关闭订单
     * @param outTradeNo 商户订单号
     * @return 是否成功
     */
    boolean closeOrder(String outTradeNo);

    /** Close exactly the observed provider attempt; never resolve a newer alias for an old observation. */
    boolean closePaymentAttempt(String businessOutTradeNo, String observedWxOutTradeNo);

    /** Must run inside the domain mutation transaction. Locks business then latest attempt; false means stale. */
    boolean lockPaymentAttemptForUpdate(String businessOutTradeNo, String observedWxOutTradeNo);

    /** Additional predicate for domain CLOSED CAS only; caller must already have verified original CLOSED evidence. */
    boolean lockClosablePaymentAttemptForUpdate(String businessOutTradeNo, String observedWxOutTradeNo);


    /**
     * 微信原路退款(退回付款人原支付账户)。
     * @param outTradeNo   原支付商户订单号(= registrationNo)
     * @param outRefundNo  退款单号(确定性,微信按其幂等,防重复退)
     * @param refundAmount 退款金额(元)
     * @param totalAmount  原订单总额(元)
     * @param reason       退款原因
     * @return true=已受理/已退(SUCCESS/PROCESSING);false=失败(调用方应兜底退余额)
     */
    RefundRequestResult submitRefund(String outTradeNo, String outRefundNo, BigDecimal refundAmount,
                                     BigDecimal totalAmount, String reason);

    /** Submit only against an immutable original PAYMENT reference; missing proof remains UNKNOWN. */
    RefundRequestResult submitRefund(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference originalPayment,
                                     String outTradeNo, String outRefundNo, BigDecimal refundAmount, BigDecimal totalAmount, String reason);

    /** 兼容保证金等旧调用；UNKNOWN 与确定失败都返回 false，资金退款编排必须调用 submitRefund。 */
    default boolean refund(String outTradeNo, String outRefundNo, BigDecimal refundAmount,
                           BigDecimal totalAmount, String reason) {
        return submitRefund(outTradeNo, outRefundNo, refundAmount, totalAmount, reason)
                == RefundRequestResult.ACCEPTED;
    }

    /**
     * 商家转账到零钱(批量转账 API,单笔一批)。用于用户/商家提现真实出金。
     * 微信按 (mchid, out_batch_no) 幂等,同号重复调用不会二次出金。
     * @param outBatchNo  商户批次单号(确定性,幂等键)
     * @param outDetailNo 商户明细单号(确定性)
     * @param amountYuan  转账金额(元)
     * @param openid      收款人 openid(须与本商户号 appid 同主体)
     * @param remark      转账备注(<=32字)
     * @return keys: ok("true"/"false")、batchId(微信批次单号,成功时)、error(失败原因)
     */
    /**
     * @param appid **必须与 openid 同源**的应用 appid。
     *   ⚠️ 微信按 (appid, openid) 认人:小程序 openid 配移动应用 appid(或反之)
     *   会被判为「openid 与 appid 不匹配」而拒付。
     *   ⚠️ 当前 ums_member 只有一列 wx_open_id,小程序与 App 的 openid **混存**
     *   (AppLoginService 与 /login/wechat/app 都写这一列),故调用方目前无法
     *   可靠判断来源 —— 这是已知缺口,完整修复需区分 openid 归属(见 R1)。
     */
    Map<String, String> transferToBalance(String outBatchNo, String outDetailNo, BigDecimal amountYuan, String openid, String remark, String appid);

    /**
     * 查询商家转账最终结果(修复「已受理即成功」:发起只是受理,真实到账须查询确认)。
     * @param outBatchNo  商户批次单号
     * @param outDetailNo 商户明细单号
     * @return keys: result(SUCCESS 到账 / FAIL 失败 / PROCESSING 处理中 / UNKNOWN 查询异常)、batchStatus、detailStatus、error。
     *         ★UNKNOWN 表示查询本身异常(网络/服务未就绪),调用方不得据此判失败,应保持处理中并稍后重试。
     */
    Map<String, String> queryTransferResult(String outBatchNo, String outDetailNo);

    /** Submit only the original immutable, hash-validated withdrawal intent; accepted is not settled. */
    Map<String, String> transferToBalance(CashWithdrawTransferIntent intent);

    /** Only original merchant/app/batch/detail/amount/receiver evidence may authorize a terminal result. */
    Map<String, String> queryTransferResult(CashWithdrawTransferIntent intent);

}
